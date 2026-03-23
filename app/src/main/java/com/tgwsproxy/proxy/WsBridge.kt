package com.tgwsproxy.proxy

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

/**
 * Core TCP ↔ WebSocket bridge for Telegram traffic.
 *
 * Architecture:
 * ┌─────────────┐       ┌──────────────┐       ┌──────────────┐
 * │  TUN device  │──TCP──│  LocalProxy   │──WSS──│  Telegram DC │
 * │  (VpnService)│       │  (this class) │       │  (kws*.tg)   │
 * └─────────────┘       └──────────────┘       └──────────────┘
 *
 * For each Telegram TCP connection coming from the TUN:
 * 1. Read the 64-byte MTProto init packet
 * 2. Extract DC ID and media flag
 * 3. Connect via WSS to the appropriate kws domain
 * 4. Bridge bidirectionally: TCP→WS and WS→TCP
 * 5. If WS fails, fall back to direct TCP
 */
class WsBridge(
    private val dcConfig: Map<Int, String> // dc_id -> target_ip
) {
    companion object {
        private const val TAG = "WsBridge"
        private const val WS_CONNECT_TIMEOUT_MS = 10_000L
        private const val TCP_BUFFER_SIZE = 65536
    }

    // DCs where WS is known to fail (302 redirect) — use TCP fallback
    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()

    // Cooldown tracking for failed WS attempts
    private val dcFailUntil = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private val DC_FAIL_COOLDOWN_MS = 30_000L

    // OkHttp client with standard TLS verification
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(WS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Handle a new TCP connection from the local proxy server.
     * This is called for each connection that the SOCKS5/local server accepts.
     *
     * @param clientSocket The TCP socket from the local proxy
     * @param destIp The original destination IP (Telegram DC)
     * @param destPort The original destination port
     */
    suspend fun handleConnection(
        clientSocket: Socket,
        destIp: String,
        destPort: Int
    ) {
        val label = "${clientSocket.remoteSocketAddress}"

        try {
            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()

            // Step 1: Read the 64-byte MTProto init packet
            val init = ByteArray(64)
            var read = 0
            while (read < 64) {
                val n = inputStream.read(init, read, 64 - read)
                if (n < 0) {
                    Log.d(TAG, "[$label] Client disconnected before init")
                    return
                }
                read += n
            }

            // Step 2: Extract DC ID from init packet
            var initInfo = MtProtoParser.extractDcFromInit(init)
            var initData = init
            var patched = false

            // Android clients with useSecret=0 may have random dc_id — patch it
            if (initInfo == null) {
                val knownDc = TelegramConstants.IP_TO_DC[destIp]
                if (knownDc != null && knownDc.dc in dcConfig) {
                    initData = MtProtoParser.patchInitDc(init, knownDc.dc, knownDc.isMedia)
                    initInfo = MtProtoParser.InitInfo(knownDc.dc, knownDc.isMedia)
                    patched = true
                }
            }

            if (initInfo == null || initInfo.dc !in dcConfig) {
                Log.w(TAG, "[$label] Unknown DC for $destIp:$destPort → TCP passthrough")
                tcpFallback(inputStream, outputStream, destIp, destPort, initData, label)
                return
            }

            val dc = initInfo.dc
            val isMedia = initInfo.isMedia
            val dcKey = dc to isMedia
            val mediaTag = if (isMedia) " media" else ""
            val targetIp = dcConfig[dc]!!

            // Step 3: Check WS blacklist
            if (dcKey in wsBlacklist) {
                Log.d(TAG, "[$label] DC$dc$mediaTag WS blacklisted → TCP fallback")
                tcpFallback(inputStream, outputStream, destIp, destPort, initData, label, dc, isMedia)
                return
            }

            // Step 4: Try WebSocket connection
            val domains = TelegramConstants.wsDomains(dc, isMedia)
            val now = System.currentTimeMillis()
            val failUntil = dcFailUntil[dcKey] ?: 0L
            val timeout = if (now < failUntil) 2000L else WS_CONNECT_TIMEOUT_MS

            var ws: WebSocket? = null
            var wsQueue: LinkedBlockingQueue<ByteArray?>? = null
            var allRedirects = true
            var anyRedirect = false

            for (domain in domains) {
                val url = "wss://$domain/apiws"
                Log.i(TAG, "[$label] DC$dc$mediaTag → $url via $targetIp")

                try {
                    val result = connectWebSocket(targetIp, domain, timeout)
                    ws = result.first
                    wsQueue = result.second
                    allRedirects = false
                    break
                } catch (e: WsRedirectException) {
                    anyRedirect = true
                    Log.w(TAG, "[$label] DC$dc$mediaTag got redirect from $domain")
                    continue
                } catch (e: Exception) {
                    ProxyStats.onWsError()
                    allRedirects = false
                    Log.w(TAG, "[$label] DC$dc$mediaTag WS connect failed: ${e.message}")
                }
            }

            // WS failed → fallback
            if (ws == null) {
                if (anyRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    Log.w(TAG, "[$label] DC$dc$mediaTag blacklisted for WS (all 302)")
                } else {
                    dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
                }

                Log.i(TAG, "[$label] DC$dc$mediaTag → TCP fallback to $destIp:$destPort")
                tcpFallback(inputStream, outputStream, destIp, destPort, initData, label, dc, isMedia)
                return
            }

            // WS success
            dcFailUntil.remove(dcKey)
            ProxyStats.onConnectionOpened(isWs = true)

            // Create message splitter if we patched the init
            val splitter = if (patched) {
                try { MsgSplitter(initData) } catch (e: Exception) { null }
            } else null

            // Send the buffered init packet
            ws.send(initData.toByteString())

            // Step 5: Bidirectional bridge
            try {
                bridgeWs(inputStream, outputStream, ws, wsQueue!!, label, dc, isMedia, splitter)
            } finally {
                ProxyStats.onConnectionClosed()
                ws.close(1000, null)
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[$label] Unexpected error", e)
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Connect WebSocket to Telegram DC via OkHttp.
     * Returns the WebSocket and a blocking queue for received messages.
     */
    private suspend fun connectWebSocket(
        targetIp: String,
        domain: String,
        timeout: Long
    ): Pair<WebSocket, LinkedBlockingQueue<ByteArray?>> = withContext(Dispatchers.IO) {

        val queue = LinkedBlockingQueue<ByteArray?>()
        val openLatch = java.util.concurrent.CountDownLatch(1)
        var connectError: Exception? = null

        val request = Request.Builder()
            .url("wss://$domain/apiws")
            .header("Host", domain)
            .header("Origin", "https://web.telegram.org")
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .build()

        // OkHttp resolves domain → we need to force it to use targetIp
        // We achieve this by using a custom DNS that resolves the domain to targetIp
        val client = okHttpClient.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return listOf(java.net.InetAddress.getByName(targetIp))
                }
            })
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS opened to $domain via $targetIp")
                openLatch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                queue.put(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                if (code in listOf(301, 302, 303, 307, 308)) {
                    connectError = WsRedirectException(code, response?.header("Location"))
                } else {
                    connectError = Exception("WS failure: ${t.message} (HTTP $code)")
                }
                openLatch.countDown()
                queue.put(null) // signal close
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                queue.put(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                queue.put(null)
            }
        }

        val ws = client.newWebSocket(request, listener)

        // Wait for connection or timeout
        val opened = openLatch.await(timeout, TimeUnit.MILLISECONDS)
        if (!opened) {
            ws.cancel()
            throw Exception("WS connect timeout to $domain")
        }

        connectError?.let {
            ws.cancel()
            throw it
        }

        ws to queue
    }

    /**
     * Bidirectional TCP ↔ WebSocket bridge.
     * Runs two concurrent loops: TCP→WS and WS→TCP.
     */
    private suspend fun bridgeWs(
        input: InputStream,
        output: OutputStream,
        ws: WebSocket,
        wsQueue: LinkedBlockingQueue<ByteArray?>,
        label: String,
        dc: Int,
        isMedia: Boolean,
        splitter: MsgSplitter?
    ) = coroutineScope {
        val mediaTag = if (isMedia) "m" else ""

        // TCP → WS
        val tcpToWs = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(TCP_BUFFER_SIZE)
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    ProxyStats.addBytesUp(n.toLong())

                    val chunk = buf.copyOfRange(0, n)
                    if (splitter != null) {
                        val parts = splitter.split(chunk)
                        for (part in parts) {
                            ws.send(part.toByteString())
                        }
                    } else {
                        ws.send(chunk.toByteString())
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.d(TAG, "[$label] tcp→ws ended: ${e.message}")
            }
        }

        // WS → TCP
        val wsToTcp = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val data = wsQueue.poll(60, TimeUnit.SECONDS) ?: break
                    ProxyStats.addBytesDown(data.size.toLong())
                    output.write(data)
                    output.flush()
                }
            } catch (e: Exception) {
                if (isActive) Log.d(TAG, "[$label] ws→tcp ended: ${e.message}")
            }
        }

        // Wait for either direction to finish
        select(tcpToWs, wsToTcp)

        // Cancel the other
        tcpToWs.cancel()
        wsToTcp.cancel()
    }

    /**
     * Wait for the first of two jobs to complete.
     */
    private suspend fun select(a: Job, b: Job) {
        try {
            kotlinx.coroutines.selects.select<Unit> {
                a.onJoin {}
                b.onJoin {}
            }
        } catch (_: CancellationException) {}
    }

    /**
     * TCP fallback: direct connection to the original Telegram DC IP.
     * Used when WS is unavailable (302 redirect, timeout, etc.)
     */
    private suspend fun tcpFallback(
        clientInput: InputStream,
        clientOutput: OutputStream,
        destIp: String,
        destPort: Int,
        initData: ByteArray,
        label: String,
        dc: Int? = null,
        isMedia: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            val remote = Socket()
            remote.connect(InetSocketAddress(destIp, destPort), 10_000)
            ProxyStats.onConnectionOpened(isWs = false)

            try {
                val remoteOut = remote.getOutputStream()
                val remoteIn = remote.getInputStream()

                // Send the buffered init packet
                remoteOut.write(initData)
                remoteOut.flush()

                // Bidirectional TCP ↔ TCP bridge
                coroutineScope {
                    val up = launch {
                        try {
                            val buf = ByteArray(TCP_BUFFER_SIZE)
                            while (isActive) {
                                val n = clientInput.read(buf)
                                if (n < 0) break
                                ProxyStats.addBytesUp(n.toLong())
                                remoteOut.write(buf, 0, n)
                                remoteOut.flush()
                            }
                        } catch (_: Exception) {}
                    }
                    val down = launch {
                        try {
                            val buf = ByteArray(TCP_BUFFER_SIZE)
                            while (isActive) {
                                val n = remoteIn.read(buf)
                                if (n < 0) break
                                ProxyStats.addBytesDown(n.toLong())
                                clientOutput.write(buf, 0, n)
                                clientOutput.flush()
                            }
                        } catch (_: Exception) {}
                    }
                    select(up, down)
                    up.cancel()
                    down.cancel()
                }
            } finally {
                ProxyStats.onConnectionClosed()
                try { remote.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$label] TCP fallback to $destIp:$destPort failed: ${e.message}")
        }
    }

    fun shutdown() {
        okHttpClient.dispatcher.cancelAll()
        okHttpClient.connectionPool.evictAll()
    }

    class WsRedirectException(val code: Int, val location: String?) :
        Exception("HTTP $code redirect → $location")
}

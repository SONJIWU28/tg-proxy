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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WsBridge(
    private val dcConfig: Map<Int, String>,
    private val proxyManager: ProxyManager? = null
) {
    companion object {
        private const val TAG = "WsBridge"
        private const val WS_CONNECT_TIMEOUT_MS = 10_000L
        private const val TCP_BUFFER_SIZE = 65536
    }

    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val dcFailUntil = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private val DC_FAIL_COOLDOWN_MS = 30_000L

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(WS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun handleConnection(
        clientSocket: Socket,
        destIp: String,
        destPort: Int
    ) {
        val label = "${clientSocket.remoteSocketAddress}"
        try {
            val inputStream = clientSocket.getInputStream()
            val outputStream = clientSocket.getOutputStream()

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

            var initInfo = MtProtoParser.extractDcFromInit(init)
            var initData = init
            var patched = false

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

            if (dcKey in wsBlacklist) {
                Log.d(TAG, "[$label] DC$dc$mediaTag WS blacklisted → TCP fallback")
                tcpFallback(inputStream, outputStream, destIp, destPort, initData, label, dc, isMedia)
                return
            }

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

            if (ws == null) {
                val bestProxy = proxyManager?.getBestProxy(dc)
                if (bestProxy != null) {
                    Log.i(TAG, "[$label] Using MTProto proxy: ${bestProxy.ip}:${bestProxy.port}")
                    connectViaMtProxy(inputStream, outputStream, bestProxy, initData, label)
                    return
                }

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

            dcFailUntil.remove(dcKey)
            ProxyStats.onConnectionOpened(isWs = true)

            val splitter = if (patched) {
                try { MsgSplitter(initData) } catch (e: Exception) { null }
            } else null

            ws.send(initData.toByteString())

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
                queue.put(null)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                queue.put(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                queue.put(null)
            }
        }

        val ws = client.newWebSocket(request, listener)
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

    private suspend fun connectViaMtProxy(
        clientInput: InputStream,
        clientOutput: OutputStream,
        proxy: ProxyServer,
        initData: ByteArray,
        label: String
    ) = withContext(Dispatchers.IO) {
        try {
            val remote = Socket()
            remote.connect(InetSocketAddress(proxy.ip, proxy.port), 10_000)
            ProxyStats.onConnectionOpened(isWs = false)

            try {
                val remoteOut = remote.getOutputStream()
                val remoteIn = remote.getInputStream()

                remoteOut.write(initData)
                remoteOut.flush()

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
            Log.w(TAG, "[$label] MTProto proxy ${proxy.ip}:${proxy.port} failed: ${e.message}")
        }
    }

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

        select(tcpToWs, wsToTcp)
        tcpToWs.cancel()
        wsToTcp.cancel()
    }

    private suspend fun select(a: Job, b: Job) {
        try {
            kotlinx.coroutines.selects.select {
                a.onJoin {}
                b.onJoin {}
            }
        } catch (_: CancellationException) {}
    }

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

                remoteOut.write(initData)
                remoteOut.flush()

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
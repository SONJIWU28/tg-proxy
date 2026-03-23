package com.tgwsproxy.proxy
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LocalProxyServer(
    private val port: Int = DEFAULT_PORT,
    private val dcConfig: Map<Int, String>,
    private val sessionTracker: SessionTracker,
    private val proxyManager: ProxyManager? = null
) {
    companion object {
        private const val TAG = "LocalProxyServer"
        const val DEFAULT_PORT = 1984
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val bridge = WsBridge(dcConfig, proxyManager)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverJob = scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                serverSocket = ss
                Log.i(TAG, "Local proxy server listening on 127.0.0.1:$port")

                while (isActive) {
                    val client = ss.accept()
                    launch {
                        handleClient(client)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val srcPort = (client.remoteSocketAddress as? InetSocketAddress)?.port ?: client.port
        try {
            client.tcpNoDelay = true
            client.soTimeout = 0

            val session = sessionTracker.getSession(srcPort)
            if (session == null) {
                Log.w(TAG, "No session found for port $srcPort")
                client.close()
                return
            }

            val destIp = session.destIp
            val destPort = session.destPort
            Log.d(TAG, "Connection from port $srcPort → $destIp:$destPort")

            bridge.handleConnection(client, destIp, destPort)
        } catch (e: Exception) {
            Log.e(TAG, "Handle client error: ${e.message}", e)
        } finally {
            sessionTracker.removeSession(srcPort)
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        scope.cancel()
        bridge.shutdown()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Local proxy server stopped")
    }
}

class SessionTracker {
    data class Session(val destIp: String, val destPort: Int)
    private val sessions = java.util.concurrent.ConcurrentHashMap<Int, Session>()

    fun addSession(localPort: Int, destIp: String, destPort: Int) {
        sessions[localPort] = Session(destIp, destPort)
    }

    fun getSession(localPort: Int): Session? = sessions[localPort]

    fun removeSession(localPort: Int) {
        sessions.remove(localPort)
    }

    fun clear() = sessions.clear()
}
package com.tgwsproxy.proxy
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProxyServer(
    val ip: String,
    val port: Int,
    val secret: String,
    val dc: Int,
    var latency: Long = Long.MAX_VALUE,
    var lastChecked: Long = 0,
    var isWorking: Boolean = false
)

class ProxyManager {
    companion object {
        private const val TAG = "ProxyManager"
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val MAX_LATENCY_MS = 3000L
    }

    private val proxies = ConcurrentHashMap<Int, MutableList<ProxyServer>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                checkAllProxies()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private val proxySources = listOf(
        "https://core.telegram.org/getProxyConfig",
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/main/proxies.json",
        "https://t.me/s/ProxyMTProto"
    )

    suspend fun fetchProxies() {
        for (source in proxySources) {
            try {
                val request = Request.Builder().url(source).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: continue

                when {
                    source.contains("telegram.org") -> parseTelegramConfig(body)
                    source.contains("github") -> parseJsonList(body)
                    source.contains("t.me") -> parseTelegramChannel(body)
                }
                Log.i(TAG, "Загружено прокси из $source")
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось загрузить из $source: ${e.message}")
            }
        }
    }

    private fun parseTelegramConfig(body: String) {
        body.lines().forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 4) {
                try {
                    val dc = parts[0].toInt()
                    val ip = parts[1]
                    val port = parts[2].toInt()
                    val secret = parts[3]
                    addProxy(ProxyServer(ip, port, secret, dc))
                } catch (_: Exception) {}
            }
        }
    }

    private fun parseJsonList(body: String) {
        try {
            val array = JSONArray(body)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val proxy = ProxyServer(
                    ip = obj.getString("ip"),
                    port = obj.getInt("port"),
                    secret = obj.getString("secret"),
                    dc = obj.optInt("dc", 2)
                )
                addProxy(proxy)
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}")
        }
    }

    private fun parseTelegramChannel(body: String) {
        val regex = Regex("tg://proxy\\?server=([^&]+)&port=(\\d+)&secret=([a-fA-F0-9]+)")
        regex.findAll(body).forEach { match ->
            try {
                val ip = match.groupValues[1]
                val port = match.groupValues[2].toInt()
                val secret = match.groupValues[3]
                addProxy(ProxyServer(ip, port, secret, dc = 2))
            } catch (_: Exception) {}
        }
    }

    private fun addProxy(proxy: ProxyServer) {
        val list = proxies.getOrPut(proxy.dc) { mutableListOf() }
        if (list.none { it.ip == proxy.ip && it.port == proxy.port }) {
            list.add(proxy)
            Log.d(TAG, "Добавлен прокси: ${proxy.ip}:${proxy.port} (DC${proxy.dc})")
        }
    }

    private suspend fun checkAllProxies() {
        proxies.values.flatten().forEach { proxy ->
            if (System.currentTimeMillis() - proxy.lastChecked > CHECK_INTERVAL_MS) {
                val isWorking = testProxy(proxy)
                proxy.isWorking = isWorking
                proxy.lastChecked = System.currentTimeMillis()
                Log.d(TAG, "Проверка ${proxy.ip}:${proxy.port} → ${if (isWorking) "OK" else "FAIL"} (${proxy.latency}ms)")
            }
        }
    }

    private suspend fun testProxy(proxy: ProxyServer): Boolean = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(proxy.ip, proxy.port), 3000)
            socket.soTimeout = 3000
            val handshake = ByteArray(64) { 0x00 }
            socket.getOutputStream().write(handshake)
            socket.getOutputStream().flush()
            val response = socket.getInputStream().read(ByteArray(1))
            socket.close()
            proxy.latency = System.currentTimeMillis() - start
            response >= 0 && proxy.latency < MAX_LATENCY_MS
        } catch (e: Exception) {
            proxy.latency = Long.MAX_VALUE
            false
        }
    }

    fun getBestProxy(dc: Int): ProxyServer? {
        val list = proxies[dc] ?: return null
        return list.filter { it.isWorking }.minByOrNull { it.latency }
    }

    fun getAllWorkingProxies(): List<ProxyServer> {
        return proxies.values.flatten().filter { it.isWorking }
    }

    fun getProxiesCount(): Int = proxies.values.flatten().size

    fun getWorkingProxiesCount(): Int = proxies.values.flatten().count { it.isWorking }

    fun shutdown() {
        scope.cancel()
        client.dispatcher.cancelAll()
    }
}
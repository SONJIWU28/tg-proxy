package com.tgwsproxy.proxy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ProxyStats {
    private val _connectionsTotal = AtomicInteger(0)
    private val _connectionsWs = AtomicInteger(0)
    private val _connectionsTcpFallback = AtomicInteger(0)
    private val _connectionsActive = AtomicInteger(0)
    private val _wsErrors = AtomicInteger(0)
    private val _bytesUp = AtomicLong(0)
    private val _bytesDown = AtomicLong(0)

    private val _statsLive = MutableLiveData<Snapshot>()
    val statsLive: LiveData<Snapshot> = _statsLive

    data class Snapshot(
        val connectionsTotal: Int,
        val connectionsWs: Int,
        val connectionsTcpFallback: Int,
        val connectionsActive: Int,
        val wsErrors: Int,
        val bytesUp: Long,
        val bytesDown: Long,
        val startTimeMs: Long
    ) {
        val bytesTotal: Long get() = bytesUp + bytesDown
    }

    private var startTimeMs: Long = 0L

    fun reset() {
        _connectionsTotal.set(0)
        _connectionsWs.set(0)
        _connectionsTcpFallback.set(0)
        _connectionsActive.set(0)
        _wsErrors.set(0)
        _bytesUp.set(0)
        _bytesDown.set(0)
        startTimeMs = System.currentTimeMillis()
        notifyChanged()
    }

    fun onConnectionOpened(isWs: Boolean) {
        _connectionsTotal.incrementAndGet()
        _connectionsActive.incrementAndGet()
        if (isWs) _connectionsWs.incrementAndGet()
        else _connectionsTcpFallback.incrementAndGet()
        notifyChanged()
    }

    fun onConnectionClosed() {
        _connectionsActive.decrementAndGet()
        notifyChanged()
    }

    fun onWsError() {
        _wsErrors.incrementAndGet()
        notifyChanged()
    }

    fun addBytesUp(n: Long) {
        _bytesUp.addAndGet(n)
    }

    fun addBytesDown(n: Long) {
        _bytesDown.addAndGet(n)
    }

    fun publishTrafficUpdate() {
        notifyChanged()
    }

    fun snapshot(): Snapshot = Snapshot(
        connectionsTotal = _connectionsTotal.get(),
        connectionsWs = _connectionsWs.get(),
        connectionsTcpFallback = _connectionsTcpFallback.get(),
        connectionsActive = _connectionsActive.get(),
        wsErrors = _wsErrors.get(),
        bytesUp = _bytesUp.get(),
        bytesDown = _bytesDown.get(),
        startTimeMs = startTimeMs
    )

    private fun notifyChanged() {
        _statsLive.postValue(snapshot())
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
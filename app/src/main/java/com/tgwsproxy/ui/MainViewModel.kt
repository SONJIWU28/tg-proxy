package com.tgwsproxy.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import com.tgwsproxy.proxy.ProxyStats
import com.tgwsproxy.vpn.TgVpnService

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val isRunning: LiveData<Boolean> = MutableLiveData(false)
    val stats: LiveData<ProxyStats.Snapshot> = ProxyStats.statsLive

    val uptimeText: LiveData<String> = stats.map { snapshot ->
        if (snapshot.startTimeMs == 0L) "—"
        else {
            val elapsed = (System.currentTimeMillis() - snapshot.startTimeMs) / 1000
            val hours = elapsed / 3600
            val minutes = (elapsed % 3600) / 60
            val seconds = elapsed % 60
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        }
    }

    val trafficUp: LiveData<String> = stats.map { ProxyStats.formatBytes(it.bytesUp) }
    val trafficDown: LiveData<String> = stats.map { ProxyStats.formatBytes(it.bytesDown) }
    val trafficTotal: LiveData<String> = stats.map { ProxyStats.formatBytes(it.bytesTotal) }

    val connectionsActive: LiveData<String> = stats.map { it.connectionsActive.toString() }
    val connectionsTotal: LiveData<String> = stats.map { it.connectionsTotal.toString() }
    val connectionsWs: LiveData<String> = stats.map { it.connectionsWs.toString() }
    val connectionsTcp: LiveData<String> = stats.map { it.connectionsTcpFallback.toString() }

    fun refreshRunningState() {
        (isRunning as MutableLiveData).value = TgVpnService.isRunning
    }

    fun toggleVpn() {
        val context = getApplication<Application>()
        if (TgVpnService.isRunning) {
            TgVpnService.stop(context)
        } else {
            TgVpnService.start(context)
        }
    }
}
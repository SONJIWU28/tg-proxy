package com.tgwsproxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tgwsproxy.R
import com.tgwsproxy.proxy.LocalProxyServer
import com.tgwsproxy.proxy.ProxyManager
import com.tgwsproxy.proxy.ProxyStats
import com.tgwsproxy.proxy.SessionTracker
import com.tgwsproxy.proxy.TelegramConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class TgVpnService : VpnService() {
    companion object {
        private const val TAG = "TgVpnService"
        private const val CHANNEL_ID = "tg_ws_proxy_vpn"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tgwsproxy.STOP"
        private const val VPN_ADDRESS = "10.255.255.1"
        private const val VPN_ROUTE_PREFIX = 32
        const val LOCAL_PROXY_PORT = 1984

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TgVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TgVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var localProxy: LocalProxyServer? = null
    private var proxyManager: ProxyManager? = null
    private val sessionTracker = SessionTracker()
    private var packetLoopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsUpdateJob: Job? = null

    private val dcConfig: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))
        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            ProxyStats.reset()

            proxyManager = ProxyManager()
            scope.launch {
                proxyManager?.fetchProxies()
            }

            localProxy = LocalProxyServer(
                port = LOCAL_PROXY_PORT,
                dcConfig = dcConfig,
                sessionTracker = sessionTracker,
                proxyManager = proxyManager
            )
            localProxy?.start()

            val builder = Builder()
                .setSession("TG WS Proxy")
                .addAddress(VPN_ADDRESS, VPN_ROUTE_PREFIX)
                .setMtu(1500)

            for ((route, prefix) in TelegramConstants.getAllRoutes()) {
                builder.addRoute(route, prefix)
            }

            builder.addDisallowedApplication(packageName)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            startPacketLoop()

            statsUpdateJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    ProxyStats.publishTrafficUpdate()
                    updateNotification()
                }
            }

            isRunning = true
            updateNotification()
            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        packetLoopJob?.cancel()
        packetLoopJob = null
        statsUpdateJob?.cancel()
        statsUpdateJob = null
        proxyManager?.shutdown()
        proxyManager = null
        localProxy?.stop()
        localProxy = null
        sessionTracker.clear()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        Log.i(TAG, "VPN stopped")
    }

    private fun startPacketLoop() {
        packetLoopJob = scope.launch {
            val tunFd = vpnInterface ?: return@launch
            val input = FileInputStream(tunFd.fileDescriptor)
            val output = FileOutputStream(tunFd.fileDescriptor)
            val packetBuffer = ByteBuffer.allocate(32767)

            try {
                while (isActive) {
                    packetBuffer.clear()
                    val length = input.read(packetBuffer.array())
                    if (length <= 0) {
                        delay(10)
                        continue
                    }
                    packetBuffer.limit(length)
                    processPacket(packetBuffer, output)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Packet loop error: ${e.message}", e)
            } finally {
                try {
                    input.close()
                } catch (_: Exception) {
                }
                try {
                    output.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun processPacket(packet: ByteBuffer, tunOutput: FileOutputStream) {
        if (packet.limit() < 20) return

        val version = (packet.get(0).toInt() and 0xF0) shr 4
        if (version != 4) return

        val ihl = (packet.get(0).toInt() and 0x0F) * 4
        if (packet.limit() < ihl) return

        val protocol = packet.get(9).toInt() and 0xFF
        if (protocol != 6) return

        val destIpBytes = ByteArray(4)
        packet.position(16)
        packet.get(destIpBytes)

        if (!TelegramConstants.isTelegramIp(destIpBytes)) return

        val destIp = InetAddress.getByAddress(destIpBytes).hostAddress ?: return
        val destPort = ((packet.get(ihl + 2).toInt() and 0xFF) shl 8) or
            (packet.get(ihl + 3).toInt() and 0xFF)
        val srcPort = ((packet.get(ihl).toInt() and 0xFF) shl 8) or
            (packet.get(ihl + 1).toInt() and 0xFF)

        val tcpFlags = packet.get(ihl + 13).toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0 && (tcpFlags and 0x10) == 0

        if (isSyn) {
            sessionTracker.addSession(srcPort, destIp, destPort)
            Log.d(TAG, "SYN tracked: port $srcPort → $destIp:$destPort")
        }

        val localIp = InetAddress.getByName("127.0.0.1").address
        packet.position(16)
        packet.put(localIp)
        packet.put(ihl + 2, ((LOCAL_PROXY_PORT shr 8) and 0xFF).toByte())
        packet.put(ihl + 3, (LOCAL_PROXY_PORT and 0xFF).toByte())

        recalculateIpChecksum(packet, ihl)
        recalculateTcpChecksum(packet, ihl)

        packet.position(0)
        tunOutput.write(packet.array(), 0, packet.limit())
    }

    private fun recalculateIpChecksum(packet: ByteBuffer, ihl: Int) {
        packet.put(10, 0)
        packet.put(11, 0)

        var sum = 0L
        for (i in 0 until ihl step 2) {
            val word = ((packet.get(i).toInt() and 0xFF) shl 8) or
                (packet.get(i + 1).toInt() and 0xFF)
            sum += word.toLong()
        }

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.toInt().inv() and 0xFFFF
        packet.put(10, (checksum shr 8).toByte())
        packet.put(11, (checksum and 0xFF).toByte())
    }

    private fun recalculateTcpChecksum(packet: ByteBuffer, ihl: Int) {
        val totalLength = ((packet.get(2).toInt() and 0xFF) shl 8) or
            (packet.get(3).toInt() and 0xFF)
        val tcpLength = totalLength - ihl

        packet.put(ihl + 16, 0)
        packet.put(ihl + 17, 0)

        var sum = 0L
        for (i in 12..19 step 2) {
            val word = ((packet.get(i).toInt() and 0xFF) shl 8) or
                (packet.get(i + 1).toInt() and 0xFF)
            sum += word.toLong()
        }

        sum += 6L
        sum += tcpLength.toLong()

        for (i in 0 until tcpLength step 2) {
            val b1 = packet.get(ihl + i).toInt() and 0xFF
            val b2 = if (i + 1 < tcpLength) packet.get(ihl + i + 1).toInt() and 0xFF else 0
            sum += ((b1 shl 8) or b2).toLong()
        }

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.toInt().inv() and 0xFFFF
        packet.put(ihl + 16, (checksum shr 8).toByte())
        packet.put(ihl + 17, (checksum and 0xFF).toByte())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG WS Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN service status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()

        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TgVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG WS Proxy")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_stop, "Остановить", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (!isRunning) return

        val stats = ProxyStats.snapshot()
        val proxyCount = proxyManager?.getWorkingProxiesCount() ?: 0
        val status = "▲ ${ProxyStats.formatBytes(stats.bytesUp)} ▼ ${ProxyStats.formatBytes(stats.bytesDown)} | ${stats.connectionsActive} акт. | ${proxyCount} прокси"

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}

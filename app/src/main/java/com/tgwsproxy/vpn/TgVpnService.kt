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
import com.tgwsproxy.proxy.*
import com.tgwsproxy.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Android VPN Service that intercepts Telegram traffic and routes it
 * through WebSocket connections.
 *
 * How it works:
 * 1. Creates a TUN interface that captures only Telegram IP ranges
 * 2. Reads raw IP packets from the TUN
 * 3. For TCP SYN packets to Telegram IPs: records the session and rewrites
 *    the destination to 127.0.0.1:LOCAL_PORT (NAT)
 * 4. A local proxy server (LocalProxyServer) accepts the redirected connection
 * 5. WsBridge handles the MTProto→WSS conversion
 * 6. Responses flow back through the TUN
 *
 * Alternatively (simpler approach used here):
 * We route only Telegram IP ranges through the VPN, and the VPN simply
 * forwards packets. The trick is using protect() on our own sockets to
 * prevent routing loops.
 */
class TgVpnService : VpnService() {

    companion object {
        private const val TAG = "TgVpnService"
        private const val CHANNEL_ID = "tg_ws_proxy_vpn"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.tgwsproxy.STOP"

        // VPN TUN address (private, not routable)
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
            val intent = Intent(context, TgVpnService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var localProxy: LocalProxyServer? = null
    private val sessionTracker = SessionTracker()
    private var packetLoopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsUpdateJob: Job? = null

    // Default DC config
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
        // Called when user revokes VPN permission
        stopVpn()
        stopSelf()
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            ProxyStats.reset()

            // Start local proxy server
            localProxy = LocalProxyServer(
                port = LOCAL_PROXY_PORT,
                dcConfig = dcConfig,
                sessionTracker = sessionTracker
            )
            localProxy?.start()

            // Build VPN interface
            val builder = Builder()
                .setSession("TG WS Proxy")
                .addAddress(VPN_ADDRESS, VPN_ROUTE_PREFIX)
                .setMtu(1500)

            // Only route Telegram IP ranges through the VPN
            for ((route, prefix) in TelegramConstants.getAllRoutes()) {
                builder.addRoute(route, prefix)
            }

            // Exclude ourselves to prevent routing loops
            // Also exclude the local proxy server
            builder.addDisallowedApplication(packageName)

            // Set DNS to prevent leaks (use Google DNS)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("8.8.4.4")

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            // Start packet processing loop
            startPacketLoop()

            // Start periodic stats updates
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
        statsUpdateJob?.cancel()
        localProxy?.stop()
        sessionTracker.clear()

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        updateNotification()
        Log.i(TAG, "VPN stopped")
    }

    /**
     * Main packet processing loop.
     *
     * Reads IP packets from the TUN, identifies Telegram TCP traffic,
     * and redirects it to our local proxy server.
     *
     * This is a simplified approach: instead of full userspace TCP/IP stack,
     * we use the VPN only for routing. Android's TCP stack handles the actual
     * connections — the VPN just ensures Telegram IPs go through our tunnel.
     *
     * The actual proxying happens because:
     * 1. Telegram app connects to DC IP (e.g., 149.154.167.220:443)
     * 2. VPN route captures this traffic
     * 3. We read the IP packet, note the destination
     * 4. We rewrite the destination to 127.0.0.1:LOCAL_PROXY_PORT
     * 5. LocalProxyServer accepts and bridges via WS
     * 6. Responses are sent back through the TUN
     */
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
                        delay(10) // prevent busy loop
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
                try { input.close() } catch (_: Exception) {}
                try { output.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Process a single IP packet from the TUN interface.
     *
     * For TCP packets destined to Telegram IPs, we perform a simple NAT:
     * rewrite the destination IP to 127.0.0.1 and the port to LOCAL_PROXY_PORT.
     * This way, Android's TCP stack connects to our local proxy instead of
     * the remote Telegram server directly.
     *
     * Note: This is a simplified implementation. A production version would
     * need proper TCP checksum recalculation, connection tracking, and
     * reverse NAT for response packets. For a full implementation, consider
     * using tun2socks (e.g., badvpn-tun2socks or go-tun2socks).
     */
    private fun processPacket(packet: ByteBuffer, tunOutput: FileOutputStream) {
        if (packet.limit() < 20) return // too small for IP header

        val version = (packet.get(0).toInt() and 0xF0) shr 4
        if (version != 4) return // IPv4 only

        val ihl = (packet.get(0).toInt() and 0x0F) * 4
        if (packet.limit() < ihl) return

        val protocol = packet.get(9).toInt() and 0xFF
        if (protocol != 6) return // TCP only

        // Extract destination IP
        val destIpBytes = ByteArray(4)
        packet.position(16)
        packet.get(destIpBytes)

        // Check if it's a Telegram IP
        if (!TelegramConstants.isTelegramIp(destIpBytes)) return

        val destIp = InetAddress.getByAddress(destIpBytes).hostAddress ?: return
        val destPort = ((packet.get(ihl + 2).toInt() and 0xFF) shl 8) or
                       (packet.get(ihl + 3).toInt() and 0xFF)
        val srcPort = ((packet.get(ihl).toInt() and 0xFF) shl 8) or
                      (packet.get(ihl + 1).toInt() and 0xFF)

        // Check if it's a SYN packet (connection start)
        val tcpFlags = packet.get(ihl + 13).toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0 && (tcpFlags and 0x10) == 0

        if (isSyn) {
            // Record the session: srcPort → destIp:destPort
            sessionTracker.addSession(srcPort, destIp, destPort)
            Log.d(TAG, "SYN tracked: port $srcPort → $destIp:$destPort")
        }

        // Rewrite destination to local proxy
        val localIp = InetAddress.getByName("127.0.0.1").address
        packet.position(16)
        packet.put(localIp)

        // Rewrite destination port
        packet.put(ihl + 2, ((LOCAL_PROXY_PORT shr 8) and 0xFF).toByte())
        packet.put(ihl + 3, (LOCAL_PROXY_PORT and 0xFF).toByte())

        // Recalculate IP header checksum
        recalculateIpChecksum(packet, ihl)

        // Recalculate TCP checksum
        recalculateTcpChecksum(packet, ihl)

        // Write the modified packet back to TUN
        packet.position(0)
        tunOutput.write(packet.array(), 0, packet.limit())
    }

    /**
     * Recalculate IPv4 header checksum.
     */
    private fun recalculateIpChecksum(packet: ByteBuffer, ihl: Int) {
        // Zero out existing checksum
        packet.put(10, 0)
        packet.put(11, 0)

        var sum = 0L
        for (i in 0 until ihl step 2) {
            val word = ((packet.get(i).toInt() and 0xFF) shl 8) or
                       (packet.get(i + 1).toInt() and 0xFF)
            sum += word.toLong()
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.toInt().inv() and 0xFFFF
        packet.put(10, (checksum shr 8).toByte())
        packet.put(11, (checksum and 0xFF).toByte())
    }

    /**
     * Recalculate TCP checksum (including pseudo-header).
     */
    private fun recalculateTcpChecksum(packet: ByteBuffer, ihl: Int) {
        val totalLength = ((packet.get(2).toInt() and 0xFF) shl 8) or
                          (packet.get(3).toInt() and 0xFF)
        val tcpLength = totalLength - ihl

        // Zero out existing TCP checksum
        packet.put(ihl + 16, 0)
        packet.put(ihl + 17, 0)

        var sum = 0L

        // Pseudo-header: src IP + dst IP + zero + protocol + TCP length
        for (i in 12..19 step 2) {
            val word = ((packet.get(i).toInt() and 0xFF) shl 8) or
                       (packet.get(i + 1).toInt() and 0xFF)
            sum += word.toLong()
        }
        sum += 6L // TCP protocol number
        sum += tcpLength.toLong()

        // TCP header + data
        for (i in 0 until tcpLength step 2) {
            val b1 = packet.get(ihl + i).toInt() and 0xFF
            val b2 = if (i + 1 < tcpLength) packet.get(ihl + i + 1).toInt() and 0xFF else 0
            sum += ((b1 shl 8) or b2).toLong()
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.toInt().inv() and 0xFFFF
        packet.put(ihl + 16, (checksum shr 8).toByte())
        packet.put(ihl + 17, (checksum and 0xFF).toByte())
    }

    // ── Notification ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG WS Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Telegram WebSocket proxy status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TgVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
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
        val status = if (isRunning) {
            "▲ ${ProxyStats.formatBytes(stats.bytesUp)} " +
            "▼ ${ProxyStats.formatBytes(stats.bytesDown)} " +
            "| ${stats.connectionsActive} акт."
        } else {
            "Остановлен"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}

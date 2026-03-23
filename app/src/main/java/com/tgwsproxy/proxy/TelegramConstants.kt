package com.tgwsproxy.proxy

import java.net.InetAddress

/**
 * Telegram network constants: DC IP ranges, IP→DC mapping, WS domain generation.
 * Ported from tg-ws-proxy Python codebase (Flowseal).
 */
object TelegramConstants {

    /** CIDR ranges for all Telegram server IPs */
    data class IpRange(val start: Long, val end: Long)

    val TG_RANGES: List<IpRange> = listOf(
        // 185.76.151.0/24
        IpRange(ipToLong("185.76.151.0"), ipToLong("185.76.151.255")),
        // 149.154.160.0/20
        IpRange(ipToLong("149.154.160.0"), ipToLong("149.154.175.255")),
        // 91.105.192.0/23
        IpRange(ipToLong("91.105.192.0"), ipToLong("91.105.193.255")),
        // 91.108.0.0/16
        IpRange(ipToLong("91.108.0.0"), ipToLong("91.108.255.255"))
    )

    /** Known Telegram IPs mapped to (dc_id, is_media) */
    data class DcInfo(val dc: Int, val isMedia: Boolean)

    val IP_TO_DC: Map<String, DcInfo> = mapOf(
        // DC1
        "149.154.175.50" to DcInfo(1, false),
        "149.154.175.51" to DcInfo(1, false),
        "149.154.175.53" to DcInfo(1, false),
        "149.154.175.54" to DcInfo(1, false),
        "149.154.175.52" to DcInfo(1, true),
        // DC2
        "149.154.167.41" to DcInfo(2, false),
        "149.154.167.50" to DcInfo(2, false),
        "149.154.167.51" to DcInfo(2, false),
        "149.154.167.220" to DcInfo(2, false),
        "95.161.76.100" to DcInfo(2, false),
        "149.154.167.151" to DcInfo(2, true),
        "149.154.167.222" to DcInfo(2, true),
        "149.154.167.223" to DcInfo(2, true),
        "149.154.162.123" to DcInfo(2, true),
        // DC3
        "149.154.175.100" to DcInfo(3, false),
        "149.154.175.101" to DcInfo(3, false),
        "149.154.175.102" to DcInfo(3, true),
        // DC4
        "149.154.167.91" to DcInfo(4, false),
        "149.154.167.92" to DcInfo(4, false),
        "149.154.164.250" to DcInfo(4, true),
        "149.154.166.120" to DcInfo(4, true),
        "149.154.166.121" to DcInfo(4, true),
        "149.154.167.118" to DcInfo(4, true),
        "149.154.165.111" to DcInfo(4, true),
        // DC5
        "91.108.56.100" to DcInfo(5, false),
        "91.108.56.101" to DcInfo(5, false),
        "91.108.56.116" to DcInfo(5, false),
        "91.108.56.126" to DcInfo(5, false),
        "149.154.171.5" to DcInfo(5, false),
        "91.108.56.102" to DcInfo(5, true),
        "91.108.56.128" to DcInfo(5, true),
        "91.108.56.151" to DcInfo(5, true),
        // DC203
        "91.105.192.100" to DcInfo(203, false)
    )

    /** DC overrides (e.g. DC203 routes through DC2 infrastructure) */
    val DC_OVERRIDES: Map<Int, Int> = mapOf(
        203 to 2
    )

    /** Valid MTProto obfuscated transport protocol IDs */
    val VALID_PROTOS: Set<Long> = setOf(
        0xEFEFEFEFL, // abridged
        0xEEEEEEEEL, // intermediate
        0xDDDDDDDDL  // padded intermediate
    )

    /**
     * Check if the given IP belongs to a Telegram DC.
     */
    fun isTelegramIp(ip: String): Boolean {
        return try {
            val n = ipToLong(ip)
            TG_RANGES.any { n in it.start..it.end }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a raw 4-byte IPv4 address belongs to Telegram.
     */
    fun isTelegramIp(rawIp: ByteArray): Boolean {
        if (rawIp.size != 4) return false
        val n = ((rawIp[0].toLong() and 0xFF) shl 24) or
                ((rawIp[1].toLong() and 0xFF) shl 16) or
                ((rawIp[2].toLong() and 0xFF) shl 8) or
                (rawIp[3].toLong() and 0xFF)
        return TG_RANGES.any { n in it.start..it.end }
    }

    /**
     * Get WebSocket domains for a given DC and media flag.
     * Media connections try the -1 suffix first.
     */
    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val effectiveDc = DC_OVERRIDES.getOrDefault(dc, dc)
        return if (isMedia) {
            listOf("kws${effectiveDc}-1.web.telegram.org", "kws${effectiveDc}.web.telegram.org")
        } else {
            listOf("kws${effectiveDc}.web.telegram.org", "kws${effectiveDc}-1.web.telegram.org")
        }
    }

    /**
     * Get all Telegram IP addresses (for VPN routing table).
     * Returns CIDR strings suitable for VpnService.Builder.addRoute().
     */
    fun getAllRoutes(): List<Pair<String, Int>> = listOf(
        "185.76.151.0" to 24,
        "149.154.160.0" to 20,
        "91.105.192.0" to 23,
        "91.108.0.0" to 16
    )

    private fun ipToLong(ip: String): Long {
        val bytes = InetAddress.getByName(ip).address
        return ((bytes[0].toLong() and 0xFF) shl 24) or
               ((bytes[1].toLong() and 0xFF) shl 16) or
               ((bytes[2].toLong() and 0xFF) shl 8) or
               (bytes[3].toLong() and 0xFF)
    }
}

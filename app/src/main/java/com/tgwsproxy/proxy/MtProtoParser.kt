package com.tgwsproxy.proxy

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MTProto obfuscated transport init packet parser.
 *
 * The Telegram client sends a 64-byte init packet when establishing a new
 * connection. This packet contains an AES-256-CTR encrypted header with:
 *   - bytes [8..40)  → AES key
 *   - bytes [40..56) → AES CTR IV (nonce)
 *   - bytes [56..64) → encrypted payload containing (proto_id, dc_id)
 *
 * We decrypt the last 8 bytes to extract the DC ID, which tells us
 * which WebSocket endpoint to connect to.
 */
object MtProtoParser {

    private const val TAG = "MtProtoParser"
    private val ZERO_64 = ByteArray(64)

    data class InitInfo(
        val dc: Int,
        val isMedia: Boolean
    )

    /**
     * Extract DC ID and media flag from a 64-byte MTProto obfuscation init packet.
     * Returns null if the packet is invalid or cannot be parsed.
     */
    fun extractDcFromInit(data: ByteArray): InitInfo? {
        if (data.size < 64) return null

        return try {
            // Extract AES key and IV from the init packet
            val aesKey = data.copyOfRange(8, 40)     // 32 bytes
            val aesIv = data.copyOfRange(40, 56)      // 16 bytes

            // Create AES-CTR cipher and generate keystream from zeros
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(aesIv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val keystream = cipher.update(ZERO_64)

            // XOR encrypted bytes [56..64) with keystream to get plaintext
            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }

            // Parse plaintext: proto_id (uint32 LE) + dc_raw (int16 LE)
            val buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            val protoId = buf.getInt(0).toLong() and 0xFFFFFFFFL
            val dcRaw = buf.getShort(4).toInt()

            Log.d(TAG, "dc_from_init: proto=0x${protoId.toString(16)} dc_raw=$dcRaw")

            if (protoId !in TelegramConstants.VALID_PROTOS) {
                Log.d(TAG, "Invalid protocol ID: 0x${protoId.toString(16)}")
                return null
            }

            val dc = kotlin.math.abs(dcRaw)
            if (dc !in 1..5 && dc != 203) {
                Log.d(TAG, "DC out of range: $dc")
                return null
            }

            InitInfo(dc = dc, isMedia = dcRaw < 0)
        } catch (e: Exception) {
            Log.e(TAG, "DC extraction failed", e)
            null
        }
    }

    /**
     * Patch dc_id in the 64-byte MTProto init packet.
     *
     * Mobile clients with useSecret=0 may leave bytes 60-61 as random.
     * The WS relay needs a valid dc_id to route correctly, so we patch it.
     */
    fun patchInitDc(data: ByteArray, dc: Int, isMedia: Boolean): ByteArray {
        if (data.size < 64) return data

        return try {
            val signedDc: Short = if (isMedia) (-dc).toShort() else dc.toShort()
            val newDcBytes = ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(signedDc)
                .array()

            val aesKey = data.copyOfRange(8, 40)
            val aesIv = data.copyOfRange(40, 56)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val keystream = cipher.update(ZERO_64)

            val patched = data.copyOf()
            patched[60] = (keystream[60].toInt() xor newDcBytes[0].toInt()).toByte()
            patched[61] = (keystream[61].toInt() xor newDcBytes[1].toInt()).toByte()

            Log.d(TAG, "init patched: dc_id -> $dc (media=$isMedia)")
            patched
        } catch (e: Exception) {
            Log.e(TAG, "Patch failed", e)
            data
        }
    }
}

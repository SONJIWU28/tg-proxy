package com.tgwsproxy.proxy
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MtProtoParser {
    private const val TAG = "MtProtoParser"
    private val ZERO_64 = ByteArray(64)

    data class InitInfo(val dc: Int, val isMedia: Boolean)

    fun extractDcFromInit(data: ByteArray): InitInfo? {
        if (data.size < 64) return null
        return try {
            val aesKey = data.copyOfRange(8, 40)
            val aesIv = data.copyOfRange(40, 56)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(aesIv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val keystream = cipher.update(ZERO_64)

            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }

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
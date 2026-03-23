package com.tgwsproxy.proxy

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Splits client TCP data into individual MTProto abridged-protocol messages
 * so each can be sent as a separate WebSocket frame.
 *
 * The Telegram WS relay processes one MTProto message per WS frame.
 * Mobile clients may batch multiple messages in a single TCP write
 * (e.g. msgs_ack + req_DH_params). If sent as one WS frame, the relay
 * only processes the first — DH handshake never completes.
 *
 * This class maintains a parallel AES-CTR decryptor to find message
 * boundaries in the encrypted stream, then splits the original ciphertext.
 */
class MsgSplitter(initData: ByteArray) {

    companion object {
        private const val TAG = "MsgSplitter"
        private val ZERO_64 = ByteArray(64)
    }

    private val decryptor: Cipher

    init {
        require(initData.size >= 64) { "Init data must be at least 64 bytes" }

        val aesKey = initData.copyOfRange(8, 40)
        val aesIv = initData.copyOfRange(40, 56)

        decryptor = Cipher.getInstance("AES/CTR/NoPadding")
        decryptor.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            IvParameterSpec(aesIv)
        )
        // Skip init packet (advance CTR counter by 64 bytes)
        decryptor.update(ZERO_64)
    }

    /**
     * Decrypt to find message boundaries, return split ciphertext chunks.
     * Each chunk corresponds to one MTProto message that should be sent
     * as a separate WebSocket binary frame.
     */
    fun split(chunk: ByteArray): List<ByteArray> {
        val plain = decryptor.update(chunk)
        if (plain == null || plain.isEmpty()) return listOf(chunk)

        val boundaries = mutableListOf<Int>()
        var pos = 0

        while (pos < plain.size) {
            val first = plain[pos].toInt() and 0xFF

            val msgLen: Int
            if (first == 0x7F) {
                // Extended length: next 3 bytes as little-endian uint24, * 4
                if (pos + 4 > plain.size) break
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                lenBuf.put(plain[pos + 1])
                lenBuf.put(plain[pos + 2])
                lenBuf.put(plain[pos + 3])
                lenBuf.put(0.toByte())
                lenBuf.flip()
                msgLen = (lenBuf.getInt() and 0x00FFFFFF) * 4
                pos += 4
            } else {
                msgLen = first * 4
                pos += 1
            }

            if (msgLen == 0 || pos + msgLen > plain.size) break
            pos += msgLen
            boundaries.add(pos)
        }

        if (boundaries.size <= 1) return listOf(chunk)

        val parts = mutableListOf<ByteArray>()
        var prev = 0
        for (boundary in boundaries) {
            parts.add(chunk.copyOfRange(prev, boundary))
            prev = boundary
        }
        if (prev < chunk.size) {
            parts.add(chunk.copyOfRange(prev, chunk.size))
        }

        Log.d(TAG, "Split TCP chunk (${chunk.size} bytes) into ${parts.size} messages")
        return parts
    }
}

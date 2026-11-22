package com.zhibai.app.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * mm- key wrap (mm.transmute.v1) - an OUTER layer around a real sk-/tp- key.
 *
 * The stored key stays plaintext; the mm- form is what travels in and out of
 * the clipboard. The protocol is byte-identical with tools/mm_encode.ps1:
 *
 *   blob = 0x01 || (utf8(key) XOR keystream) || HMAC-SHA256(mackey, ct)[0..8]
 *   seed     = SHA256("mm.transmute.v1|seed|ZhiBai.keywrap.2026")
 *   mackey   = SHA256(seed || "mm.transmute.v1|mac|ZhiBai.keywrap.2026")
 *   keystream block i = SHA256(seed || BE32(i))
 *   body     = base64 with the REVERSED alphabet below, '=' padding
 *   output   = "mm-" + body
 */
object MmKeyCodec {

    private const val ALPHABET = "+/9876543210zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA"
    private val SEED_SALT = "mm.transmute.v1|seed|ZhiBai.keywrap.2026".toByteArray(Charsets.UTF_8)
    private val MAC_SALT = "mm.transmute.v1|mac|ZhiBai.keywrap.2026".toByteArray(Charsets.UTF_8)
    private val KEY_SHAPE = Regex("^(sk-|tp-)[A-Za-z0-9._\\-]+$")

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun seed(): ByteArray = sha256(SEED_SALT)

    private fun macKey(): ByteArray = sha256(seed() + MAC_SALT)

    private fun keystream(seed: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var filled = 0
        var idx = 0
        while (filled < len) {
            val be = byteArrayOf(
                (idx ushr 24).toByte(), (idx ushr 16).toByte(),
                (idx ushr 8).toByte(), idx.toByte()
            )
            val block = sha256(seed + be)
            val n = minOf(block.size, len - filled)
            System.arraycopy(block, 0, out, filled, n)
            filled += n
            idx++
        }
        return out
    }

    private fun hmac8(ct: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey(), "HmacSHA256"))
            doFinal(ct)
        }.copyOfRange(0, 8)

    /** real key -> mm- blob (always available; copy-out uses this). */
    fun encode(key: String): String {
        val plain = key.trim().toByteArray(Charsets.UTF_8)
        val ks = keystream(seed(), plain.size)
        val ct = ByteArray(plain.size) { (plain[it].toInt() xor ks[it].toInt()).toByte() }
        val blob = byteArrayOf(0x01) + ct + hmac8(ct)
        return "mm-" + b64Encode(blob)
    }

    /** mm- blob -> real key, or null when the wrap is broken or not a key. */
    fun decode(mm: String): String? {
        val body = mm.trim().removePrefix("mm-")
        val blob = b64Decode(body) ?: return null
        if (blob.size < 1 + 8 + 3 || blob[0] != 0x01.toByte()) return null
        val ct = blob.copyOfRange(1, blob.size - 8)
        val mac = blob.copyOfRange(blob.size - 8, blob.size)
        if (!mac.contentEquals(hmac8(ct))) return null
        val ks = keystream(seed(), ct.size)
        val plain = ByteArray(ct.size) { (ct[it].toInt() xor ks[it].toInt()).toByte() }
        val key = String(plain, Charsets.UTF_8)
        return if (KEY_SHAPE.matches(key)) key else null
    }

    private fun b64Encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val have1 = i + 1 < data.size
            val have2 = i + 2 < data.size
            val b1 = if (have1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (have2) data[i + 2].toInt() and 0xFF else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (have2) {
                sb.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                sb.append(ALPHABET[b2 and 0x3F])
            } else if (have1) {
                sb.append(ALPHABET[(b1 and 0x0F) shl 2])
                sb.append('=')
            } else {
                sb.append('=')
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }

    private fun b64Decode(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 4 != 0) return null
        val inv = IntArray(128) { -1 }
        for (i in ALPHABET.indices) inv[ALPHABET[i].code] = i
        val out = ByteArray(s.length / 4 * 3)
        var o = 0
        var i = 0
        while (i < s.length) {
            val c0 = s[i]; val c1 = s[i + 1]; val c2 = s[i + 2]; val c3 = s[i + 3]
            if (c0 == '=' || c1 == '=') return null
            val v0 = if (c0.code < 128) inv[c0.code] else -1
            val v1 = if (c1.code < 128) inv[c1.code] else -1
            val v2 = if (c2.code < 128) inv[c2.code] else -1
            val v3 = if (c3.code < 128) inv[c3.code] else -1
            if (v0 < 0 || v1 < 0) return null
            out[o++] = ((v0 shl 2) or (v1 shr 4)).toByte()
            if (c2 != '=') {
                if (v2 < 0) return null
                out[o++] = (((v1 and 0x0F) shl 4) or (v2 shr 2)).toByte()
                if (c3 != '=') {
                    if (v3 < 0) return null
                    out[o++] = (((v2 and 0x03) shl 6) or v3).toByte()
                }
            } else if (c3 != '=') return null
            i += 4
        }
        return out.copyOf(o)
    }
}

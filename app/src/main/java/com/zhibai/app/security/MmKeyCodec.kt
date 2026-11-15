package com.zhibai.app.security

/**
 * mm- key wrap — converts the owner-issued `mm-...` blob back into the real
 * API key at runtime.
 *
 * Encoding exists ONLY in the owner's offline tool (tools/mm_encode.ps1);
 * testers of the APK receive mm- values, paste them like any key, and the app
 * unwraps them silently — the real sk-/tp- key never appears in the UI, in
 * DataStore, or in logs.
 *
 * The unwrap core (mm.transmute.v1 protocol) lives in native code inside
 * libadb.so (cpp/mm_codec.cpp): decompiling the APK yields only this
 * one-line external call, no readable implementation. Requires the mm- body
 * to HMAC-verify and to decode into a printable sk-/tp- string, so random
 * garbage is rejected (resolve returns "" → callers must treat the key as
 * unconfigured — see [isBroken]).
 */
object MmKeyCodec {
    private const val TAG = "MmKeyCodec"

    private val libLoaded = try {
        System.loadLibrary("adb")
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * raw key → real key: mm- blobs are unwrapped, anything else passes through.
     *
     * The encoder trims its input, so a pasted value with a stray newline must be
     * trimmed here too — otherwise base64 decode fails and a valid key collapses
     * to "" with no visible reason.
     */
    fun resolve(raw: String): String {
        val v = raw.trim()
        if (!v.startsWith("mm-")) return v
        if (!libLoaded) {
            android.util.Log.e(TAG, "libadb.so not loaded — cannot unwrap mm- key")
            return ""
        }
        return try {
            val out = nativeUnwrap(v)
            if (out.isNullOrEmpty()) {
                android.util.Log.e(TAG, "mm- unwrap failed (HMAC mismatch or corrupt body)")
            }
            out ?: ""
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "mm- unwrap threw: $t")
            ""
        }
    }

    /** True when [raw] is an mm- blob that failed to unwrap (key unusable). */
    fun isBroken(raw: String): Boolean {
        val v = raw.trim()
        return v.startsWith("mm-") && resolve(v).isEmpty()
    }

    private external fun nativeUnwrap(mm: String): String?
}

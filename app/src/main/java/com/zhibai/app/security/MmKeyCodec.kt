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
 * garbage is rejected (resolve returns "" → the field counts as unconfigured).
 */
object MmKeyCodec {
    private val libLoaded = try {
        System.loadLibrary("adb")
        true
    } catch (_: Throwable) {
        false
    }

    /** raw key → real key: mm- blobs are unwrapped, anything else passes through. */
    fun resolve(raw: String): String {
        if (!raw.startsWith("mm-")) return raw
        if (!libLoaded) return ""
        return try {
            nativeUnwrap(raw) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private external fun nativeUnwrap(mm: String): String?
}

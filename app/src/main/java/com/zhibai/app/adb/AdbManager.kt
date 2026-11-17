// ZhiBai wireless-debug ADB manager. Protocol stack copied from Shizuku (GPLv3).
package com.zhibai.app.adb

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

object AdbManager {

    private const val TAG = "AdbManager"
    private const val PREFS = "wireless_adb"
    private const val KEY_LAST_PORT = "last_connect_port"
    private const val HOST = "127.0.0.1"

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private lateinit var adbKey: AdbKey

    private val activeMdns = AtomicReference<AdbMdns?>(null)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            adbKey = AdbKey(PreferenceAdbKeyStore(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), "zhibai")
            initialized = true
        }
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastConnectPort(): Int = prefs.getInt(KEY_LAST_PORT, -1)

    private fun saveConnectPort(port: Int) {
        prefs.edit().putInt(KEY_LAST_PORT, port).apply()
    }

    /** mDNS 发现配对服务端口，超时返回 null。 */
    suspend fun discoverPairingPort(timeoutMs: Long = 15000): Int? = discoverPort(AdbMdns.TLS_PAIRING, timeoutMs)

    /** mDNS 发现连接服务端口，超时回退到上次保存的端口。 */
    suspend fun discoverConnectPort(timeoutMs: Long = 10000): Int? {
        return discoverPort(AdbMdns.TLS_CONNECT, timeoutMs) ?: lastConnectPort().takeIf { it > 0 }
    }

    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var mdns: AdbMdns? = null
                mdns = AdbMdns(appContext, serviceType) { port ->
                    if (port > 0) {
                        mdns?.stop()
                        activeMdns.compareAndSet(mdns, null)
                        if (cont.isActive) cont.resume(port)
                    }
                }
                activeMdns.getAndSet(mdns)?.stop()
                mdns.start()
                cont.invokeOnCancellation {
                    mdns?.stop()
                    activeMdns.compareAndSet(mdns, null)
                }
            }
        }
    }

    /**
     * 用配对码与无线调试配对服务握手。
     * @param host 端口来自弹窗时填 127.0.0.1，来自 mDNS 也一样
     */
    suspend fun pair(host: String, port: Int, pairCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        init(appContext)
        runCatching<Unit> {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { com.zhibai.app.i18n.LangManager.t("adb.err.need11", "配对需要 Android 11+") }
            AdbPairingClient(host, port, pairCode.trim(), adbKey).use { client ->
                check(client.start()) { com.zhibai.app.i18n.LangManager.t("adb.err.handshake", "配对握手失败") }
            }
            Log.i(TAG, "pairing succeeded")
        }
    }

    /**
     * 通过 adb shell 执行命令，返回 stdout+stderr。
     * 未连接/失败返回 null（调用方自行回退沙箱 sh）。
     */
    suspend fun executeShell(command: String, timeoutMs: Long = 30000): String? {
        if (!initialized) return null
        val port = discoverConnectPort() ?: return null
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeoutMs) {
                    val output = StringBuilder()
                    AdbClient(HOST, port, adbKey).use { client ->
                        client.connect()
                        client.shellCommand(command) { chunk ->
                            output.append(String(chunk, Charsets.UTF_8))
                        }
                    }
                    saveConnectPort(port)
                    output.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "executeShell failed: $e")
                // 端口失效，清掉缓存
                saveConnectPort(-1)
                null
            }
        }
    }

    /** 测试连接是否可用。 */
    suspend fun testConnection(): Boolean = executeShell("echo ok")?.contains("ok") == true
}

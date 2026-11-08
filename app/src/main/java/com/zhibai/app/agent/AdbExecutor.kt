package com.zhibai.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.zhibai.app.adb.AdbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure wireless-ADB executor: input gestures + screencap.
 * No MediaProjection / Accessibility required.
 */
class AdbExecutor {

    companion object {
        private const val TAG = "AdbExecutor"
        private const val SHELL_TIMEOUT_MS = 8_000L
        private const val CAPTURE_PATH = "/data/local/tmp/mimo_cap.png"

        // Minimal common CN apps (Open-AutoGLM has a full list; add as needed)
        private val APP_PACKAGES = mapOf(
            "微信" to "com.tencent.mm",
            "QQ" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "支付宝" to "com.eg.android.AlipayGphone",
            "抖音" to "com.ss.android.ugc.aweme",
            "哔哩哔哩" to "tv.danmaku.bili",
            "快手" to "com.smile.gifmaker",
            "小红书" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "携程" to "ctrip.android.view",
            "12306" to "com.MobileTicket",
            "滴滴出行" to "com.sdu.didi.psnger",
            "大众点评" to "com.dianping.v1",
            "爱奇艺" to "com.qiyi.video",
            "腾讯视频" to "com.tencent.qqlive",
            "浏览器" to "com.android.browser",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.miui.gallery",
            "电话" to "com.android.contacts",
            "短信" to "com.android.mms",
            "应用商店" to "com.xiaomi.mipicks",
            "时钟" to "com.android.deskclock",
            "日历" to "com.android.calendar",
            "天气" to "com.miui.weather2"
        )
    }

    /** screencap via ADB → base64 → Bitmap. */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val saved = AdbManager.executeShell("screencap -p $CAPTURE_PATH", 15_000)
            if (saved == null) {
                Log.w(TAG, "screencap failed")
                return@withContext null
            }
            val b64 = AdbManager.executeShell("base64 $CAPTURE_PATH", 20_000)
                ?.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
            AdbManager.executeShell("rm -f $CAPTURE_PATH", 5_000)
            if (b64.isNullOrBlank()) {
                Log.w(TAG, "screencap base64 empty")
                return@withContext null
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "captureScreen failed", e)
            null
        }
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        AdbManager.executeShell("echo ok", SHELL_TIMEOUT_MS)?.contains("ok") == true
    }

    suspend fun tap(x: Int, y: Int): Boolean = shellOk("input tap $x $y")

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean =
        shellOk("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 800): Boolean =
        shellOk("input swipe $x $y $x $y $durationMs")

    suspend fun doubleTap(x: Int, y: Int): Boolean {
        val first = tap(x, y)
        withContext(Dispatchers.IO) { Thread.sleep(50) }
        return first && tap(x, y)
    }

    /** Chinese text needs ADB Keyboard; fall back to `input text` for ASCII. */
    suspend fun inputText(text: String): Boolean {
        if (text.isBlank()) return false
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        val viaKeyboard = shellOk("am broadcast -a ADB_INPUT_TEXT --es msg '$escaped'")
        if (viaKeyboard && !text.any { it.code > 127 }) return true
        val ascii = text.replace(" ", "%s").replace("'", "")
        return shellOk("input text '$ascii'")
    }

    suspend fun pressBack(): Boolean = shellOk("input keyevent 4")

    suspend fun pressHome(): Boolean = shellOk("input keyevent 3")

    suspend fun launchApp(appOrPackage: String): Boolean {
        if (appOrPackage.isBlank()) return false
        val pkg = resolvePackage(appOrPackage)
        if (pkg.isBlank()) return false
        return shellOk("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    private fun resolvePackage(name: String): String {
        if (name.contains('.')) return name
        APP_PACKAGES[name]?.let { return it }
        APP_PACKAGES.entries.firstOrNull { name.contains(it.key) || it.key.contains(name) }?.let {
            return it.value
        }
        return name
    }

    suspend fun getScreenSize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val out = withTimeoutOrNull(SHELL_TIMEOUT_MS) {
            AdbManager.executeShell("wm size", SHELL_TIMEOUT_MS)
        }
        val m = Regex("(\\d+)x(\\d+)").find(out ?: "")
        if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt()
        else 1080 to 2340
    }

    private suspend fun shellOk(command: String): Boolean {
        return try {
            val out = AdbManager.executeShell(command, SHELL_TIMEOUT_MS)
            if (out == null) {
                Log.w(TAG, "shell failed: $command")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "shell error: $command", e)
            false
        }
    }
}

package com.zhibai.app.localai

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Internal device support list for local AI, expressed as RAM tiers.
 *
 * Rules (the "support list" lives here, not in a server — it must work offline):
 *  - arm64-v8a only (the packaged MNN .so is arm64)
 *  - ≥4GB total RAM to be supported at all
 *  - tier from total RAM: LOW 4–6GB, MID 6–8GB, HIGH ≥8GB
 *  - ModelRegistry maps the tier to a max model size (1B / 3B / 7B+)
 */
object LocalAiSupport {
    enum class Tier { LOW, MID, HIGH }

    data class Support(
        val supported: Boolean,
        val tier: Tier?,
        val totalRamGb: Double,
        val reason: String = "",
    )

    fun check(context: Context): Support {
        val abiOk = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / 1024.0 / 1024.0 / 1024.0

        if (!abiOk) {
            return Support(
                supported = false, tier = null, totalRamGb = totalRamGb,
                reason = "需要 arm64-v8a 架构（当前: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}）"
            )
        }
        if (totalRamGb < 4.0) {
            return Support(
                supported = false, tier = null, totalRamGb = totalRamGb,
                reason = "内存不足（需要 ≥4GB，当前 ${"%.1f".format(totalRamGb)}GB）"
            )
        }
        val tier = when {
            totalRamGb >= 8.0 -> Tier.HIGH
            totalRamGb >= 6.0 -> Tier.MID
            else -> Tier.LOW
        }
        return Support(supported = true, tier = tier, totalRamGb = totalRamGb)
    }

    fun tierLabel(tier: Tier?): String = when (tier) {
        Tier.LOW -> "低"
        Tier.MID -> "中"
        Tier.HIGH -> "高"
        null -> "不支持"
    }
}

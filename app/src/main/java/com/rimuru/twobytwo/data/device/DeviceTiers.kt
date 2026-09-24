package com.rimuru.twobytwo.data.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Device tiering (PRD §6.1/§6.2): low-spec = < 4 GB RAM or no Vulkan 1.1 →
 * default fast path + CPU INT8.
 */
object DeviceTiers {

    data class Tier(
        val isLowSpec: Boolean,
        val totalRamMb: Long,
        val hasVulkan: Boolean,
        val recommendedTileSize: Int,
    )

    fun classify(context: Context): Tier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024 * 1024)

        val pm = context.packageManager
        val hasVulkan = Build.VERSION.SDK_INT >= 24 &&
            pm.hasSystemFeature("android.hardware.vulkan.version") &&
            isVulkan11OrNewer(vulkanVersion(pm))

        val isLowSpec = totalMb < 4_000 || !hasVulkan
        return Tier(
            isLowSpec = isLowSpec,
            totalRamMb = totalMb,
            hasVulkan = hasVulkan,
            recommendedTileSize = if (totalMb >= 6_000 && hasVulkan) 512 else 256,
        )
    }

    internal fun isVulkan11OrNewer(packedVersion: Int): Boolean {
        val major = (packedVersion ushr 22) and 0x3FF
        val minor = (packedVersion ushr 12) and 0x3FF
        return major > 1 || (major == 1 && minor >= 1)
    }

    private fun vulkanVersion(pm: PackageManager): Int =
        pm.systemAvailableFeatures
            .firstOrNull { it.name == "android.hardware.vulkan.version" }
            ?.version
            ?: 0
}

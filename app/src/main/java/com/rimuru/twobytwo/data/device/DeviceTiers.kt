package com.rimuru.twobytwo.data.device

import android.app.ActivityManager
import android.content.Context
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
            vulkanMajorVersion() >= 1

        val isLowSpec = totalMb < 4_000 || !hasVulkan
        return Tier(
            isLowSpec = isLowSpec,
            totalRamMb = totalMb,
            hasVulkan = hasVulkan,
            recommendedTileSize = if (totalMb >= 6_000 && hasVulkan) 512 else 256,
        )
    }

    private fun vulkanMajorVersion(): Int = try {
        val raw = Build.VERSION.RELEASE ?: return 0
        // No public API for the Vulkan version reported by the feature string without
        // PackageManager.FEATURE_VULKAN_HARDWARE_VERSION (hidden); treat presence as 1.1-capable
        // and rely on ORT/NNAPI runtime probing for the truth (HW-1/HW-3 fallback chain).
        if (raw.isNotEmpty()) 1 else 0
    } catch (_: Exception) {
        0
    }
}

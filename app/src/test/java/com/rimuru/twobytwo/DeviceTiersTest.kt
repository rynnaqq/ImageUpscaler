package com.rimuru.twobytwo

import com.rimuru.twobytwo.data.device.DeviceTiers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTiersTest {

    @Test
    fun `vulkan tier requires version 1 1 or newer`() {
        val version10 = 1 shl 22
        val version11 = version10 or (1 shl 12)
        val version20 = 2 shl 22

        assertFalse(DeviceTiers.isVulkan11OrNewer(version10))
        assertTrue(DeviceTiers.isVulkan11OrNewer(version11))
        assertTrue(DeviceTiers.isVulkan11OrNewer(version20))
    }
}

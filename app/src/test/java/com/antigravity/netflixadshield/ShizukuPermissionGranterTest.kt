package com.antigravity.netflixadshield

import com.antigravity.netflixadshield.shizuku.ShizukuGrantCommandSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionGranterTest {

    @Test
    fun allowsOnlyOwnApplicationId() {
        assertTrue(ShizukuGrantCommandSpec.isAllowedPackage("com.antigravity.netflixadshield"))
        assertFalse(ShizukuGrantCommandSpec.isAllowedPackage("com.other.app"))
        assertFalse(ShizukuGrantCommandSpec.isAllowedPackage(""))
        assertFalse(ShizukuGrantCommandSpec.isAllowedPackage("com.antigravity.netflixadshield; rm -rf /"))
    }

    @Test
    fun buildsExpectedPmAndAppOpsCommands() {
        val commands = ShizukuGrantCommandSpec.buildGrantCommands("com.antigravity.netflixadshield")
        assertEquals(2, commands.size)
        assertEquals(
            listOf("pm", "grant", "com.antigravity.netflixadshield", "android.permission.WRITE_SECURE_SETTINGS"),
            commands[0]
        )
        assertEquals(
            listOf("appops", "set", "com.antigravity.netflixadshield", "GET_USAGE_STATS", "allow"),
            commands[1]
        )
    }

    @Test
    fun formatsSuccessAndFailureResponses() {
        assertEquals("OK", ShizukuGrantCommandSpec.formatResult(emptyList()))
        assertEquals(
            "ERROR: pm failed",
            ShizukuGrantCommandSpec.formatResult(listOf("pm failed"))
        )
    }
}

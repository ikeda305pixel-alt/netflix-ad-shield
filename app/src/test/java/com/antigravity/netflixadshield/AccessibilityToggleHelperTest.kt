package com.antigravity.netflixadshield

import com.antigravity.netflixadshield.service.AccessibilityToggleHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityToggleHelperTest {

    private val pkg = "com.antigravity.netflixadshield"
    private val fullComponent = "$pkg/com.antigravity.netflixadshield.service.NetflixAdShieldService"
    private val shortComponent = "$pkg/.service.NetflixAdShieldService"
    private val otherServiceA = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
    private val otherServiceB = "com.example.bitwarden/.AccessibilityService"

    @Test
    fun enableAddsOwnServiceWhenEmptyAndSetsAccessibilityEnabled1() {
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = null,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = true
        )

        assertEquals(fullComponent, result.enabledServicesValue)
        assertEquals(1, result.accessibilityEnabledInt)
        assertTrue(result.changed)
    }

    @Test
    fun enablePreservesAllOtherServicesWithoutOverwriting() {
        val existing = "$otherServiceA:$otherServiceB"
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = existing,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = true
        )

        val parts = result.enabledServicesValue.split(":")
        assertEquals(listOf(otherServiceA, otherServiceB, fullComponent), parts)
        assertEquals(1, result.accessibilityEnabledInt)
        assertTrue(result.changed)
    }

    @Test
    fun disableRemovesOnlyOwnServiceAndPreservesOtherServices() {
        val existing = "$otherServiceA:$fullComponent:$otherServiceB"
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = existing,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = false
        )

        assertEquals("$otherServiceA:$otherServiceB", result.enabledServicesValue)
        assertEquals(1, result.accessibilityEnabledInt)
        assertTrue(result.changed)
    }

    @Test
    fun disableSetsAccessibilityEnabled0WhenSetBecomesEmpty() {
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = fullComponent,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = false
        )

        assertEquals("", result.enabledServicesValue)
        assertEquals(0, result.accessibilityEnabledInt)
        assertTrue(result.changed)
    }

    @Test
    fun disableAlsoRemovesShortFormComponentNameWrittenByAndroidSettings() {
        val existing = "$otherServiceA:$shortComponent"
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = existing,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = false
        )

        assertEquals(otherServiceA, result.enabledServicesValue)
        assertEquals(1, result.accessibilityEnabledInt)
        assertTrue(result.changed)
    }

    @Test
    fun enableDoesNotDuplicateIfShortFormAlreadyPresent() {
        val existing = "$otherServiceA:$shortComponent"
        val result = AccessibilityToggleHelper.computeUpdatedSettings(
            currentRaw = existing,
            fullComponent = fullComponent,
            shortComponent = shortComponent,
            enable = true
        )

        assertEquals(existing, result.enabledServicesValue)
        assertEquals(1, result.accessibilityEnabledInt)
        assertFalse(result.changed)
    }

    @Test
    fun containsTargetServiceMatchesBothFullAndShortForms() {
        assertTrue(
            AccessibilityToggleHelper.containsTargetService(
                "$otherServiceA:$fullComponent",
                fullComponent,
                shortComponent
            )
        )
        assertTrue(
            AccessibilityToggleHelper.containsTargetService(
                "$otherServiceA:$shortComponent",
                fullComponent,
                shortComponent
            )
        )
        assertFalse(
            AccessibilityToggleHelper.containsTargetService(
                "$otherServiceA:$otherServiceB",
                fullComponent,
                shortComponent
            )
        )
    }
}

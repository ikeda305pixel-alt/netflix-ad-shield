package com.antigravity.netflixadshield

import com.antigravity.netflixadshield.core.AdDetectionResult
import com.antigravity.netflixadshield.core.AdShieldStateMachine
import com.antigravity.netflixadshield.core.DeactivateReason
import com.antigravity.netflixadshield.core.MatchReason
import com.antigravity.netflixadshield.core.ShieldAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdShieldStateMachineTest {

    private val adHit = AdDetectionResult(isAdDetected = true, reason = MatchReason.BADGE_TEXT, matchedSignal = "広告 1/2")
    private val noAd = AdDetectionResult(isAdDetected = false, reason = MatchReason.NONE, matchedSignal = null)

    @Test
    fun activatesOnAdAndDebouncesBeforeEnding() {
        val sm = AdShieldStateMachine(endDebounceMs = 1800L, maxShieldMs = 90_000L, manualCooldownMs = 15_000L)

        // Start ad at t=1000
        val a1 = sm.onScan(nowMs = 1000L, isNetflixForeground = true, detection = adHit)
        assertTrue(a1 is ShieldAction.Activate)
        assertTrue(sm.isActive())

        // Brief miss at t=1600 (600ms < 1800ms) -> keep shield
        val a2 = sm.onScan(nowMs = 1600L, isNetflixForeground = true, detection = noAd)
        assertTrue(a2 is ShieldAction.Keep)
        assertTrue(sm.isActive())

        // Still missing at t=2800 (1800ms >= 1800ms) -> deactivate
        val a3 = sm.onScan(nowMs = 2800L, isNetflixForeground = true, detection = noAd)
        assertTrue(a3 is ShieldAction.Deactivate)
        assertEquals(DeactivateReason.AD_ENDED, (a3 as ShieldAction.Deactivate).reason)
        assertFalse(sm.isActive())
    }

    @Test
    fun resetsDebounceTimerWhenAdReappears() {
        val sm = AdShieldStateMachine(endDebounceMs = 1800L, maxShieldMs = 90_000L, manualCooldownMs = 15_000L)

        sm.onScan(nowMs = 1000L, isNetflixForeground = true, detection = adHit)
        sm.onScan(nowMs = 2000L, isNetflixForeground = true, detection = noAd) // 1000ms miss
        val keep = sm.onScan(nowMs = 2500L, isNetflixForeground = true, detection = adHit) // ad back!
        assertTrue(keep is ShieldAction.Keep)

        val stillKeep = sm.onScan(nowMs = 3500L, isNetflixForeground = true, detection = noAd) // only 1000ms since 2500
        assertTrue(stillKeep is ShieldAction.Keep)
        assertTrue(sm.isActive())
    }

    @Test
    fun enforcesFailsafeMaxTimeoutAndCooldown() {
        val sm = AdShieldStateMachine(endDebounceMs = 1800L, maxShieldMs = 60_000L, manualCooldownMs = 15_000L)

        sm.onScan(nowMs = 0L, isNetflixForeground = true, detection = adHit)
        val timeout = sm.onScan(nowMs = 60_000L, isNetflixForeground = true, detection = adHit)
        assertTrue(timeout is ShieldAction.Deactivate)
        assertEquals(DeactivateReason.TIMEOUT_FAILSAFE, (timeout as ShieldAction.Deactivate).reason)
        assertFalse(sm.isActive())

        // Immediately afterwards, must stay suppressed in cooldown
        val duringCooldown = sm.onScan(nowMs = 62_000L, isNetflixForeground = true, detection = adHit)
        assertTrue(duringCooldown is ShieldAction.NoOp)
    }

    @Test
    fun manualDismissDeactivatesAndRespectsCooldown() {
        val sm = AdShieldStateMachine(endDebounceMs = 1800L, maxShieldMs = 90_000L, manualCooldownMs = 15_000L)

        sm.onScan(nowMs = 1000L, isNetflixForeground = true, detection = adHit)
        val dismissed = sm.onManualDismiss(nowMs = 5000L)
        assertTrue(dismissed is ShieldAction.Deactivate)
        assertEquals(DeactivateReason.MANUAL_DISMISS, (dismissed as ShieldAction.Deactivate).reason)
        assertFalse(sm.isActive())

        // Still in cooldown at t=19999
        assertTrue(sm.onScan(nowMs = 19_999L, isNetflixForeground = true, detection = adHit) is ShieldAction.NoOp)

        // Cooldown expired at t=20000 -> can activate again
        assertTrue(sm.onScan(nowMs = 20_000L, isNetflixForeground = true, detection = adHit) is ShieldAction.Activate)
    }

    @Test
    fun deactivatesImmediatelyOnAppLeaveOrScreenOff() {
        val sm = AdShieldStateMachine()
        sm.onScan(nowMs = 1000L, isNetflixForeground = true, detection = adHit)

        val left = sm.onScan(nowMs = 1500L, isNetflixForeground = false, detection = noAd)
        assertTrue(left is ShieldAction.Deactivate)
        assertEquals(DeactivateReason.APP_LEFT, (left as ShieldAction.Deactivate).reason)

        sm.onScan(nowMs = 3000L, isNetflixForeground = true, detection = adHit)
        val screenOff = sm.onScreenOff()
        assertTrue(screenOff is ShieldAction.Deactivate)
        assertEquals(DeactivateReason.SCREEN_OFF, (screenOff as ShieldAction.Deactivate).reason)
    }

    @Test
    fun ignoresSignalsWhenNetflixNotInForeground() {
        val sm = AdShieldStateMachine()
        val action = sm.onScan(nowMs = 1000L, isNetflixForeground = false, detection = adHit)
        assertTrue(action is ShieldAction.NoOp)
        assertFalse(sm.isActive())
    }
}

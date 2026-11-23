package com.antigravity.netflixadshield

import com.antigravity.netflixadshield.core.WatchdogDecision
import com.antigravity.netflixadshield.core.WatchdogDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogDecisionEngineTest {

    private val netflix = "com.netflix.mediaclient"
    private val paypay = "jp.ne.paypay.android.app"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val systemUi = "com.android.systemui"

    @Test
    fun enablesImmediatelyWhenNetflixEntersForeground() {
        val engine = WatchdogDecisionEngine()
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
    }

    @Test
    fun disablesImmediatelyWithoutDelayWhenSwitchingToPayPayOrLauncher() {
        val engine = WatchdogDecisionEngine()
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        assertEquals(WatchdogDecision.DISABLE, engine.onForegroundPackageSampled(paypay))

        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        assertEquals(WatchdogDecision.DISABLE, engine.onForegroundPackageSampled(launcher))
    }

    @Test
    fun retainsEnabledStateWhenNoNewEventOccursDuringLongNetflixPlayback() {
        val engine = WatchdogDecisionEngine()
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        // Long episode playback: no new ACTIVITY_RESUMED in recent 10s window
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(null))
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(null))
    }

    @Test
    fun grantsOneTickGraceForTransientSystemUiThenDisablesIfPersistent() {
        val engine = WatchdogDecisionEngine(systemUiGraceTicks = 1)
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))

        // First tick of SystemUI (e.g. brief volume/overlay): keep enabled (1.5s grace)
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(systemUi))

        // Second consecutive tick still on SystemUI: turn OFF
        assertEquals(WatchdogDecision.DISABLE, engine.onForegroundPackageSampled(systemUi))
    }

    @Test
    fun resetsSystemUiGraceWhenNetflixReturnsAndDisablesImmediatelyIfPayPayFollows() {
        val engine = WatchdogDecisionEngine(systemUiGraceTicks = 1)
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(systemUi))

        // Netflix returns -> grace resets
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(systemUi))

        // PayPay immediately follows SystemUI -> instant DISABLE
        assertEquals(WatchdogDecision.DISABLE, engine.onForegroundPackageSampled(paypay))
    }

    @Test
    fun disablesAndResetsStateOnScreenOff() {
        val engine = WatchdogDecisionEngine()
        assertEquals(WatchdogDecision.ENABLE, engine.onForegroundPackageSampled(netflix))
        assertEquals(WatchdogDecision.DISABLE, engine.onScreenOff())

        // After screen-on with no new event yet, stays DISABLED until Netflix actually resumes
        assertEquals(WatchdogDecision.DISABLE, engine.onForegroundPackageSampled(null))
    }
}

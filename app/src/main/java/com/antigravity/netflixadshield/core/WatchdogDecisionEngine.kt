package com.antigravity.netflixadshield.core

enum class WatchdogDecision {
    ENABLE,
    DISABLE
}

/**
 * Pure Kotlin state machine for PayPay-safe dynamic Accessibility toggling.
 * - Enables immediately when Netflix (com.netflix.mediaclient) is foreground.
 * - Disables immediately (zero delay) when any normal non-Netflix app (e.g. PayPay, Home) is foreground.
 * - Grants a single-tick grace period (1.5s) for transient SystemUI (e.g. volume bar) while Netflix was active,
 *   disabling if SystemUI persists on the next consecutive tick.
 * - Retains the last effective state when no new ACTIVITY_RESUMED event occurred during continuous playback.
 * - Immediately disables and resets state on screen off.
 */
class WatchdogDecisionEngine(
    private val netflixPackage: String = DEFAULT_NETFLIX_PACKAGE,
    private val systemUiPackage: String = DEFAULT_SYSTEM_UI_PACKAGE,
    private val systemUiGraceTicks: Int = 1
) {
    private var lastEffectivePackage: String? = null
    private var isExpectedEnabled: Boolean = false
    private var consecutiveSystemUiTicks: Int = 0

    fun onForegroundPackageSampled(latestResumedPackage: String?): WatchdogDecision {
        if (latestResumedPackage == null) {
            return if (isExpectedEnabled && lastEffectivePackage == netflixPackage) {
                WatchdogDecision.ENABLE
            } else {
                isExpectedEnabled = false
                WatchdogDecision.DISABLE
            }
        }

        if (latestResumedPackage == netflixPackage) {
            lastEffectivePackage = netflixPackage
            consecutiveSystemUiTicks = 0
            isExpectedEnabled = true
            return WatchdogDecision.ENABLE
        }

        if (latestResumedPackage == systemUiPackage) {
            if (isExpectedEnabled && consecutiveSystemUiTicks < systemUiGraceTicks) {
                consecutiveSystemUiTicks++
                return WatchdogDecision.ENABLE
            }
            consecutiveSystemUiTicks = 0
            lastEffectivePackage = systemUiPackage
            isExpectedEnabled = false
            return WatchdogDecision.DISABLE
        }

        // Any other app (PayPay, launcher, settings, browser, etc.) -> immediate OFF with zero delay
        consecutiveSystemUiTicks = 0
        lastEffectivePackage = latestResumedPackage
        isExpectedEnabled = false
        return WatchdogDecision.DISABLE
    }

    fun onScreenOff(): WatchdogDecision {
        consecutiveSystemUiTicks = 0
        lastEffectivePackage = null
        isExpectedEnabled = false
        return WatchdogDecision.DISABLE
    }

    companion object {
        const val DEFAULT_NETFLIX_PACKAGE = "com.netflix.mediaclient"
        const val DEFAULT_SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

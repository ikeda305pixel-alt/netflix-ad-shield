package com.antigravity.netflixadshield.core

enum class DeactivateReason {
    AD_ENDED,
    TIMEOUT_FAILSAFE,
    MANUAL_DISMISS,
    APP_LEFT,
    SCREEN_OFF,
    SERVICE_DISABLED
}

sealed class ShieldAction {
    data class Activate(val signal: String?) : ShieldAction()
    data class Keep(val elapsedMs: Long, val signal: String?) : ShieldAction()
    data class Deactivate(val reason: DeactivateReason) : ShieldAction()
    data object NoOp : ShieldAction()
}

/**
 * Deterministic finite state machine managing ad-shield activation, end-debounce,
 * failsafe maximum timeout, manual 1-tap override cooldown, and immediate cleanup.
 */
class AdShieldStateMachine(
    private var endDebounceMs: Long = 1800L,
    private var maxShieldMs: Long = 90_000L,
    private var manualCooldownMs: Long = 15_000L,
    private var timeoutCooldownMs: Long = 10_000L
) {
    private var active: Boolean = false
    private var startedAtMs: Long = 0L
    private var lastDetectedAtMs: Long = 0L
    private var lastSignal: String? = null
    private var cooldownUntilMs: Long = 0L

    fun updateThresholds(endDebounceMs: Long, maxShieldMs: Long) {
        this.endDebounceMs = endDebounceMs.coerceAtLeast(300L)
        this.maxShieldMs = maxShieldMs.coerceAtLeast(10_000L)
    }

    fun isActive(): Boolean = active

    fun onScan(
        nowMs: Long,
        isNetflixForeground: Boolean,
        detection: AdDetectionResult
    ): ShieldAction {
        if (!isNetflixForeground) {
            return if (active) {
                resetActive()
                ShieldAction.Deactivate(DeactivateReason.APP_LEFT)
            } else {
                ShieldAction.NoOp
            }
        }

        if (active) {
            val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
            if (elapsed >= maxShieldMs) {
                resetActive()
                cooldownUntilMs = nowMs + timeoutCooldownMs
                return ShieldAction.Deactivate(DeactivateReason.TIMEOUT_FAILSAFE)
            }

            if (detection.isAdDetected) {
                lastDetectedAtMs = nowMs
                if (detection.matchedSignal != null) {
                    lastSignal = detection.matchedSignal
                }
                return ShieldAction.Keep(elapsed, lastSignal)
            }

            val missingForMs = (nowMs - lastDetectedAtMs).coerceAtLeast(0L)
            return if (missingForMs >= endDebounceMs) {
                resetActive()
                ShieldAction.Deactivate(DeactivateReason.AD_ENDED)
            } else {
                ShieldAction.Keep(elapsed, lastSignal)
            }
        }

        // Currently IDLE
        if (nowMs < cooldownUntilMs) {
            return ShieldAction.NoOp
        }

        if (detection.isAdDetected) {
            active = true
            startedAtMs = nowMs
            lastDetectedAtMs = nowMs
            lastSignal = detection.matchedSignal
            return ShieldAction.Activate(lastSignal)
        }

        return ShieldAction.NoOp
    }

    fun onManualDismiss(nowMs: Long): ShieldAction {
        cooldownUntilMs = nowMs + manualCooldownMs
        return if (active) {
            resetActive()
            ShieldAction.Deactivate(DeactivateReason.MANUAL_DISMISS)
        } else {
            ShieldAction.NoOp
        }
    }

    fun onScreenOff(): ShieldAction {
        return if (active) {
            resetActive()
            ShieldAction.Deactivate(DeactivateReason.SCREEN_OFF)
        } else {
            ShieldAction.NoOp
        }
    }

    fun onServiceDisabled(): ShieldAction {
        return if (active) {
            resetActive()
            ShieldAction.Deactivate(DeactivateReason.SERVICE_DISABLED)
        } else {
            ShieldAction.NoOp
        }
    }

    private fun resetActive() {
        active = false
        startedAtMs = 0L
        lastDetectedAtMs = 0L
        lastSignal = null
    }
}

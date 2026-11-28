package com.antigravity.netflixadshield.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.antigravity.netflixadshield.core.AdDetectionEngine
import com.antigravity.netflixadshield.core.AdShieldStateMachine
import com.antigravity.netflixadshield.core.DeactivateReason
import com.antigravity.netflixadshield.core.ShieldAction
import com.antigravity.netflixadshield.core.UiNodeSnapshot
import com.antigravity.netflixadshield.data.DiagnosticLogStore
import com.antigravity.netflixadshield.data.ShieldPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AccessibilityService that observes ONLY Netflix (com.netflix.mediaclient) UI nodes
 * to detect ad playback, mute media volume, and display the pitch-black cover overlay.
 */
class NetflixAdShieldService : AccessibilityService() {

    private lateinit var prefs: ShieldPreferences
    private lateinit var logStore: DiagnosticLogStore
    private lateinit var volumeController: VolumeController
    private lateinit var overlayManager: OverlayCoverManager

    private val detectionEngine = AdDetectionEngine()
    private val stateMachine = AdShieldStateMachine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pollJob: Job? = null
    private var isNetflixForeground: Boolean = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                isNetflixForeground = false
                stopPolling()
                overlayManager.hideDiagnosticPill()
                applyAction(stateMachine.onScreenOff())
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = ShieldPreferences(this)
        logStore = DiagnosticLogStore(this)
        volumeController = VolumeController(this)
        overlayManager = OverlayCoverManager(this)
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isServiceRunning = true
        activeInstance = this

        // When enabled dynamically by NetflixWatchdogService while Netflix is already visible,
        // begin scanning immediately without waiting for the next accessibility event.
        if (findNetflixRootWindow() != null) {
            isNetflixForeground = true
            onNetflixEntered()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own overlay windows so showing the cover never triggers "APP_LEFT"
        if (pkg == applicationContext.packageName || pkg == SYSTEM_UI_PACKAGE) {
            return
        }

        if (pkg != NETFLIX_PACKAGE) {
            if (isNetflixForeground &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || findNetflixRootWindow() == null)
            ) {
                isNetflixForeground = false
                onNetflixLeft()
            }
            return
        }

        if (!isNetflixForeground) {
            isNetflixForeground = true
            onNetflixEntered()
        }
        if (prefs.isMasterEnabled) {
            scanNetflixWindow(trigger = "event")
        }
    }

    override fun onInterrupt() {
        cleanupEverything()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }
        cleanupEverything()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
            // Not registered
        }
        cleanupEverything()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onNetflixEntered() {
        updateDiagnosticPillVisibility()
        if (prefs.isMasterEnabled || prefs.diagnosticModeEnabled) {
            startPolling()
        }
        if (prefs.isMasterEnabled) {
            scanNetflixWindow(trigger = "enter")
        }
    }

    private fun onNetflixLeft() {
        isNetflixForeground = false
        stopPolling()
        if (::overlayManager.isInitialized) {
            overlayManager.hideAll()
        }
        applyAction(
            stateMachine.onScan(
                nowMs = SystemClock.elapsedRealtime(),
                isNetflixForeground = false,
                detection = detectionEngine.evaluate(emptyList())
            )
        )
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive && isNetflixForeground) {
                delay(POLL_INTERVAL_MS)
                // Re-verify Netflix is still actually on screen (catches gesture-nav Home swipes)
                if (findNetflixRootWindow() == null) {
                    onNetflixLeft()
                    break
                }
                updateDiagnosticPillVisibility()
                if (prefs.isMasterEnabled) {
                    scanNetflixWindow(trigger = "poll")
                } else if (stateMachine.isActive()) {
                    applyAction(stateMachine.onServiceDisabled())
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun updateDiagnosticPillVisibility() {
        if (isNetflixForeground && prefs.diagnosticModeEnabled && findNetflixRootWindow() != null) {
            overlayManager.showDiagnosticPill {
                captureDiagnosticSnapshotManually()
            }
        } else {
            overlayManager.hideDiagnosticPill()
        }
    }

    private fun findNetflixRootWindow(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == NETFLIX_PACKAGE) {
            return active
        }
        return windows?.firstOrNull { it.root?.packageName?.toString() == NETFLIX_PACKAGE }?.root
    }

    private fun scanNetflixWindow(trigger: String) {
        val root = findNetflixRootWindow()
        if (root == null) {
            if (isNetflixForeground) {
                onNetflixLeft()
            }
            return
        }

        stateMachine.updateThresholds(
            endDebounceMs = DEFAULT_END_DEBOUNCE_MS,
            maxShieldMs = prefs.maxShieldSeconds * 1000L
        )

        val nodes = collectUiNodes(root)
        val detection = detectionEngine.evaluate(nodes, prefs.toRuleConfig())
        val action = stateMachine.onScan(
            nowMs = SystemClock.elapsedRealtime(),
            isNetflixForeground = true,
            detection = detection
        )

        if (action is ShieldAction.Activate && prefs.diagnosticModeEnabled) {
            serviceScope.launch(Dispatchers.IO) {
                logStore.appendSnapshot("auto_activate_$trigger", NETFLIX_PACKAGE, nodes, detection)
            }
        }

        applyAction(action)
    }

    private fun captureDiagnosticSnapshotManually() {
        val root = findNetflixRootWindow() ?: rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: "null"
        val nodes = if (root != null) collectUiNodes(root) else emptyList()
        val detection = detectionEngine.evaluate(nodes, prefs.toRuleConfig())
        serviceScope.launch(Dispatchers.IO) {
            logStore.appendSnapshot("manual_tap", pkg, nodes, detection)
        }
        Toast.makeText(
            this,
            "ログ保存完了 (${nodes.size}ノード / 広告判定=${detection.isAdDetected})",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyAction(action: ShieldAction) {
        when (action) {
            is ShieldAction.Activate -> {
                if (prefs.autoMuteEnabled) {
                    volumeController.mute()
                }
                if (prefs.screenCoverEnabled) {
                    overlayManager.showCover(action.signal) {
                        val dismissAction = stateMachine.onManualDismiss(SystemClock.elapsedRealtime())
                        applyAction(dismissAction)
                    }
                }
            }
            is ShieldAction.Keep -> {
                if (prefs.screenCoverEnabled) {
                    overlayManager.updateCoverStatus(action.elapsedMs, action.signal)
                }
            }
            is ShieldAction.Deactivate -> {
                overlayManager.hideCover()
                volumeController.restore()
                if (action.reason == DeactivateReason.AD_ENDED && prefs.vibrateOnEndEnabled) {
                    volumeController.vibrateGentle()
                }
            }
            ShieldAction.NoOp -> Unit
        }
    }

    private fun collectUiNodes(root: AccessibilityNodeInfo): List<UiNodeSnapshot> {
        val result = ArrayList<UiNodeSnapshot>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val rect = Rect()

        while (stack.isNotEmpty() && result.size < MAX_NODES_PER_SCAN) {
            val node = stack.removeLast()
            node.getBoundsInScreen(rect)
            val id = node.viewIdResourceName
            val cls = node.className?.toString()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (!id.isNullOrBlank() || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
                result.add(
                    UiNodeSnapshot(
                        viewId = id,
                        className = cls,
                        text = text,
                        contentDesc = desc,
                        boundsSummary = "[${rect.left},${rect.top}-${rect.right},${rect.bottom}]"
                    )
                )
            }

            val childCount = node.childCount
            for (i in childCount - 1 downTo 0) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child)
                }
            }
        }
        return result
    }

    private fun cleanupEverything() {
        isNetflixForeground = false
        stopPolling()
        applyAction(stateMachine.onServiceDisabled())
        if (::overlayManager.isInitialized) {
            overlayManager.hideAll()
        }
        if (::volumeController.isInitialized) {
            volumeController.restore()
        }
    }

    companion object {
        const val NETFLIX_PACKAGE = "com.netflix.mediaclient"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val POLL_INTERVAL_MS = 600L
        private const val DEFAULT_END_DEBOUNCE_MS = 1800L
        private const val MAX_NODES_PER_SCAN = 250

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        private var activeInstance: NetflixAdShieldService? = null

        fun dismissAllOverlaysImmediately() {
            val instance = activeInstance ?: return
            instance.serviceScope.launch(Dispatchers.Main.immediate) {
                instance.cleanupEverything()
            }
        }
    }
}

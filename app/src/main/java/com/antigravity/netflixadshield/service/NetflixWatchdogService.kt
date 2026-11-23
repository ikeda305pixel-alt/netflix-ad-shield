package com.antigravity.netflixadshield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antigravity.netflixadshield.MainActivity
import com.antigravity.netflixadshield.R
import com.antigravity.netflixadshield.core.WatchdogDecision
import com.antigravity.netflixadshield.core.WatchdogDecisionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WatchdogUiState(
    val isRunning: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val lastDetectedPackage: String? = null,
    val permissionError: String? = null
)

/**
 * Lightweight ForegroundService that monitors foreground app changes via UsageStatsManager
 * (1.5s interval) and dynamically enables NetflixAdShieldService ONLY while Netflix is active,
 * disabling it immediately upon leaving Netflix (so PayPay and banking apps never block).
 */
class NetflixWatchdogService : Service() {

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var powerManager: PowerManager
    private lateinit var notificationManager: NotificationManager

    private val decisionEngine = WatchdogDecisionEngine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pollJob: Job? = null
    private var hasSeededInitialSample: Boolean = false
    private var lastNotificationStatus: String = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF -> pause polling and turn Accessibility OFF")
                    stopPolling()
                    decisionEngine.onScreenOff()
                    hasSeededInitialSample = false
                    applyToggle(enable = false, detectedPkg = null, statusLabel = "画面消灯中（ユーザー補助OFF・待機）")
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen ON / Unlocked -> resume polling")
                    startPolling()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForegroundCompat(buildNotification("起動中（ユーザー補助OFF・PayPay安全）"))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        isServiceRunning = true
        publishState(
            isRunning = true,
            isAccessibilityEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this),
            lastPkg = null,
            permissionError = AccessibilityToggleHelper.lastPermissionError
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("監視中（ユーザー補助OFF・PayPay安全）"))

        if (powerManager.isInteractive) {
            startPolling()
        } else {
            decisionEngine.onScreenOff()
            applyToggle(enable = false, detectedPkg = null, statusLabel = "画面消灯中（ユーザー補助OFF・待機）")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "NetflixWatchdogService onDestroy -> failsafe Accessibility OFF")
        stopPolling()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Receiver not registered
        }

        // Failsafe: ALWAYS turn our AccessibilityService OFF when Watchdog stops or is destroyed
        decisionEngine.onScreenOff()
        AccessibilityToggleHelper.setServiceEnabled(this, enable = false)

        isServiceRunning = false
        publishState(
            isRunning = false,
            isAccessibilityEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this),
            lastPkg = null,
            permissionError = AccessibilityToggleHelper.lastPermissionError
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive) {
                if (!powerManager.isInteractive) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        val lookbackMs = if (hasSeededInitialSample) STEADY_LOOKBACK_MS else INITIAL_LOOKBACK_MS
        val latestPkg = withContext(Dispatchers.IO) {
            queryLatestForegroundPackage(lookbackMs)
        }
        hasSeededInitialSample = true

        val decision = decisionEngine.onForegroundPackageSampled(latestPkg)
        val enable = (decision == WatchdogDecision.ENABLE)
        val statusText = if (enable) {
            "Netflix検知中（ユーザー補助ON）"
        } else {
            "監視中（ユーザー補助OFF・PayPay安全）"
        }
        applyToggle(enable = enable, detectedPkg = latestPkg, statusLabel = statusText)
    }

    private fun queryLatestForegroundPackage(lookbackMs: Long): String? {
        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - lookbackMs
            val events = usageStatsManager.queryEvents(startTime, endTime) ?: return null
            val event = UsageEvents.Event()

            var latestPkg: String? = null
            var latestTs = -1L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (isForegroundResumeEvent(event.eventType) && event.timeStamp >= latestTs) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrBlank()) {
                        latestTs = event.timeStamp
                        latestPkg = pkg
                    }
                }
            }
            latestPkg
        } catch (se: SecurityException) {
            Log.e(TAG, "UsageStats permission missing during queryEvents", se)
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Error querying UsageEvents", t)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun isForegroundResumeEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    private fun applyToggle(enable: Boolean, detectedPkg: String?, statusLabel: String) {
        when (val result = AccessibilityToggleHelper.setServiceEnabled(this, enable)) {
            is AccessibilityToggleResult.Success -> {
                updateNotificationIfChanged(statusLabel)
                publishState(
                    isRunning = true,
                    isAccessibilityEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this),
                    lastPkg = detectedPkg,
                    permissionError = null
                )
            }
            is AccessibilityToggleResult.PermissionDenied -> {
                updateNotificationIfChanged("ADB権限未付与（WRITE_SECURE_SETTINGSが必要）")
                publishState(
                    isRunning = true,
                    isAccessibilityEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this),
                    lastPkg = detectedPkg,
                    permissionError = AccessibilityToggleHelper.lastPermissionError
                )
            }
            is AccessibilityToggleResult.Failure -> {
                updateNotificationIfChanged("ユーザー補助の切替に失敗しました")
                publishState(
                    isRunning = true,
                    isAccessibilityEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this),
                    lastPkg = detectedPkg,
                    permissionError = result.error.message
                )
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watchdog_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.watchdog_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        lastNotificationStatus = statusText
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NetflixWatchdogService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_shield)
            .setContentTitle(getString(R.string.watchdog_notif_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.watchdog_notif_stop_action), stopIntent)
            .build()
    }

    private fun updateNotificationIfChanged(statusText: String) {
        if (statusText == lastNotificationStatus) return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+ - continue running silently
        }
    }

    private fun publishState(
        isRunning: Boolean,
        isAccessibilityEnabled: Boolean,
        lastPkg: String?,
        permissionError: String?
    ) {
        _uiState.value = WatchdogUiState(
            isRunning = isRunning,
            isAccessibilityEnabled = isAccessibilityEnabled,
            lastDetectedPackage = lastPkg,
            permissionError = permissionError
        )
    }

    companion object {
        private const val TAG = "NetflixWatchdog"
        private const val CHANNEL_ID = "netflix_watchdog_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_STOP = "com.antigravity.netflixadshield.action.STOP_WATCHDOG"

        private const val POLL_INTERVAL_MS = 1_500L
        private const val STEADY_LOOKBACK_MS = 8_000L
        private const val INITIAL_LOOKBACK_MS = 60_000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        private val _uiState = MutableStateFlow(WatchdogUiState())
        val uiState: StateFlow<WatchdogUiState> = _uiState.asStateFlow()

        fun createStartIntent(context: Context): Intent {
            return Intent(context.applicationContext, NetflixWatchdogService::class.java)
        }
    }
}

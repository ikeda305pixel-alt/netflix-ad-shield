package com.antigravity.netflixadshield

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.netflixadshield.data.DiagnosticLogStore
import com.antigravity.netflixadshield.data.ShieldPreferences
import com.antigravity.netflixadshield.databinding.ActivityMainBinding
import com.antigravity.netflixadshield.service.AccessibilityToggleHelper
import com.antigravity.netflixadshield.service.AccessibilityToggleResult
import com.antigravity.netflixadshield.service.NetflixWatchdogService
import com.antigravity.netflixadshield.service.OverlayCoverManager
import com.antigravity.netflixadshield.service.VolumeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ShieldPreferences
    private lateinit var logStore: DiagnosticLogStore
    private lateinit var overlayManager: OverlayCoverManager
    private lateinit var volumeController: VolumeController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewJob: Job? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start watchdog regardless of whether user granted notification display
            startWatchdogServiceNow()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = ShieldPreferences(this)
        logStore = DiagnosticLogStore(this)
        overlayManager = OverlayCoverManager(this)
        volumeController = VolumeController(this)

        bindInitialValues()
        bindListeners()
        observeWatchdogState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshDiagnosticLogView()
    }

    override fun onDestroy() {
        stopPreview()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindInitialValues() {
        binding.switchMaster.isChecked = prefs.isMasterEnabled
        binding.switchAutoMute.isChecked = prefs.autoMuteEnabled
        binding.switchScreenCover.isChecked = prefs.screenCoverEnabled
        binding.switchVibrateOnEnd.isChecked = prefs.vibrateOnEndEnabled
        binding.switchDiagnosticMode.isChecked = prefs.diagnosticModeEnabled
        binding.editMaxSeconds.setText(prefs.maxShieldSeconds.toString())
        binding.editCustomKeywords.setText(prefs.customKeywordsRaw)
        binding.editCustomResIds.setText(prefs.customResourceIdsRaw)
    }

    private fun bindListeners() {
        binding.btnGrantUsageStats.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnToggleWatchdog.setOnClickListener {
            if (NetflixWatchdogService.isServiceRunning) {
                stopService(Intent(this, NetflixWatchdogService::class.java))
                Toast.makeText(this, "自動切替監視を停止し、ユーザー補助をOFFにしました", Toast.LENGTH_SHORT).show()
                refreshPermissionStatus()
            } else {
                requestStartWatchdog()
            }
        }

        binding.btnForceAccessibilityOff.setOnClickListener {
            if (NetflixWatchdogService.isServiceRunning) {
                stopService(Intent(this, NetflixWatchdogService::class.java))
            }
            when (AccessibilityToggleHelper.setServiceEnabled(this, enable = false)) {
                is AccessibilityToggleResult.Success -> {
                    Toast.makeText(this, "ユーザー補助を即座にOFFにしました（PayPay安全）", Toast.LENGTH_SHORT).show()
                }
                is AccessibilityToggleResult.PermissionDenied -> {
                    Toast.makeText(
                        this,
                        "ADB権限未付与のため自動OFF不可。setup_paypay_safe.ps1 を実行するか手動でOFFにしてください",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is AccessibilityToggleResult.Failure -> {
                    Toast.makeText(this, "ユーザー補助のOFFに失敗しました", Toast.LENGTH_SHORT).show()
                }
            }
            refreshPermissionStatus()
        }

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            prefs.isMasterEnabled = isChecked
        }
        binding.switchAutoMute.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoMuteEnabled = isChecked
        }
        binding.switchScreenCover.setOnCheckedChangeListener { _, isChecked ->
            prefs.screenCoverEnabled = isChecked
        }
        binding.switchVibrateOnEnd.setOnCheckedChangeListener { _, isChecked ->
            prefs.vibrateOnEndEnabled = isChecked
        }
        binding.switchDiagnosticMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.diagnosticModeEnabled = isChecked
            val msg = if (isChecked) {
                "診断モードON: Netflix画面右上に「ログ採取」ボタンが表示されます"
            } else {
                "診断モードOFF"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveRules.setOnClickListener {
            val sec = binding.editMaxSeconds.text?.toString()?.trim()?.toIntOrNull() ?: 90
            prefs.maxShieldSeconds = sec
            binding.editMaxSeconds.setText(prefs.maxShieldSeconds.toString())
            prefs.customKeywordsRaw = binding.editCustomKeywords.text?.toString().orEmpty()
            prefs.customResourceIdsRaw = binding.editCustomResIds.text?.toString().orEmpty()
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestPreview.setOnClickListener {
            runThreeSecondPreview()
        }

        binding.btnRefreshLog.setOnClickListener {
            refreshDiagnosticLogView()
        }

        binding.btnCopyLog.setOnClickListener {
            uiScope.launch {
                val content = withContext(Dispatchers.IO) { logStore.readAll() }
                if (content.isBlank()) {
                    Toast.makeText(this@MainActivity, "保存されたログがありません", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NetflixAdShield Diagnostic Log", content))
                Toast.makeText(this@MainActivity, "診断ログをコピーしました", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearLog.setOnClickListener {
            uiScope.launch {
                withContext(Dispatchers.IO) { logStore.clear() }
                refreshDiagnosticLogView()
                Toast.makeText(this@MainActivity, "診断ログを消去しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeWatchdogState() {
        uiScope.launch {
            NetflixWatchdogService.uiState.collect {
                if (!isFinishing && !isDestroyed) {
                    refreshPermissionStatus()
                }
            }
        }
    }

    private fun requestStartWatchdog() {
        if (!AccessibilityToggleHelper.hasWriteSecureSettingsPermission(this)) {
            Toast.makeText(
                this,
                "ADB権限未付与（WRITE_SECURE_SETTINGS）。先に PC で setup_paypay_safe.ps1 を実行してください",
                Toast.LENGTH_LONG
            ).show()
            refreshPermissionStatus()
            return
        }
        if (!AccessibilityToggleHelper.hasUsageStatsPermission(this)) {
            Toast.makeText(
                this,
                "先に「使用状況へのアクセス権限」を許可してください",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startWatchdogServiceNow()
    }

    private fun startWatchdogServiceNow() {
        ContextCompat.startForegroundService(this, NetflixWatchdogService.createStartIntent(this))
        Toast.makeText(this, "自動切替（PayPay両立モード）を開始しました", Toast.LENGTH_SHORT).show()
        refreshPermissionStatus()
    }

    private fun runThreeSecondPreview() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先に「他のアプリの上に重ねて表示」を許可してください", Toast.LENGTH_LONG).show()
            return
        }
        stopPreview()
        previewJob = uiScope.launch {
            if (prefs.autoMuteEnabled) {
                volumeController.mute()
            }
            overlayManager.showCover("テスト表示 (3秒で自動復帰)") {
                stopPreview()
            }
            for (sec in 0..3) {
                if (isFinishing || isDestroyed) break
                overlayManager.updateCoverStatus(sec * 1000L, "テスト表示 (残り ${3 - sec}秒)")
                if (sec < 3) delay(1000L)
            }
            stopPreview()
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        if (::overlayManager.isInitialized) {
            overlayManager.hideCover()
        }
        if (::volumeController.isInitialized) {
            volumeController.restore()
        }
    }

    private fun refreshPermissionStatus() {
        // 1. WRITE_SECURE_SETTINGS
        val writeSecureGranted = AccessibilityToggleHelper.hasWriteSecureSettingsPermission(this)
        binding.txtWriteSecureStatus.text = if (writeSecureGranted) "付与済み" else "未付与（ADB権限未付与）"
        binding.txtWriteSecureStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (writeSecureGranted) R.color.status_ok else R.color.status_warn
            )
        )

        // 2. PACKAGE_USAGE_STATS
        val usageGranted = AccessibilityToggleHelper.hasUsageStatsPermission(this)
        binding.txtUsageStatsStatus.text = if (usageGranted) "許可済み" else "未許可"
        binding.txtUsageStatsStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (usageGranted) R.color.status_ok else R.color.status_warn
            )
        )
        binding.btnGrantUsageStats.visibility = if (usageGranted) View.GONE else View.VISIBLE

        // 3. Watchdog Service status
        val watchdogRunning = NetflixWatchdogService.isServiceRunning
        binding.txtWatchdogStatus.text = if (watchdogRunning) "稼働中" else "停止中"
        binding.txtWatchdogStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (watchdogRunning) R.color.status_ok else R.color.text_secondary
            )
        )
        binding.btnToggleWatchdog.text = getString(
            if (watchdogRunning) R.string.paypay_mode_stop else R.string.paypay_mode_start
        )

        // 4. Current Accessibility ON/OFF status
        val a11yEnabled = AccessibilityToggleHelper.isOurServiceEnabled(this)
        binding.txtCurrentAccessibilityOnOff.text = if (a11yEnabled) "ON" else "OFF"
        binding.txtCurrentAccessibilityOnOff.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11yEnabled) R.color.status_warn else R.color.status_ok
            )
        )

        // ADB / SecurityException alert banner
        val permErr = AccessibilityToggleHelper.lastPermissionError
        if (!writeSecureGranted || permErr != null) {
            binding.txtWatchdogError.visibility = View.VISIBLE
            binding.txtWatchdogError.text = permErr
                ?: "ADB権限未付与: 自動ON/OFFには PC での setup_paypay_safe.ps1 実行が必要です"
        } else {
            binding.txtWatchdogError.visibility = View.GONE
        }

        // Legacy / base setup status card
        val overlayGranted = Settings.canDrawOverlays(this)
        binding.txtOverlayStatus.text = if (overlayGranted) "許可済み" else "未許可"
        binding.txtOverlayStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted) R.color.status_ok else R.color.status_warn
            )
        )

        binding.txtAccessibilityStatus.text = if (a11yEnabled) "有効 (ON)" else "無効 (OFF)"
        binding.txtAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11yEnabled) R.color.status_ok else R.color.text_secondary
            )
        )
    }

    private fun refreshDiagnosticLogView() {
        uiScope.launch {
            val content = withContext(Dispatchers.IO) { logStore.readAll() }
            if (isFinishing || isDestroyed) return@launch
            binding.txtLogPreview.text = if (content.isBlank()) {
                getString(R.string.diag_log_empty)
            } else {
                content.take(4000)
            }
        }
    }
}

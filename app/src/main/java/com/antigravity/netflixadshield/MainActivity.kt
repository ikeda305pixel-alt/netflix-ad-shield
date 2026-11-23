package com.antigravity.netflixadshield

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antigravity.netflixadshield.data.DiagnosticLogStore
import com.antigravity.netflixadshield.data.ShieldPreferences
import com.antigravity.netflixadshield.databinding.ActivityMainBinding
import com.antigravity.netflixadshield.service.NetflixAdShieldService
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
        val overlayGranted = Settings.canDrawOverlays(this)
        binding.txtOverlayStatus.text = if (overlayGranted) "許可済み" else "未許可"
        binding.txtOverlayStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted) R.color.status_ok else R.color.status_warn
            )
        )

        val a11yEnabled = isAccessibilityServiceEnabled()
        binding.txtAccessibilityStatus.text = if (a11yEnabled) "有効" else "無効"
        binding.txtAccessibilityStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11yEnabled) R.color.status_ok else R.color.status_warn
            )
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (NetflixAdShieldService.isServiceRunning) return true
        val expected = ComponentName(this, NetflixAdShieldService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
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

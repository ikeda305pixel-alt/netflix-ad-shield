package com.antigravity.netflixadshield.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.antigravity.netflixadshield.R

/**
 * Manages:
 * 1) Full-screen pitch-black power-saving ad cover with centered "広告ミュート中" and 1-tap force-dismiss button.
 *    Uses FLAG_NOT_FOCUSABLE so Netflix continues playing the ad underneath without pausing.
 * 2) Optional compact floating "ログ採取" pill at top-right when Diagnostic Mode is enabled.
 */
class OverlayCoverManager(context: Context) {

    private val themedContext = ContextThemeWrapper(context.applicationContext, R.style.Theme_NetflixAdShield)
    private val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var coverView: View? = null
    private var timerTextView: TextView? = null
    private var signalTextView: TextView? = null

    private var diagnosticPillView: View? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(themedContext)

    fun showCover(signal: String?, onForceDismiss: () -> Unit) {
        if (!canDrawOverlays()) return
        if (coverView != null) {
            updateCoverStatus(0L, signal)
            return
        }

        val root = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
        }

        val title = TextView(themedContext).apply {
            text = themedContext.getString(R.string.cover_title_muted)
            setTextColor(Color.parseColor("#B0B0B0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }

        val timer = TextView(themedContext).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#6E6E6E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }

        val signalCaption = TextView(themedContext).apply {
            text = formatSignal(signal)
            setTextColor(Color.parseColor("#8A8A8A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        }

        val dismissBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1A1A1A"))
            setStroke(dp(1), Color.parseColor("#4D4D4D"))
            cornerRadius = dp(28).toFloat()
        }

        val dismissButton = Button(themedContext).apply {
            text = themedContext.getString(R.string.cover_force_dismiss)
            setTextColor(Color.parseColor("#D8D8D8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = dismissBg
            minWidth = dp(196)
            minHeight = dp(56)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isAllCaps = false
            setOnClickListener {
                onForceDismiss()
            }
        }

        root.addView(title)
        root.addView(timer)
        root.addView(signalCaption)
        root.addView(dismissButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(root, params)
            coverView = root
            timerTextView = timer
            signalTextView = signalCaption
        } catch (_: Exception) {
            coverView = null
            timerTextView = null
            signalTextView = null
        }
    }

    fun updateCoverStatus(elapsedMs: Long, signal: String?) {
        val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        timerTextView?.text = String.format("%02d:%02d", min, sec)
        if (!signal.isNullOrBlank()) {
            signalTextView?.text = formatSignal(signal)
        }
    }

    fun hideCover() {
        val view = coverView ?: return
        coverView = null
        timerTextView = null
        signalTextView = null
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        } catch (_: Exception) {
            // Already removed
        }
    }

    fun showDiagnosticPill(onCaptureClick: () -> Unit) {
        if (!canDrawOverlays()) return
        if (diagnosticPillView != null) return

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC1F2937"))
            setStroke(dp(1), Color.parseColor("#60A5FA"))
            cornerRadius = dp(24).toFloat()
        }

        val pill = Button(themedContext).apply {
            text = themedContext.getString(R.string.diag_pill_label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = bg
            minHeight = dp(48)
            minWidth = dp(96)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isAllCaps = false
            setOnClickListener { onCaptureClick() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(44)
        }

        try {
            windowManager.addView(pill, params)
            diagnosticPillView = pill
        } catch (_: Exception) {
            diagnosticPillView = null
        }
    }

    fun hideDiagnosticPill() {
        val view = diagnosticPillView ?: return
        diagnosticPillView = null
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        } catch (_: Exception) {
            // Already removed
        }
    }

    fun hideAll() {
        hideCover()
        hideDiagnosticPill()
    }

    private fun formatSignal(signal: String?): String {
        return if (signal.isNullOrBlank()) "" else signal.take(48)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            themedContext.resources.displayMetrics
        ).toInt()
}

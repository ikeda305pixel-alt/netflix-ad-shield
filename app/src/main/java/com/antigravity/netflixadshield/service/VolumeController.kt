package com.antigravity.netflixadshield.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Safely mutes STREAM_MUSIC during ads and restores the exact prior volume on release.
 * Never overwrites savedVolume if mute() is invoked repeatedly while already muted.
 */
class VolumeController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var isMutedByShield: Boolean = false
    private var savedVolume: Int? = null
    private var wasAlreadyMuted: Boolean = false

    fun mute() {
        if (isMutedByShield) return
        try {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val alreadyMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC) || currentVol == 0
            savedVolume = currentVol
            wasAlreadyMuted = alreadyMuted
            isMutedByShield = true

            if (!alreadyMuted) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        } catch (_: SecurityException) {
            // Do Not Disturb or OEM policy restriction - fail safely without crashing
            isMutedByShield = false
        }
    }

    fun restore() {
        if (!isMutedByShield) return
        val volToRestore = savedVolume
        val priorMuted = wasAlreadyMuted
        isMutedByShield = false
        savedVolume = null
        wasAlreadyMuted = false

        if (priorMuted || volToRestore == null || volToRestore <= 0) return

        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = volToRestore.coerceIn(0, maxVol)
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // Fail safely
        }
    }

    fun vibrateGentle() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
            // Ignore devices without vibrator or restricted vibration
        }
    }
}

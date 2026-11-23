package com.antigravity.netflixadshield.data

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.netflixadshield.core.AdRuleConfig

class ShieldPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isMasterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    var autoMuteEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MUTE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MUTE, value).apply()

    var screenCoverEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_COVER, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_COVER, value).apply()

    var vibrateOnEndEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON_END, false)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE_ON_END, value).apply()

    var maxShieldSeconds: Int
        get() = prefs.getInt(KEY_MAX_SHIELD_SEC, 90).coerceIn(20, 300)
        set(value) = prefs.edit().putInt(KEY_MAX_SHIELD_SEC, value.coerceIn(20, 300)).apply()

    var diagnosticModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTIC_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DIAGNOSTIC_MODE, value).apply()

    var customKeywordsRaw: String
        get() = prefs.getString(KEY_CUSTOM_KEYWORDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_KEYWORDS, value).apply()

    var customResourceIdsRaw: String
        get() = prefs.getString(KEY_CUSTOM_RES_IDS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_RES_IDS, value).apply()

    fun toRuleConfig(): AdRuleConfig {
        val keywords = customKeywordsRaw.split(",", "\n", "、")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val resIds = customResourceIdsRaw.split(",", "\n", "、")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return AdRuleConfig(customKeywords = keywords, customResourceIds = resIds)
    }

    companion object {
        private const val PREFS_NAME = "netflix_ad_shield_prefs"
        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val KEY_AUTO_MUTE = "auto_mute"
        private const val KEY_SCREEN_COVER = "screen_cover"
        private const val KEY_VIBRATE_ON_END = "vibrate_on_end"
        private const val KEY_MAX_SHIELD_SEC = "max_shield_sec"
        private const val KEY_DIAGNOSTIC_MODE = "diagnostic_mode"
        private const val KEY_CUSTOM_KEYWORDS = "custom_keywords"
        private const val KEY_CUSTOM_RES_IDS = "custom_res_ids"
    }
}

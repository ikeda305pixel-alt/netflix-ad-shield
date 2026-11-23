package com.antigravity.netflixadshield.service

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

data class UpdatedAccessibilitySettings(
    val enabledServicesValue: String,
    val accessibilityEnabledInt: Int,
    val changed: Boolean
)

sealed class AccessibilityToggleResult {
    data class Success(val enabled: Boolean, val changed: Boolean) : AccessibilityToggleResult()
    data class PermissionDenied(val error: SecurityException) : AccessibilityToggleResult()
    data class Failure(val error: Throwable) : AccessibilityToggleResult()
}

/**
 * Safely toggles ONLY NetflixAdShieldService inside Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
 * using WRITE_SECURE_SETTINGS without disturbing any other apps' accessibility services.
 */
object AccessibilityToggleHelper {

    private const val TAG = "AccessibilityToggle"

    @Volatile
    var lastPermissionError: String? = null
        private set

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appContext = context.applicationContext
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName
            )
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.PACKAGE_USAGE_STATS
            ) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    fun getTargetComponent(context: Context): ComponentName {
        return ComponentName(context.applicationContext, NetflixAdShieldService::class.java)
    }

    fun isOurServiceEnabled(context: Context): Boolean {
        val resolver = context.applicationContext.contentResolver
        val accessibilityEnabled = Settings.Secure.getInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!accessibilityEnabled) return false

        val raw = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val component = getTargetComponent(context)
        return containsTargetService(
            currentRaw = raw,
            fullComponent = component.flattenToString(),
            shortComponent = component.flattenToShortString()
        )
    }

    fun setServiceEnabled(context: Context, enable: Boolean): AccessibilityToggleResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val component = getTargetComponent(appContext)
        val fullComponent = component.flattenToString()
        val shortComponent = component.flattenToShortString()

        return try {
            val currentRaw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val currentEnabledInt = Settings.Secure.getInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            val updated = computeUpdatedSettings(
                currentRaw = currentRaw,
                fullComponent = fullComponent,
                shortComponent = shortComponent,
                enable = enable
            )

            val needsWrite = updated.changed || currentEnabledInt != updated.accessibilityEnabledInt
            if (needsWrite) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated.enabledServicesValue
                )
                Settings.Secure.putInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    updated.accessibilityEnabledInt
                )
                Log.i(
                    TAG,
                    "Accessibility toggled enable=$enable accessibilityEnabled=${updated.accessibilityEnabledInt}"
                )
            }
            lastPermissionError = null
            AccessibilityToggleResult.Success(enabled = enable, changed = needsWrite)
        } catch (se: SecurityException) {
            Log.e(
                TAG,
                "WRITE_SECURE_SETTINGS permission missing. Run setup_paypay_safe.ps1 via ADB.",
                se
            )
            lastPermissionError = "ADB権限未付与 (WRITE_SECURE_SETTINGS)"
            AccessibilityToggleResult.PermissionDenied(se)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update accessibility settings", t)
            AccessibilityToggleResult.Failure(t)
        }
    }

    internal fun containsTargetService(
        currentRaw: String?,
        fullComponent: String,
        shortComponent: String
    ): Boolean {
        if (currentRaw.isNullOrBlank()) return false
        return currentRaw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { isSameComponent(it, fullComponent, shortComponent) }
    }

    internal fun computeUpdatedSettings(
        currentRaw: String?,
        fullComponent: String,
        shortComponent: String,
        enable: Boolean
    ): UpdatedAccessibilitySettings {
        val originalList = currentRaw
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val services: MutableSet<String> = LinkedHashSet(originalList)
        val alreadyPresent = services.any { isSameComponent(it, fullComponent, shortComponent) }

        val changed: Boolean = if (enable) {
            if (!alreadyPresent) {
                services.add(fullComponent)
                true
            } else {
                false
            }
        } else {
            val removed = services.removeAll { isSameComponent(it, fullComponent, shortComponent) }
            removed
        }

        val joined = services.joinToString(":")
        val accessibilityEnabledInt = if (services.isEmpty()) 0 else 1
        return UpdatedAccessibilitySettings(
            enabledServicesValue = joined,
            accessibilityEnabledInt = accessibilityEnabledInt,
            changed = changed
        )
    }

    private fun isSameComponent(
        candidate: String,
        fullComponent: String,
        shortComponent: String
    ): Boolean {
        return candidate.equals(fullComponent, ignoreCase = true) ||
            candidate.equals(shortComponent, ignoreCase = true)
    }
}

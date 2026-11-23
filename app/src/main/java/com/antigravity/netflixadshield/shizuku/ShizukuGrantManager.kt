package com.antigravity.netflixadshield.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.antigravity.netflixadshield.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

enum class ShizukuAvailability {
    NOT_INSTALLED,
    NOT_RUNNING,
    NEEDS_PERMISSION,
    READY
}

sealed class ShizukuGrantOutcome {
    data object Granted : ShizukuGrantOutcome()
    data object OpenedShizukuApp : ShizukuGrantOutcome()
    data object OpenedPlayStore : ShizukuGrantOutcome()
    data object RequestedPermission : ShizukuGrantOutcome()
    data class Failed(val message: String) : ShizukuGrantOutcome()
}

/**
 * Manages Shizuku lifecycle listeners, permission requests, and one-shot UserService binding
 * to grant WRITE_SECURE_SETTINGS + GET_USAGE_STATS in a single tap without a PC or terminal.
 */
class ShizukuGrantManager(
    private val context: Context,
    private val onStateChanged: () -> Unit,
    private val onOutcome: (ShizukuGrantOutcome) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor()

    private var pendingGrantAfterPermission: Boolean = false
    private var isGrantInProgress: Boolean = false
    private var activeConnection: ServiceConnection? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post { onStateChanged() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post { onStateChanged() }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE_SHIZUKU) return@OnRequestPermissionResultListener
            mainHandler.post {
                onStateChanged()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    if (pendingGrantAfterPermission) {
                        pendingGrantAfterPermission = false
                        executeGrantViaUserService()
                    }
                } else {
                    pendingGrantAfterPermission = false
                    onOutcome(ShizukuGrantOutcome.Failed("Shizukuの権限リクエストが拒否されました"))
                }
            }
        }

    fun register() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun unregister() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        unbindActiveServiceQuietly()
        workerExecutor.shutdownNow()
    }

    fun getAvailability(): ShizukuAvailability {
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        if (!binderAlive) {
            return if (isShizukuInstalled()) {
                ShizukuAvailability.NOT_RUNNING
            } else {
                ShizukuAvailability.NOT_INSTALLED
            }
        }

        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return if (granted) ShizukuAvailability.READY else ShizukuAvailability.NEEDS_PERMISSION
    }

    fun triggerOneTapGrant(activityContext: Context) {
        if (isGrantInProgress) return

        when (getAvailability()) {
            ShizukuAvailability.NOT_INSTALLED -> {
                openShizukuInstallPage(activityContext)
                onOutcome(ShizukuGrantOutcome.OpenedPlayStore)
            }
            ShizukuAvailability.NOT_RUNNING -> {
                val launchIntent = activityContext.packageManager
                    .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activityContext.startActivity(launchIntent)
                    onOutcome(ShizukuGrantOutcome.OpenedShizukuApp)
                } else {
                    openShizukuInstallPage(activityContext)
                    onOutcome(ShizukuGrantOutcome.OpenedPlayStore)
                }
            }
            ShizukuAvailability.NEEDS_PERMISSION -> {
                try {
                    if (Shizuku.isPreV11()) {
                        onOutcome(ShizukuGrantOutcome.Failed("Shizuku v11以降へアップデートしてください"))
                        return
                    }
                    pendingGrantAfterPermission = true
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
                    onOutcome(ShizukuGrantOutcome.RequestedPermission)
                } catch (t: Throwable) {
                    pendingGrantAfterPermission = false
                    Log.e(TAG, "Failed requesting Shizuku permission", t)
                    onOutcome(ShizukuGrantOutcome.Failed("Shizuku権限要求に失敗: ${t.message}"))
                }
            }
            ShizukuAvailability.READY -> {
                executeGrantViaUserService()
            }
        }
    }

    private fun executeGrantViaUserService() {
        if (isGrantInProgress) return
        isGrantInProgress = true

        val userServiceArgs = buildUserServiceArgs()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || !service.pingBinder()) {
                    finishGrant(ShizukuGrantOutcome.Failed("Shizukuサービスへの接続に失敗しました"))
                    return
                }
                val granter = IShizukuPermissionGranter.Stub.asInterface(service)
                workerExecutor.execute {
                    val outcome = try {
                        val response = granter.grantPayPaySafePermissions(appContext.packageName)
                        if (response == "OK") {
                            ShizukuGrantOutcome.Granted
                        } else {
                            ShizukuGrantOutcome.Failed("権限付与エラー: $response")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Shizuku grant RPC failed", t)
                        ShizukuGrantOutcome.Failed("Shizuku実行エラー: ${t.message}")
                    } finally {
                        try {
                            Shizuku.unbindUserService(userServiceArgs, this, true)
                        } catch (_: Throwable) {
                            // Best-effort cleanup
                        }
                    }
                    finishGrant(outcome)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (isGrantInProgress) {
                    finishGrant(ShizukuGrantOutcome.Failed("Shizukuサービスが切断されました"))
                }
            }
        }

        activeConnection = conn
        try {
            Shizuku.bindUserService(userServiceArgs, conn)
        } catch (t: Throwable) {
            Log.e(TAG, "bindUserService failed", t)
            finishGrant(ShizukuGrantOutcome.Failed("Shizukuサービス起動エラー: ${t.message}"))
        }
    }

    private fun finishGrant(outcome: ShizukuGrantOutcome) {
        isGrantInProgress = false
        activeConnection = null
        mainHandler.post {
            onStateChanged()
            onOutcome(outcome)
        }
    }

    private fun buildUserServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShizukuPermissionGranterService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_granter")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private fun unbindActiveServiceQuietly() {
        val conn = activeConnection ?: return
        activeConnection = null
        isGrantInProgress = false
        try {
            Shizuku.unbindUserService(buildUserServiceArgs(), conn, true)
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openShizukuInstallPage(activityContext: Context) {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$SHIZUKU_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activityContext.startActivity(marketIntent)
        } catch (_: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activityContext.startActivity(webIntent)
        }
    }

    companion object {
        private const val TAG = "ShizukuGrantManager"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE_SHIZUKU = 4102
    }
}

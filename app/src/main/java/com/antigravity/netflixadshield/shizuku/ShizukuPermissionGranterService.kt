package com.antigravity.netflixadshield.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService executed in the privileged shell (UID 2000) process.
 * Grants ONLY WRITE_SECURE_SETTINGS and GET_USAGE_STATS to Netflix Ad Shield itself.
 */
@Keep
class ShizukuPermissionGranterService : IShizukuPermissionGranter.Stub {

    @Suppress("unused")
    constructor() : super()

    @Suppress("unused", "UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun grantPayPaySafePermissions(packageName: String?): String {
        val target = packageName?.trim().orEmpty()
        if (!ShizukuGrantCommandSpec.isAllowedPackage(target)) {
            return "ERROR: unauthorized package"
        }

        val errors = mutableListOf<String>()
        val commands = ShizukuGrantCommandSpec.buildGrantCommands(target)

        for (cmd in commands) {
            try {
                val process = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText().trim()
                }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    errors.add("${cmd.first()} timed out")
                } else if (process.exitValue() != 0) {
                    errors.add("${cmd.joinToString(" ")} -> exit=${process.exitValue()} $output".trim())
                }
            } catch (t: Throwable) {
                errors.add("${cmd.first()} exception: ${t.message}")
            }
        }

        return ShizukuGrantCommandSpec.formatResult(errors)
    }
}

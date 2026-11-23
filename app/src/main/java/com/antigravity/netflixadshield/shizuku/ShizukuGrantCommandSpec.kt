package com.antigravity.netflixadshield.shizuku

/**
 * Pure Kotlin validation & command specification for Shizuku-based self-permission granting.
 * Strictly restricted to this app's own package name.
 */
object ShizukuGrantCommandSpec {

    const val OWN_PACKAGE_NAME = "com.antigravity.netflixadshield"

    fun isAllowedPackage(packageName: String): Boolean {
        return packageName == OWN_PACKAGE_NAME
    }

    fun buildGrantCommands(packageName: String): List<List<String>> {
        require(isAllowedPackage(packageName)) { "Unauthorized package: $packageName" }
        return listOf(
            listOf("pm", "grant", packageName, "android.permission.WRITE_SECURE_SETTINGS"),
            listOf("appops", "set", packageName, "GET_USAGE_STATS", "allow")
        )
    }

    fun formatResult(errors: List<String>): String {
        return if (errors.isEmpty()) {
            "OK"
        } else {
            "ERROR: ${errors.joinToString(" | ")}"
        }
    }
}

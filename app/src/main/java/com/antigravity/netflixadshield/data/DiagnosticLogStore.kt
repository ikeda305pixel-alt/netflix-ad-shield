package com.antigravity.netflixadshield.data

import android.content.Context
import com.antigravity.netflixadshield.core.AdDetectionResult
import com.antigravity.netflixadshield.core.UiNodeSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists on-device Netflix Accessibility UI tree snapshots so users can inspect
 * and copy real ad-screen node IDs/texts directly from the phone without ADB.
 */
class DiagnosticLogStore(context: Context) {

    private val logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)

    fun appendSnapshot(
        trigger: String,
        packageName: String,
        nodes: List<UiNodeSnapshot>,
        detection: AdDetectionResult
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("===== [$timestamp] trigger=$trigger pkg=$packageName =====")
        sb.appendLine("DETECTED=${detection.isAdDetected} reason=${detection.reason} signal=${detection.matchedSignal ?: "-"}")
        sb.appendLine("NODES=${nodes.size}")

        val meaningfulNodes = nodes.filter {
            !it.viewId.isNullOrBlank() || !it.text.isNullOrBlank() || !it.contentDesc.isNullOrBlank()
        }.take(80)

        for (node in meaningfulNodes) {
            val id = node.viewId?.substringAfter(":id/", node.viewId) ?: "-"
            val text = node.text?.replace("\n", " ")?.take(48) ?: "-"
            val desc = node.contentDesc?.replace("\n", " ")?.take(48) ?: "-"
            val cls = node.className?.substringAfterLast('.') ?: "-"
            sb.appendLine("  id=$id | cls=$cls | text=\"$text\" | desc=\"$desc\" | ${node.boundsSummary}")
        }
        sb.appendLine()

        synchronized(this) {
            val existing = if (logFile.exists()) logFile.readText(Charsets.UTF_8) else ""
            val combined = sb.toString() + existing
            val trimmed = if (combined.length > MAX_LOG_CHARS) {
                combined.substring(0, MAX_LOG_CHARS)
            } else {
                combined
            }
            logFile.writeText(trimmed, Charsets.UTF_8)
        }
    }

    fun readAll(): String = synchronized(this) {
        if (logFile.exists()) logFile.readText(Charsets.UTF_8) else ""
    }

    fun clear() = synchronized(this) {
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "diagnostic_snapshots.log"
        private const val MAX_LOG_CHARS = 32_000
    }
}

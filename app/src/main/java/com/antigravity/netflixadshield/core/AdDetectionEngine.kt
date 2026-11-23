package com.antigravity.netflixadshield.core

data class UiNodeSnapshot(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDesc: String?,
    val boundsSummary: String = ""
)

data class AdRuleConfig(
    val customKeywords: List<String> = emptyList(),
    val customResourceIds: List<String> = emptyList()
)

enum class MatchReason {
    NONE,
    RESOURCE_ID,
    BADGE_TEXT,
    CUSTOM_RESOURCE_ID,
    CUSTOM_KEYWORD
}

data class AdDetectionResult(
    val isAdDetected: Boolean,
    val reason: MatchReason,
    val matchedSignal: String?
)

/**
 * Pure Kotlin ad-state classifier over Accessibility UI node snapshots.
 * Designed to avoid false positives on movie subtitles, titles, or normal scrubbers.
 */
class AdDetectionEngine {

    companion object {
        private const val MAX_BADGE_TEXT_LENGTH = 28

        // Resource IDs strongly tied to ad badge / countdown / break indicators (never bare "ad")
        private val DEFAULT_AD_RESOURCE_ID_TOKENS = listOf(
            "ad_badge",
            "ad_countdown",
            "ad_timer",
            "ad_break",
            "ad_indicator",
            "ad_label",
            "ad_pill",
            "ad_callout",
            "ad_progress",
            "ad_remaining",
            "ads_badge",
            "ads_countdown",
            "player_ad_"
        )

        // Anchored Japanese ad badge formats (e.g. "広告", "広告 1/2", "広告・0:30", "広告 残り 25秒")
        private val JP_AD_BADGE_REGEX = Regex(
            """^広告(?:\s*[・•·:\-]\s*(?:\d+/\d+|\d+:\d+|\d+秒)|(?:\s+\d+\s*/\s*\d+)|(?:\s*\(\d+/\d+\))|(?:\s*残り\s*\d+(?::\d+|秒)))?$"""
        )

        // Anchored English ad badge formats (e.g. "Ad", "Ad 1 of 2", "Ad · 0:30", "Ads · 0:45")
        private val EN_AD_BADGE_REGEX = Regex(
            """^Ads?(?:\s*[・•·:\-]\s*(?:\d+\s*(?:of|/)\s*\d+|\d+:\d+)|(?:\s+\d+\s*(?:of|/)\s*\d+)|(?:\s*\(\d+\s*(?:of|/)\s*\d+\)))?$""",
            RegexOption.IGNORE_CASE
        )
    }

    fun evaluate(nodes: List<UiNodeSnapshot>, config: AdRuleConfig = AdRuleConfig()): AdDetectionResult {
        if (nodes.isEmpty()) {
            return AdDetectionResult(false, MatchReason.NONE, null)
        }

        val normalizedCustomIds = config.customResourceIds
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val normalizedCustomKeywords = config.customKeywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        for (node in nodes) {
            val viewIdLower = node.viewId?.trim()?.lowercase().orEmpty()
            if (viewIdLower.isNotEmpty()) {
                val matchedDefaultId = DEFAULT_AD_RESOURCE_ID_TOKENS.firstOrNull { viewIdLower.contains(it) }
                if (matchedDefaultId != null) {
                    return AdDetectionResult(true, MatchReason.RESOURCE_ID, node.viewId)
                }

                val matchedCustomId = normalizedCustomIds.firstOrNull { viewIdLower.contains(it) }
                if (matchedCustomId != null) {
                    return AdDetectionResult(true, MatchReason.CUSTOM_RESOURCE_ID, node.viewId)
                }
            }

            val candidates = listOfNotNull(
                node.text?.trim()?.takeIf { it.isNotEmpty() },
                node.contentDesc?.trim()?.takeIf { it.isNotEmpty() }
            )

            for (raw in candidates) {
                val normalized = raw.replace(Regex("\\s+"), " ")
                if (normalized.length <= MAX_BADGE_TEXT_LENGTH) {
                    if (JP_AD_BADGE_REGEX.matches(normalized) || EN_AD_BADGE_REGEX.matches(normalized)) {
                        return AdDetectionResult(true, MatchReason.BADGE_TEXT, normalized)
                    }
                }

                if (normalizedCustomKeywords.isNotEmpty() && normalized.length <= 64) {
                    val lower = normalized.lowercase()
                    val matchedCustom = normalizedCustomKeywords.firstOrNull { lower.contains(it) }
                    if (matchedCustom != null) {
                        return AdDetectionResult(true, MatchReason.CUSTOM_KEYWORD, normalized)
                    }
                }
            }
        }

        return AdDetectionResult(false, MatchReason.NONE, null)
    }
}

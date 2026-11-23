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
 * Supports both legacy XML resource IDs (snake_case) and Netflix Jetpack Compose testTags (camelCase)
 * plus TalkBack ad countdown descriptions, while avoiding false positives on subtitles or synopses.
 */
class AdDetectionEngine {

    companion object {
        private const val MAX_SHORT_BADGE_LENGTH = 28
        private const val MAX_TALKBACK_DESC_LENGTH = 56

        // Normalized (underscores/hyphens removed, lowercased) ad-specific resource ID tokens.
        // Matches both snake_case (ad_break_indicator) and Compose camelCase (adBreakProgressIndicator).
        // Intentionally excludes bare "ad" / "ads" to prevent false triggers on benign views or empty hosts.
        private val DEFAULT_AD_RESOURCE_ID_TOKENS = listOf(
            "adbadge",
            "adcountdown",
            "adtimer",
            "adbreak",
            "adindicator",
            "adlabel",
            "adpill",
            "adcallout",
            "adprogress",
            "adremaining",
            "adsbadge",
            "adscountdown",
            "playerad"
        )

        // Anchored short Japanese ad badge formats (e.g. "広告", "広告 1/2", "広告・0:30", "広告 残り 25秒")
        private val JP_AD_BADGE_REGEX = Regex(
            """^広告(?:\s*[・•·:\-]\s*(?:\d+/\d+|\d+:\d+|\d+秒)|(?:\s+\d+\s*/\s*\d+)|(?:\s*\(\d+/\d+\))|(?:\s*残り\s*\d+(?::\d+|秒)))?$"""
        )

        // Japanese TalkBack / Compose contentDescription (e.g. "2本中1本目の広告を再生中です。残り23秒です")
        private val JP_TALKBACK_AD_REGEX = Regex(
            """^(?:\d+本中\d+本目の)?広告を再生中です(?:[。．.\s]*残り\d+(?:分\d+秒|秒)です)?$"""
        )

        // Anchored short English ad badge formats (e.g. "Ad", "Ad 1 of 2", "Ad · 0:30", "Ads · 0:45")
        private val EN_AD_BADGE_REGEX = Regex(
            """^Ads?(?:\s*[・•·:\-]\s*(?:\d+\s*(?:of|/)\s*\d+|\d+:\d+)|(?:\s+\d+\s*(?:of|/)\s*\d+)|(?:\s*\(\d+\s*(?:of|/)\s*\d+\)))?$""",
            RegexOption.IGNORE_CASE
        )

        // English TalkBack / Compose contentDescription (e.g. "Playing ad 1 of 2. 23 seconds remaining")
        private val EN_TALKBACK_AD_REGEX = Regex(
            """^(?:playing\s+)?ad\s+\d+\s+of\s+\d+(?:[.,\s]+\d+\s+seconds?\s+(?:remaining|left))?$""",
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

        var resourceIdMatch: AdDetectionResult? = null
        var badgeTextMatch: AdDetectionResult? = null
        var customMatch: AdDetectionResult? = null

        for (node in nodes) {
            val rawViewId = node.viewId?.trim().orEmpty()
            if (rawViewId.isNotEmpty()) {
                val idPart = rawViewId.substringAfter(":id/", rawViewId).lowercase()
                val compactId = idPart.replace("_", "").replace("-", "")

                if (resourceIdMatch == null && DEFAULT_AD_RESOURCE_ID_TOKENS.any { compactId.contains(it) }) {
                    resourceIdMatch = AdDetectionResult(true, MatchReason.RESOURCE_ID, rawViewId)
                }

                if (customMatch == null && normalizedCustomIds.any { idPart.contains(it) || rawViewId.lowercase().contains(it) }) {
                    customMatch = AdDetectionResult(true, MatchReason.CUSTOM_RESOURCE_ID, rawViewId)
                }
            }

            val candidates = listOfNotNull(
                node.text?.trim()?.takeIf { it.isNotEmpty() },
                node.contentDesc?.trim()?.takeIf { it.isNotEmpty() }
            )

            for (raw in candidates) {
                val normalized = raw.replace(Regex("\\s+"), " ")
                if (badgeTextMatch == null && isAdBadgeOrTalkBack(normalized)) {
                    badgeTextMatch = AdDetectionResult(true, MatchReason.BADGE_TEXT, normalized)
                }

                if (customMatch == null && normalizedCustomKeywords.isNotEmpty() && normalized.length <= 64) {
                    val lower = normalized.lowercase()
                    if (normalizedCustomKeywords.any { lower.contains(it) }) {
                        customMatch = AdDetectionResult(true, MatchReason.CUSTOM_KEYWORD, normalized)
                    }
                }
            }
        }

        // Prefer returning human-readable countdown text when present (great for live cover display)
        if (resourceIdMatch != null && badgeTextMatch != null) {
            return AdDetectionResult(true, MatchReason.RESOURCE_ID, badgeTextMatch.matchedSignal)
        }
        return badgeTextMatch
            ?: resourceIdMatch
            ?: customMatch
            ?: AdDetectionResult(false, MatchReason.NONE, null)
    }

    private fun isAdBadgeOrTalkBack(normalized: String): Boolean {
        if (normalized.length <= MAX_SHORT_BADGE_LENGTH) {
            if (JP_AD_BADGE_REGEX.matches(normalized) || EN_AD_BADGE_REGEX.matches(normalized)) {
                return true
            }
        }
        if (normalized.length <= MAX_TALKBACK_DESC_LENGTH) {
            if (JP_TALKBACK_AD_REGEX.matches(normalized) || EN_TALKBACK_AD_REGEX.matches(normalized)) {
                return true
            }
        }
        return false
    }
}

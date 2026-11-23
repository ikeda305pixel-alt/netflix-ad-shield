package com.antigravity.netflixadshield

import com.antigravity.netflixadshield.core.AdDetectionEngine
import com.antigravity.netflixadshield.core.AdRuleConfig
import com.antigravity.netflixadshield.core.MatchReason
import com.antigravity.netflixadshield.core.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectionEngineTest {

    private val engine = AdDetectionEngine()
    private val defaultConfig = AdRuleConfig()

    @Test
    fun detectsJapaneseAdBadges() {
        val samples = listOf(
            "広告",
            "広告 1/2",
            "広告・0:30",
            "広告 • 0:15",
            "広告 (1/2)",
            "広告 残り 25秒",
            "広告: 0:45"
        )
        for (label in samples) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = null, className = "android.widget.TextView", text = label, contentDesc = null)),
                config = defaultConfig
            )
            assertTrue("Expected detection for '$label'", result.isAdDetected)
            assertEquals(MatchReason.BADGE_TEXT, result.reason)
        }
    }

    @Test
    fun detectsEnglishAdBadges() {
        val samples = listOf(
            "Ad",
            "Ad 1 of 2",
            "Ad · 0:30",
            "Ad • 0:15",
            "Ad (2 of 2)",
            "Ad 1/3",
            "Ads · 0:45"
        )
        for (label in samples) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = null, className = "android.widget.TextView", text = null, contentDesc = label)),
                config = defaultConfig
            )
            assertTrue("Expected detection for '$label'", result.isAdDetected)
            assertEquals(MatchReason.BADGE_TEXT, result.reason)
        }
    }

    @Test
    fun detectsKnownAdResourceIds() {
        val ids = listOf(
            "com.netflix.mediaclient:id/ad_badge",
            "com.netflix.mediaclient:id/ad_countdown_text",
            "com.netflix.mediaclient:id/ad_break_indicator",
            "com.netflix.mediaclient:id/player_ad_timer"
        )
        for (id in ids) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = id, className = "android.widget.TextView", text = "0:29", contentDesc = null)),
                config = defaultConfig
            )
            assertTrue("Expected detection for resourceId '$id'", result.isAdDetected)
            assertEquals(MatchReason.RESOURCE_ID, result.reason)
        }
    }

    @Test
    fun ignoresSubtitlesSynopsesAndNormalUiWords() {
        val falsePositives = listOf(
            "彼は大手広告代理店で働いている",
            "この番組は広告業界の裏側を描くドラマです。",
            "Adventure Time",
            "Add to My List",
            "Adjust Audio",
            "Audio & Subtitles",
            "42:15",
            "-12:30",
            "0:30",
            "残り 42分",
            "イントロをスキップ"
        )
        for (text in falsePositives) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = "com.netflix.mediaclient:id/subtitle_text", className = "android.widget.TextView", text = text, contentDesc = null)),
                config = defaultConfig
            )
            assertFalse("Must NOT detect false positive: '$text'", result.isAdDetected)
        }
    }

    @Test
    fun ignoresNonAdResourceIdsContainingSubstringAd() {
        val benignIds = listOf(
            "com.netflix.mediaclient:id/download_button",
            "com.netflix.mediaclient:id/header_title",
            "com.netflix.mediaclient:id/loading_spinner",
            "com.netflix.mediaclient:id/pad_container",
            "com.netflix.mediaclient:id/read_more"
        )
        for (id in benignIds) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = id, className = "android.view.View", text = "", contentDesc = null)),
                config = defaultConfig
            )
            assertFalse("Must NOT trigger on benign resourceId: '$id'", result.isAdDetected)
        }
    }

    @Test
    fun supportsCustomKeywordsAndResourceIds() {
        val customConfig = AdRuleConfig(
            customKeywords = listOf("スポンサー提供"),
            customResourceIds = listOf("promo_timer_view")
        )

        val keywordResult = engine.evaluate(
            nodes = listOf(UiNodeSnapshot(viewId = null, className = "android.widget.TextView", text = "スポンサー提供 0:15", contentDesc = null)),
            config = customConfig
        )
        assertTrue(keywordResult.isAdDetected)
        assertEquals(MatchReason.CUSTOM_KEYWORD, keywordResult.reason)

        val idResult = engine.evaluate(
            nodes = listOf(UiNodeSnapshot(viewId = "com.netflix.mediaclient:id/promo_timer_view", className = "android.widget.TextView", text = "15", contentDesc = null)),
            config = customConfig
        )
        assertTrue(idResult.isAdDetected)
        assertEquals(MatchReason.CUSTOM_RESOURCE_ID, idResult.reason)
    }

    @Test
    fun returnsFalseOnEmptyNodeList() {
        val result = engine.evaluate(emptyList(), defaultConfig)
        assertFalse(result.isAdDetected)
        assertEquals(MatchReason.NONE, result.reason)
    }

    @Test
    fun detectsRealNetflixComposeAdSnapshotAndExtractsCountdownLabel() {
        val realDeviceNodes = listOf(
            UiNodeSnapshot("com.netflix.mediaclient:id/2131427693", "androidx.compose.ui.platform.ComposeView", null, null),
            UiNodeSnapshot("Ads", "android.view.View", null, null),
            UiNodeSnapshot("playerTappableContainerTestTag", "android.view.View", null, "ビデオプレーヤー"),
            UiNodeSnapshot("playPauseButtonTestTag", "android.view.View", null, null),
            UiNodeSnapshot("adBreakProgressIndicator", "android.view.View", null, null),
            UiNodeSnapshot(null, "android.view.View", null, "2本中1本目の広告を再生中です。残り23秒です")
        )

        val result = engine.evaluate(realDeviceNodes, defaultConfig)
        assertTrue("Expected detection on real Netflix Compose ad snapshot", result.isAdDetected)
        assertEquals("2本中1本目の広告を再生中です。残り23秒です", result.matchedSignal)
    }

    @Test
    fun detectsJapaneseAndEnglishTalkBackAdDescriptionsWithoutResourceId() {
        val descriptions = listOf(
            "2本中1本目の広告を再生中です。残り27秒です",
            "広告を再生中です。残り15秒です",
            "Playing ad 1 of 2. 23 seconds remaining",
            "Ad 1 of 2, 15 seconds left"
        )
        for (desc in descriptions) {
            val result = engine.evaluate(
                nodes = listOf(UiNodeSnapshot(viewId = null, className = "android.view.View", text = null, contentDesc = desc)),
                config = defaultConfig
            )
            assertTrue("Expected detection for TalkBack desc '$desc'", result.isAdDetected)
            assertEquals(MatchReason.BADGE_TEXT, result.reason)
        }
    }

    @Test
    fun doesNotFalseTriggerOnBareAdsHostContainerAlone() {
        val nodes = listOf(
            UiNodeSnapshot("Ads", "android.view.View", null, null),
            UiNodeSnapshot("playerTappableContainerTestTag", "android.view.View", null, "ビデオプレーヤー")
        )
        val result = engine.evaluate(nodes, defaultConfig)
        assertFalse("Bare Ads host container without active ad indicator must not false-trigger", result.isAdDetected)
    }
}

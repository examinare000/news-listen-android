package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PodcastStatusBadge] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift:104-121（statusBadge）。
 * completed はバッジなし、processing は「生成中」、failed/partial_failed は「失敗」に統合する。
 */
class PodcastStatusBadgeTest {

    private fun podcast(status: String, errorMessage: String? = null): PodcastResponse = PodcastResponse(
        id = "1",
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/audio.mp3",
        japaneseIntroText = "intro",
        durationSeconds = 120,
        status = status,
        errorMessage = errorMessage,
        createdAt = "2026-07-01T00:00:00Z",
    )

    @Test
    fun completedはバッジなし() {
        assertEquals(PodcastStatusBadge.None, PodcastStatusBadge.from(podcast("completed")))
    }

    @Test
    fun processingは生成中バッジ() {
        assertEquals(PodcastStatusBadge.Processing, PodcastStatusBadge.from(podcast("processing")))
    }

    @Test
    fun failedは失敗バッジにerrorMessageを保持する() {
        assertEquals(
            PodcastStatusBadge.Failed("boom"),
            PodcastStatusBadge.from(podcast("failed", errorMessage = "boom")),
        )
    }

    @Test
    fun partial_failedもfailedと同じ失敗バッジに統合される() {
        assertEquals(
            PodcastStatusBadge.Failed(null),
            PodcastStatusBadge.from(podcast("partial_failed")),
        )
    }

    @Test
    fun 未知のstatusはバッジなしにフォールバックする() {
        assertEquals(PodcastStatusBadge.None, PodcastStatusBadge.from(podcast("unknown_status")))
    }
}

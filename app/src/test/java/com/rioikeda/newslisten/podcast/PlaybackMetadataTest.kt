package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PodcastResponse.displayTitle] / [PlaybackMetadata] の検証（フェーズ7 前半: 通知メタデータ）。
 *
 * 正本:
 * - displayTitle 優先順位: ios/NewsListenApp/NewsListenApp/Models/Podcast.swift:107-112（displayTitle）
 * - artist（難易度ラベル）: ios/NewsListenApp/NewsListenApp/Podcast/NowPlayingInfo.swift:36-37
 */
class PlaybackMetadataTest {

    private fun podcast(
        title: String = "",
        japaneseIntroText: String = "",
        difficulty: String = "toeic_600",
    ): PodcastResponse = PodcastResponse(
        id = "p1",
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = difficulty,
        audioUrl = "https://example.com/p1.mp3",
        japaneseIntroText = japaneseIntroText,
        durationSeconds = 300,
        status = "completed",
        title = title,
        createdAt = "2026-07-01T00:00:00Z",
    )

    // --- displayTitle: 3段フォールバック（title → japaneseIntroText → 既定文言） ---

    @Test
    fun displayTitleはtitleが非空ならtrim済みtitleを返す() {
        val podcast = podcast(title = "  特集：AI最新動向  ", japaneseIntroText = "本日のニュースです。")

        assertEquals("特集：AI最新動向", podcast.displayTitle)
    }

    @Test
    fun displayTitleはtitleが空でjapaneseIntroTextが非空ならtrim済みintroを返す() {
        val podcast = podcast(title = "  ", japaneseIntroText = "  本日のニュースです。  ")

        assertEquals("本日のニュースです。", podcast.displayTitle)
    }

    @Test
    fun displayTitleは両方空なら既定文言を返す() {
        val podcast = podcast(title = "", japaneseIntroText = "  ")

        assertEquals("ニュースポッドキャスト", podcast.displayTitle)
    }

    // --- toPlaybackMetadata: title=displayTitle, artist=難易度ラベル ---

    @Test
    fun toPlaybackMetadataは既知の難易度コードをラベルに変換する() {
        val podcast = podcast(title = "特集：AI最新動向", difficulty = "toeic_900")

        val metadata = podcast.toPlaybackMetadata()

        assertEquals(PlaybackMetadata(title = "特集：AI最新動向", artist = "TOEIC 730-900"), metadata)
    }

    @Test
    fun toPlaybackMetadataは未知の難易度コードをそのまま返す() {
        val podcast = podcast(title = "特集：AI最新動向", difficulty = "unknown_code")

        val metadata = podcast.toPlaybackMetadata()

        assertEquals(PlaybackMetadata(title = "特集：AI最新動向", artist = "unknown_code"), metadata)
    }
}

package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PodcastResponse / TranscriptSegment / PodcastListResponse の検証。
 *
 * 正本:
 * - PodcastResponse: backend/api/schemas.py:47-62
 * - TranscriptSegment: backend/shared/models.py:164-172
 * - PodcastListResponse: backend/api/schemas.py:98-100
 *
 * WHY(ADR-059): segments は既存 Podcast ドキュメントには無いため default=None で後方互換。
 * title は default=""。error_message は default=None。playback_position_seconds は default=0.0。
 */
class PodcastDecodingTest {

    private fun requiredFieldsJson() = """
        {
          "id": "p1",
          "type": "single",
          "article_ids": ["a1"],
          "difficulty": "toeic_600",
          "audio_url": "https://example.com/p1.mp3",
          "japanese_intro_text": "本日のニュースです。",
          "duration_seconds": 120,
          "status": "completed",
          "created_at": "2026-07-01T09:00:00+00:00"
        }
    """.trimIndent()

    @Test
    fun PodcastResponseは全フィールド指定時にデコードできる() {
        val json = """
            {
              "id": "p1",
              "type": "single",
              "article_ids": ["a1", "a2"],
              "difficulty": "toeic_900",
              "audio_url": "https://example.com/p1.mp3",
              "japanese_intro_text": "本日のニュースです。",
              "duration_seconds": 300,
              "status": "completed",
              "error_message": null,
              "playback_position_seconds": 42.5,
              "title": "特集：AI最新動向",
              "segments": [
                {"speaker": "A", "text": "Hello"},
                {"speaker": "B", "text": "Hi there"}
              ],
              "created_at": "2026-07-01T09:00:00+00:00"
            }
        """.trimIndent()

        val podcast = NewsListenJson.decodeFromString(PodcastResponse.serializer(), json)

        assertEquals("p1", podcast.id)
        assertEquals("single", podcast.type)
        assertEquals(listOf("a1", "a2"), podcast.articleIds)
        assertEquals("toeic_900", podcast.difficulty)
        assertEquals("https://example.com/p1.mp3", podcast.audioUrl)
        assertEquals("本日のニュースです。", podcast.japaneseIntroText)
        assertEquals(300, podcast.durationSeconds)
        assertEquals("completed", podcast.status)
        assertEquals(42.5, podcast.playbackPositionSeconds, 0.0001)
        assertEquals("特集：AI最新動向", podcast.title)
        assertEquals(2, podcast.segments?.size)
        assertEquals("A", podcast.segments?.get(0)?.speaker)
        assertEquals("Hello", podcast.segments?.get(0)?.text)
        assertEquals("2026-07-01T09:00:00+00:00", podcast.createdAt)
    }

    @Test
    fun PodcastResponseはoptionalフィールド欠落時にデフォルト値へフォールバックする() {
        val podcast = NewsListenJson.decodeFromString(PodcastResponse.serializer(), requiredFieldsJson())

        assertEquals("", podcast.title)
        assertEquals(0.0, podcast.playbackPositionSeconds, 0.0001)
        assertNull(podcast.errorMessage)
        assertNull(podcast.segments)
    }

    @Test
    fun PodcastResponseは未知フィールドを無視して前方互換を保つ() {
        val json = """
            {
              "id": "p1",
              "type": "single",
              "article_ids": ["a1"],
              "difficulty": "toeic_600",
              "audio_url": "https://example.com/p1.mp3",
              "japanese_intro_text": "本日のニュースです。",
              "duration_seconds": 120,
              "status": "completed",
              "created_at": "2026-07-01T09:00:00+00:00",
              "future_field": "unknown"
            }
        """.trimIndent()

        val podcast = NewsListenJson.decodeFromString(PodcastResponse.serializer(), json)

        assertEquals("p1", podcast.id)
    }

    @Test
    fun PodcastListResponseはpodcastsのリストをデコードできる() {
        val json = """
            {
              "podcasts": [${requiredFieldsJson()}]
            }
        """.trimIndent()

        val list = NewsListenJson.decodeFromString(PodcastListResponse.serializer(), json)

        assertEquals(1, list.podcasts.size)
        assertTrue(list.podcasts[0].id == "p1")
    }
}

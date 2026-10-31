package com.rioikeda.newslisten.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    // ──────────────────────────────────────────────
    // ADR-094 第一段階（Issue #237）: TranscriptSegment.role
    //
    // WHY: 正本 backend/shared/models.py の TranscriptSegment.role（fact|commentary|null）に
    // 合わせ、Android 側にも省略可フィールドとして role を追加済み。以下は present/absent/null
    // の 3 経路のデコード契約を固定する回帰テスト群。
    // ──────────────────────────────────────────────

    @Test
    fun TranscriptSegmentはroleキーがあればデコードできる() {
        val json = """
            {"speaker": "A", "text": "Rust is fast.", "role": "fact"}
        """.trimIndent()

        val segment = NewsListenJson.decodeFromString(TranscriptSegment.serializer(), json)

        assertEquals("fact", segment.role)
    }

    @Test
    fun TranscriptSegmentはroleキーが無ければnullにフォールバックする() {
        val json = """
            {"speaker": "A", "text": "Rust is fast."}
        """.trimIndent()

        val segment = NewsListenJson.decodeFromString(TranscriptSegment.serializer(), json)

        assertNull(segment.role)
    }

    @Test
    fun TranscriptSegmentはroleがnullでもデコードできる() {
        val json = """
            {"speaker": "A", "text": "Rust is fast.", "role": null}
        """.trimIndent()

        val segment = NewsListenJson.decodeFromString(TranscriptSegment.serializer(), json)

        assertNull(segment.role)
    }

    // ──────────────────────────────────────────────
    // android-dec-16: 出典の 2 field（source_kind / source_articles）を足しても、
    // 旧 client が受理した入力は新 client も受理し、既存 15 field は同じ値になる。
    // ──────────────────────────────────────────────

    /** 既存の 15 field をすべて埋めた JSON。[extra] は `created_at` の後ろへ足す JSON の断片。 */
    private fun fifteenFieldsJson(extra: String? = null): String {
        val tail = if (extra == null) "" else ",\n  $extra"
        return """
            {
              "id": "p1",
              "type": "digest",
              "article_ids": ["a1", "a2"],
              "difficulty": "toeic_900",
              "audio_url": "https://example.com/p1.mp3",
              "japanese_intro_text": "本日のニュースです。",
              "duration_seconds": 300,
              "status": "completed",
              "error_message": "boom",
              "playback_position_seconds": 42.5,
              "title": "特集",
              "segments": [{"speaker": "A", "text": "Hello", "role": "fact"}],
              "vocabulary": [{"term": "t", "meaning_ja": "意味", "example": "e"}],
              "quiz": [{"question": "q", "options": ["x", "y"]}],
              "created_at": "2026-07-01T09:00:00+00:00"$tail
            }
        """.trimIndent()
    }

    private fun decodePodcast(json: String) = NewsListenJson.decodeFromString(PodcastResponse.serializer(), json)

    /** 足した 2 field を null に戻して比べると、既存 15 field が同値かどうかだけが残る。 */
    private fun withoutSourceFields(podcast: PodcastResponse) = podcast.copy(sourceKind = null, sourceArticles = null)

    @Test
    fun `android-dec-16 出典のkeyが無い旧JSONは2fieldがnullでデコードされ既存15fieldは変わらない`() {
        // verifies: CI-M1, CI-M7 / T-M1
        val podcast = decodePodcast(fifteenFieldsJson())

        assertNull(podcast.sourceKind)
        assertNull(podcast.sourceArticles)
        assertEquals("p1", podcast.id)
        assertEquals(listOf("a1", "a2"), podcast.articleIds)
        assertEquals("boom", podcast.errorMessage)
        assertEquals(42.5, podcast.playbackPositionSeconds, 0.0001)
        assertEquals("fact", podcast.segments?.single()?.role)
        assertEquals(listOf("x", "y"), podcast.quiz?.single()?.options)
    }

    @Test
    fun `android-dec-16 出典の2fieldが両方nullでもデコードでき既存15fieldは出典keyが無い場合と同値`() {
        // verifies: CI-M2, CI-M7 / T-M2
        val baseline = decodePodcast(fifteenFieldsJson())

        val podcast = decodePodcast(fifteenFieldsJson("\"source_kind\": null,\n  \"source_articles\": null"))

        assertNull(podcast.sourceKind)
        assertNull(podcast.sourceArticles)
        assertEquals(baseline, withoutSourceFields(podcast))
    }

    @Test
    fun `android-dec-16 未知のsource_kindの文字列はそのまま保持されデコードに成功する`() {
        // verifies: CI-M3, CI-M7 / T-M3
        val baseline = decodePodcast(fifteenFieldsJson())

        val podcast = decodePodcast(fifteenFieldsJson("\"source_kind\": \"weird\""))

        assertEquals("weird", podcast.sourceKind)
        assertEquals(baseline, withoutSourceFields(podcast))
    }

    @Test
    fun `android-dec-16 source_articlesの要素でfieldが欠けても欠けたfieldだけnullでデコードに成功する`() {
        // verifies: CI-M4, CI-M7 / T-M4
        val baseline = decodePodcast(fifteenFieldsJson())

        val podcast = decodePodcast(
            fifteenFieldsJson(
                "\"source_articles\": [{\"article_id\": \"art-1\", \"title\": \"Title 1\", \"source\": \"Source 1\"}]",
            ),
        )

        val article = podcast.sourceArticles!!.single()
        assertEquals("art-1", article.articleId)
        assertEquals("Title 1", article.title)
        assertNull("欠けた url は null", article.url)
        assertEquals("Source 1", article.source)
        assertEquals(baseline, withoutSourceFields(podcast))
    }

    @Test
    fun `android-dec-16 source_kindとsource_articlesに値があればそのまま読める`() {
        // verifies: CI-M5 / T-M5
        val podcast = decodePodcast(
            fifteenFieldsJson(
                """
                "source_kind": "featured",
                  "source_articles": [
                    {"article_id": "art-1", "title": "Title 1", "url": "https://example.com/1", "source": "Source 1"},
                    {"article_id": "art-2", "title": "Title 2", "url": "https://example.com/2", "source": "Source 2"}
                  ]
                """.trimIndent(),
            ),
        )

        assertEquals("featured", podcast.sourceKind)
        assertEquals(listOf("art-1", "art-2"), podcast.sourceArticles?.map { it.articleId })
        assertEquals(listOf("Title 1", "Title 2"), podcast.sourceArticles?.map { it.title })
        assertEquals(listOf("https://example.com/1", "https://example.com/2"), podcast.sourceArticles?.map { it.url })
        assertEquals(listOf("Source 1", "Source 2"), podcast.sourceArticles?.map { it.source })
    }

    @Test
    fun `android-dec-16 source_articlesがlistでないJSONはデコードに失敗する_受け入れた差として固定`() {
        // verifies: CI-M6 / T-M6
        assertThrows(SerializationException::class.java) {
            decodePodcast(fifteenFieldsJson("\"source_articles\": \"x\""))
        }
    }
}

package com.rioikeda.newslisten.catalog

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeKind
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceArticle
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceKind
import com.rioikeda.newslisten.catalog.domain.EpisodeStatus
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import com.rioikeda.newslisten.core.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Episode` の判別（fail-closed）・値の保持・`displayTitle` の検査。
 *
 * 判別の表は「判別を行う関数を引数に取る検証関数」に切り出し、本物の生成関数と
 * 「未知の status を Playable に倒す stub」の両方で呼ぶ（stub では失敗することで表の弁別力を固定する）。
 */
class EpisodeTest {

    private val url = "https://example.com/audio.mp3"

    private fun create(
        status: EpisodeStatus,
        audioUrl: String = url,
        errorMessage: String? = null,
        title: String = "題名",
        japaneseIntroText: String = "イントロ",
    ): Episode = Episode.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        status = status,
        audioUrl = audioUrl,
        errorMessage = errorMessage,
        title = title,
        japaneseIntroText = japaneseIntroText,
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = listOf("a1"),
        transcript = null,
        glossary = null,
        quiz = null,
        sourceKind = EpisodeSourceKind.Unknown,
        sourceArticles = emptyList(),
    )

    private fun playableDirect(audioUrl: String): Episode = Episode.Playable.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = listOf("a1"),
        transcript = null,
        glossary = null,
        quiz = null,
        sourceKind = EpisodeSourceKind.Unknown,
        sourceArticles = emptyList(),
        audioUrl = audioUrl,
    )

    // ---- 判別の表（個別の行） ----

    @Test
    fun `shared-session-08 android-rule-catalog-01 completedで音声URLがあり識別子が無いときだけPlayable`() {
        // verifies: CI-E1 / T-E1
        val episode = create(EpisodeStatus.Completed, audioUrl = url, errorMessage = null)

        assertTrue("Playable であるべき: $episode", episode is Episode.Playable)
        assertEquals(url, (episode as Episode.Playable).audioUrl)
    }

    @Test
    fun `shared-session-08 completedでも識別子があればFailed_空文字も識別子あり`() {
        // verifies: CI-E1 / T-E2
        val emptyId = create(EpisodeStatus.Completed, errorMessage = "")
        val withId = create(EpisodeStatus.Completed, errorMessage = "boom")

        assertTrue("空文字の識別子は Failed: $emptyId", emptyId is Episode.Failed)
        assertEquals("", (emptyId as Episode.Failed).errorMessage)
        assertTrue("識別子ありは Failed: $withId", withId is Episode.Failed)
        assertEquals("boom", (withId as Episode.Failed).errorMessage)
    }

    @Test
    fun `shared-session-09 partial_failedは音声URLがあってもFailed`() {
        // verifies: CI-E1 / T-E3
        val episode = create(EpisodeStatus.PartialFailed, audioUrl = url, errorMessage = null)

        assertTrue("PartialFailed は Failed: $episode", episode is Episode.Failed)
    }

    @Test
    fun `android-rule-catalog-01 failedはFailed`() {
        // verifies: CI-E1 / T-E4
        val episode = create(EpisodeStatus.Failed, errorMessage = "generation_failed")

        assertTrue("Failed は Failed: $episode", episode is Episode.Failed)
        assertEquals("generation_failed", (episode as Episode.Failed).errorMessage)
    }

    @Test
    fun `android-rule-catalog-01 processingはGenerating`() {
        // verifies: CI-E1 / T-E5
        val episode = create(EpisodeStatus.Processing, audioUrl = "", errorMessage = null)

        assertTrue("Processing は Generating: $episode", episode is Episode.Generating)
    }

    @Test
    fun `android-rule-catalog-01 completedで音声URLが空ならFailedで識別子はnullのまま`() {
        // verifies: CI-E1 / T-E6
        val episode = create(EpisodeStatus.Completed, audioUrl = "", errorMessage = null)

        assertTrue("URL 空は Failed: $episode", episode is Episode.Failed)
        assertNull((episode as Episode.Failed).errorMessage)
    }

    @Test
    fun `android-rule-catalog-01 未知のstatusは音声URLがあってもFailed`() {
        // verifies: CI-E1 / T-E7
        val episode = create(EpisodeStatus.Unknown, audioUrl = url, errorMessage = null)

        assertTrue("Unknown は Failed（fail-closed）: $episode", episode is Episode.Failed)
    }

    @Test
    fun `android-rule-catalog-01 processingは識別子と音声URLがあってもGenerating`() {
        // verifies: CI-E1 / T-E9
        val episode = create(EpisodeStatus.Processing, audioUrl = url, errorMessage = "x")

        assertTrue("Processing は無条件に Generating: $episode", episode is Episode.Generating)
    }

    // ---- 判別の表（弁別力。恒久） ----

    private data class Row(
        val name: String,
        val status: EpisodeStatus,
        val audioUrl: String,
        val errorMessage: String?,
        val expected: Class<out Episode>,
    )

    private val rows = listOf(
        Row("completed+URL+識別子なし", EpisodeStatus.Completed, url, null, Episode.Playable::class.java),
        Row("completed+識別子が空文字", EpisodeStatus.Completed, url, "", Episode.Failed::class.java),
        Row("completed+識別子あり", EpisodeStatus.Completed, url, "boom", Episode.Failed::class.java),
        Row("completed+URL空", EpisodeStatus.Completed, "", null, Episode.Failed::class.java),
        Row("completed+URL空+識別子あり", EpisodeStatus.Completed, "", "boom", Episode.Failed::class.java),
        Row("partial_failed+URL", EpisodeStatus.PartialFailed, url, null, Episode.Failed::class.java),
        Row("failed", EpisodeStatus.Failed, url, null, Episode.Failed::class.java),
        Row("processing", EpisodeStatus.Processing, url, null, Episode.Generating::class.java),
        Row("processing+URL空", EpisodeStatus.Processing, "", null, Episode.Generating::class.java),
        Row("未知のstatus", EpisodeStatus.Unknown, url, null, Episode.Failed::class.java),
        Row("未知のstatus+URL空", EpisodeStatus.Unknown, "", null, Episode.Failed::class.java),
    )

    private fun assertDiscriminationTable(discriminate: (EpisodeStatus, String, String?) -> Episode) {
        for (row in rows) {
            val actual = discriminate(row.status, row.audioUrl, row.errorMessage)
            val label = "行「${row.name}」status=${row.status} url=${row.audioUrl} errorMessage=${row.errorMessage}"
            assertEquals("$label の variant", row.expected, actual::class.java)
            when (actual) {
                is Episode.Playable -> assertEquals("$label の audioUrl", row.audioUrl, actual.audioUrl)
                is Episode.Failed -> assertEquals("$label の errorMessage", row.errorMessage, actual.errorMessage)
                is Episode.Generating -> Unit
            }
        }
    }

    @Test
    fun `android-rule-catalog-01 shared-session-08 shared-session-09 本物の生成関数で判別の表の全行が通る`() {
        // verifies: CI-E1 / T-E1
        assertDiscriminationTable { status, audioUrl, errorMessage -> create(status, audioUrl, errorMessage) }
    }

    @Test
    fun `android-rule-catalog-01 未知のstatusをPlayableに倒すstubでは判別の表が未知のstatusの行で失敗する`() {
        // verifies: CI-E1 / T-E8
        val unknownAsPlayable: (EpisodeStatus, String, String?) -> Episode = { status, audioUrl, errorMessage ->
            if (status == EpisodeStatus.Unknown) {
                playableDirect("https://example.com/stub.mp3")
            } else {
                create(status, audioUrl, errorMessage)
            }
        }

        val failure = assertThrows(AssertionError::class.java) { assertDiscriminationTable(unknownAsPlayable) }

        val message = failure.message.orEmpty()
        assertTrue("失敗メッセージに行の status 名がある: $message", message.contains("Unknown"))
        assertTrue("失敗した行は未知のstatusの行: $message", message.contains("未知のstatus"))
    }

    // ---- displayTitle ----

    @Test
    fun `android-rule-catalog-02 displayTitleは題名を前後の空白を除いて返す`() {
        // verifies: CI-E8 / T-E10
        val episode = create(EpisodeStatus.Completed, title = "  T  ", japaneseIntroText = "イントロ")

        assertEquals("T", episode.displayTitle)
    }

    @Test
    fun `android-rule-catalog-02 displayTitleは題名が空白だけなら日本語イントロを返す`() {
        // verifies: CI-E8 / T-E10
        val episode = create(EpisodeStatus.Completed, title = "   ", japaneseIntroText = " I ")

        assertEquals("I", episode.displayTitle)
    }

    @Test
    fun `android-rule-catalog-02 displayTitleは題名もイントロも空白だけなら既定の文言を返す`() {
        // verifies: CI-E8 / T-E10
        val episode = create(EpisodeStatus.Completed, title = " ", japaneseIntroText = "  ")

        assertEquals("ニュースポッドキャスト", episode.displayTitle)
    }

    // ---- 値の保持 ----

    private fun full(status: EpisodeStatus, audioUrl: String, errorMessage: String?): Episode = Episode.create(
        id = "ep-77",
        kind = EpisodeKind.Digest,
        status = status,
        audioUrl = audioUrl,
        errorMessage = errorMessage,
        title = "題名77",
        japaneseIntroText = "イントロ77",
        difficultyCode = "toeic_900",
        durationSeconds = 321,
        createdAt = "2026-08-02T10:11:12+00:00",
        resumeHintSeconds = 42.5,
        articleIds = listOf("a1", "a2"),
        transcript = listOf(EpisodeTranscriptSegment("A", "Hello", "fact"), EpisodeTranscriptSegment("B", "Hi", null)),
        glossary = listOf(EpisodeGlossaryEntry("term", "意味", "example")),
        quiz = listOf(EpisodeQuizQuestion("Q1", listOf("x", "y"))),
        sourceKind = EpisodeSourceKind.Featured,
        sourceArticles = listOf(EpisodeSourceArticle("art-1", "Title", "https://example.com/art", "Source")),
    )

    private fun assertCommonValues(label: String, episode: Episode) {
        assertEquals("$label id", "ep-77", episode.id)
        assertEquals("$label kind", EpisodeKind.Digest, episode.kind)
        assertEquals("$label title", "題名77", episode.title)
        assertEquals("$label japaneseIntroText", "イントロ77", episode.japaneseIntroText)
        assertEquals("$label difficultyCode", "toeic_900", episode.difficultyCode)
        assertEquals("$label durationSeconds", 321, episode.durationSeconds)
        assertEquals("$label createdAt", "2026-08-02T10:11:12+00:00", episode.createdAt)
        assertEquals("$label resumeHintSeconds", 42.5, episode.resumeHintSeconds, 0.0)
        assertEquals("$label articleIds", listOf("a1", "a2"), episode.articleIds)
        assertEquals(
            "$label transcript",
            listOf(EpisodeTranscriptSegment("A", "Hello", "fact"), EpisodeTranscriptSegment("B", "Hi", null)),
            episode.transcript,
        )
        assertEquals("$label glossary", listOf(EpisodeGlossaryEntry("term", "意味", "example")), episode.glossary)
        assertEquals("$label quiz", listOf(EpisodeQuizQuestion("Q1", listOf("x", "y"))), episode.quiz)
        assertEquals("$label sourceKind", EpisodeSourceKind.Featured, episode.sourceKind)
        assertEquals(
            "$label sourceArticles",
            listOf(EpisodeSourceArticle("art-1", "Title", "https://example.com/art", "Source")),
            episode.sourceArticles,
        )
        val item: QueueItem = episode
        assertEquals("$label QueueItem.id", "ep-77", item.id)
    }

    @Test
    fun `android-rule-catalog-01 3つのvariantすべてが共通の値を入力どおり保持しQueueItemのidを公開する`() {
        // verifies: CI-E2, CI-E7 / T-E11
        val playable = full(EpisodeStatus.Completed, url, null)
        val generating = full(EpisodeStatus.Processing, "", null)
        val failed = full(EpisodeStatus.Failed, "", "boom")

        assertTrue(playable is Episode.Playable)
        assertTrue(generating is Episode.Generating)
        assertTrue(failed is Episode.Failed)
        assertCommonValues("Playable", playable)
        assertCommonValues("Generating", generating)
        assertCommonValues("Failed", failed)
    }

    @Test
    fun `android-rule-catalog-01 内容のlistのnullはnullのまま空listは空listのまま`() {
        // verifies: CI-E4 / T-E12
        val unprovided = create(EpisodeStatus.Completed)
        val empty = Episode.create(
            id = "ep-1",
            kind = EpisodeKind.Single,
            status = EpisodeStatus.Completed,
            audioUrl = url,
            errorMessage = null,
            title = "題名",
            japaneseIntroText = "イントロ",
            difficultyCode = "toeic_600",
            durationSeconds = 120,
            createdAt = "2026-07-01T09:00:00+00:00",
            resumeHintSeconds = 0.0,
            articleIds = emptyList(),
            transcript = emptyList(),
            glossary = emptyList(),
            quiz = emptyList(),
            sourceKind = EpisodeSourceKind.Unknown,
            sourceArticles = emptyList(),
        )

        assertNull("transcript の null は null", unprovided.transcript)
        assertNull("glossary の null は null", unprovided.glossary)
        assertNull("quiz の null は null", unprovided.quiz)
        assertEquals("transcript の空は空", emptyList<EpisodeTranscriptSegment>(), empty.transcript)
        assertEquals("glossary の空は空", emptyList<EpisodeGlossaryEntry>(), empty.glossary)
        assertEquals("quiz の空は空", emptyList<EpisodeQuizQuestion>(), empty.quiz)
    }
}

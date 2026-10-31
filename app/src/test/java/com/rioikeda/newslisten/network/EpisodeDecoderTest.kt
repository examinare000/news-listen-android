package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeKind
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceArticle
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceKind
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.PodcastSourceArticleResponse
import com.rioikeda.newslisten.model.QuizQuestion
import com.rioikeda.newslisten.model.TranscriptSegment
import com.rioikeda.newslisten.model.VocabularyEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTO（`PodcastResponse`）から domain（`Episode`）への写し。写しの表の全行を観測する。 */
class EpisodeDecoderTest {

    private fun dto(
        type: String = "single",
        status: String = "completed",
        audioUrl: String = "https://example.com/audio.mp3",
        errorMessage: String? = null,
        segments: List<TranscriptSegment>? = null,
        vocabulary: List<VocabularyEntry>? = null,
        quiz: List<QuizQuestion>? = null,
        sourceKind: String? = null,
        sourceArticles: List<PodcastSourceArticleResponse>? = null,
    ) = PodcastResponse(
        id = "p1",
        type = type,
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = audioUrl,
        japaneseIntroText = "イントロ",
        durationSeconds = 120,
        status = status,
        errorMessage = errorMessage,
        playbackPositionSeconds = 0.0,
        title = "題名",
        segments = segments,
        vocabulary = vocabulary,
        quiz = quiz,
        createdAt = "2026-07-01T09:00:00+00:00",
        sourceKind = sourceKind,
        sourceArticles = sourceArticles,
    )

    // ---- 全 field の写し ----

    @Test
    fun `android-dec-17 android-dec-10 DTOの全fieldが写しの表どおりにEpisodeへ写る`() {
        // verifies: CI-D1 / T-D1
        val response = PodcastResponse(
            id = "id-9",
            type = "digest",
            articleIds = listOf("art-a", "art-b"),
            difficulty = "toeic_730",
            audioUrl = "https://example.com/nine.mp3",
            japaneseIntroText = "九番のイントロ",
            durationSeconds = 987,
            status = "completed",
            errorMessage = null,
            playbackPositionSeconds = 61.25,
            title = "九番の題名",
            segments = listOf(TranscriptSegment("S1", "first line", "fact"), TranscriptSegment("S2", "second line", null)),
            vocabulary = listOf(VocabularyEntry("term-1", "意味1", "example-1")),
            quiz = listOf(QuizQuestion("question-1", listOf("opt-a", "opt-b"))),
            createdAt = "2026-09-09T09:09:09+00:00",
            sourceKind = "featured",
            sourceArticles = listOf(PodcastSourceArticleResponse("sa-1", "SA Title", "https://example.com/sa", "SA Source")),
        )

        val episode = EpisodeDecoder.decode(response)

        assertTrue("completed+URL+識別子なしは Playable: $episode", episode is Episode.Playable)
        assertEquals("https://example.com/nine.mp3", (episode as Episode.Playable).audioUrl)
        assertEquals("id-9", episode.id)
        assertEquals(EpisodeKind.Digest, episode.kind)
        assertEquals("九番の題名", episode.title)
        assertEquals("九番のイントロ", episode.japaneseIntroText)
        assertEquals("toeic_730", episode.difficultyCode)
        assertEquals(987, episode.durationSeconds)
        assertEquals("2026-09-09T09:09:09+00:00", episode.createdAt)
        assertEquals(61.25, episode.resumeHintSeconds, 0.0)
        assertEquals(listOf("art-a", "art-b"), episode.articleIds)
        assertEquals(
            listOf(EpisodeTranscriptSegment("S1", "first line", "fact"), EpisodeTranscriptSegment("S2", "second line", null)),
            episode.transcript,
        )
        assertEquals(listOf(EpisodeGlossaryEntry("term-1", "意味1", "example-1")), episode.glossary)
        assertEquals(listOf(EpisodeQuizQuestion("question-1", listOf("opt-a", "opt-b"))), episode.quiz)
        assertEquals(EpisodeSourceKind.Featured, episode.sourceKind)
        assertEquals(listOf(EpisodeSourceArticle("sa-1", "SA Title", "https://example.com/sa", "SA Source")), episode.sourceArticles)
    }

    // ---- type / status / errorMessage ----

    @Test
    fun `android-dec-17 typeはdigestだけがDigestで未知の値はSingle`() {
        // verifies: CI-D2 / T-D2
        assertEquals(EpisodeKind.Single, EpisodeDecoder.decode(dto(type = "single")).kind)
        assertEquals(EpisodeKind.Digest, EpisodeDecoder.decode(dto(type = "digest")).kind)
        assertEquals(EpisodeKind.Single, EpisodeDecoder.decode(dto(type = "daily")).kind)
        assertEquals(EpisodeKind.Single, EpisodeDecoder.decode(dto(type = "other")).kind)
    }

    @Test
    fun `android-dec-17 statusの5値が判別の入力へ写り未知の値と大文字小文字違いはFailed`() {
        // verifies: CI-D3, CI-D6 / T-D3
        assertTrue(EpisodeDecoder.decode(dto(status = "completed")) is Episode.Playable)
        assertTrue(EpisodeDecoder.decode(dto(status = "processing")) is Episode.Generating)
        assertTrue(EpisodeDecoder.decode(dto(status = "failed")) is Episode.Failed)
        assertTrue(EpisodeDecoder.decode(dto(status = "partial_failed")) is Episode.Failed)
        assertTrue("未知の status は fail-closed", EpisodeDecoder.decode(dto(status = "weird")) is Episode.Failed)
        assertTrue("大文字小文字は区別する", EpisodeDecoder.decode(dto(status = "Completed")) is Episode.Failed)
        assertTrue("空の status は未知", EpisodeDecoder.decode(dto(status = "")) is Episode.Failed)
    }

    @Test
    fun `android-dec-17 completedでもerror_messageが空文字ならFailedで識別子を保ちnullならPlayable`() {
        // verifies: CI-D7 / T-D4
        val emptyId = EpisodeDecoder.decode(dto(status = "completed", errorMessage = ""))
        val noId = EpisodeDecoder.decode(dto(status = "completed", errorMessage = null))

        assertTrue("空文字は識別子あり: $emptyId", emptyId is Episode.Failed)
        assertEquals("", (emptyId as Episode.Failed).errorMessage)
        assertTrue("null は識別子なし: $noId", noId is Episode.Playable)
    }

    @Test
    fun `android-dec-17 completedでも音声URLが空ならFailed`() {
        // verifies: CI-D1, CI-D3 / T-D3
        val episode = EpisodeDecoder.decode(dto(status = "completed", audioUrl = ""))

        assertTrue("URL 空は Failed: $episode", episode is Episode.Failed)
        assertNull((episode as Episode.Failed).errorMessage)
    }

    // ---- 内容 ----

    @Test
    fun `android-dec-17 内容のlistのnullはnullのまま要素は順序を保って1対1で写りroleのnullも保つ`() {
        // verifies: CI-D5 / T-D5
        val unprovided = EpisodeDecoder.decode(dto(segments = null, vocabulary = null, quiz = null))
        val provided = EpisodeDecoder.decode(
            dto(
                segments = listOf(TranscriptSegment("A", "one", null), TranscriptSegment("B", "two", "commentary")),
                vocabulary = listOf(VocabularyEntry("t1", "意味1", "e1"), VocabularyEntry("t2", "意味2", "e2")),
                quiz = listOf(QuizQuestion("q1", listOf("x", "y")), QuizQuestion("q2", emptyList())),
            ),
        )

        assertNull(unprovided.transcript)
        assertNull(unprovided.glossary)
        assertNull(unprovided.quiz)
        assertEquals(
            listOf(EpisodeTranscriptSegment("A", "one", null), EpisodeTranscriptSegment("B", "two", "commentary")),
            provided.transcript,
        )
        assertEquals(
            listOf(EpisodeGlossaryEntry("t1", "意味1", "e1"), EpisodeGlossaryEntry("t2", "意味2", "e2")),
            provided.glossary,
        )
        assertEquals(
            listOf(EpisodeQuizQuestion("q1", listOf("x", "y")), EpisodeQuizQuestion("q2", emptyList())),
            provided.quiz,
        )
    }

    // ---- 出典 ----

    @Test
    fun `android-dec-10 source_kindはfeaturedとuserが写り未知の値とnullはUnknown`() {
        // verifies: CI-D4 / T-D6
        assertEquals(EpisodeSourceKind.Featured, EpisodeDecoder.decode(dto(sourceKind = "featured")).sourceKind)
        assertEquals(EpisodeSourceKind.User, EpisodeDecoder.decode(dto(sourceKind = "user")).sourceKind)
        assertEquals(EpisodeSourceKind.Unknown, EpisodeDecoder.decode(dto(sourceKind = "unknown")).sourceKind)
        assertEquals(EpisodeSourceKind.Unknown, EpisodeDecoder.decode(dto(sourceKind = "weird")).sourceKind)
        assertEquals(EpisodeSourceKind.Unknown, EpisodeDecoder.decode(dto(sourceKind = null)).sourceKind)
    }

    @Test
    fun `android-dec-10 source_articlesは2件を順序と値を保って写す`() {
        // verifies: CI-D4 / T-D7
        val episode = EpisodeDecoder.decode(
            dto(
                sourceArticles = listOf(
                    PodcastSourceArticleResponse("art-1", "Title 1", "https://example.com/1", "Source 1"),
                    PodcastSourceArticleResponse("art-2", "Title 2", "https://example.com/2", "Source 2"),
                ),
            ),
        )

        assertEquals(
            listOf(
                EpisodeSourceArticle("art-1", "Title 1", "https://example.com/1", "Source 1"),
                EpisodeSourceArticle("art-2", "Title 2", "https://example.com/2", "Source 2"),
            ),
            episode.sourceArticles,
        )
    }

    @Test
    fun `android-dec-10 source_articlesの要素のfieldがnullなら空文字になる`() {
        // verifies: CI-D4 / T-D8
        val episode = EpisodeDecoder.decode(
            dto(sourceArticles = listOf(PodcastSourceArticleResponse(articleId = "art-1", title = null, url = null, source = "Source 1"))),
        )

        assertEquals(listOf(EpisodeSourceArticle("art-1", "", "", "Source 1")), episode.sourceArticles)
    }

    @Test
    fun `android-dec-10 source_articlesがnullなら空のlistになる`() {
        // verifies: CI-D4 / T-D9
        val episode = EpisodeDecoder.decode(dto(sourceArticles = null))

        assertEquals(emptyList<EpisodeSourceArticle>(), episode.sourceArticles)
    }
}

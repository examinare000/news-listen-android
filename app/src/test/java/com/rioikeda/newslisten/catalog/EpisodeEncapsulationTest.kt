package com.rioikeda.newslisten.catalog

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeKind
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceArticle
import com.rioikeda.newslisten.catalog.domain.EpisodeSourceKind
import com.rioikeda.newslisten.catalog.domain.EpisodeStatus
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Episode` の構築子・`copy` の非公開（android-check-05）と、list の防御的複製（android-check-06）。
 *
 * 反射の検査は synthetic を除く: variant 自身の companion の呼び出しがバイトコードに生む
 * `ACC_PUBLIC|ACC_SYNTHETIC` の構築子と `copy$default` は、Kotlin の source からは呼べない。
 * 比較元は必ずリテラルで書く。生成に渡した list を比較元にすると、書き換えと同じインスタンスを
 * 見比べて空振りの green になるため。
 */
class EpisodeEncapsulationTest {

    private val url = "https://example.com/audio.mp3"

    private val episodeClasses: List<Class<*>> = listOf(
        Episode::class.java,
        Episode.Playable::class.java,
        Episode.Generating::class.java,
        Episode.Failed::class.java,
    )

    // ---- 構築子と copy の非公開 ----

    @Test
    fun `android-check-05 android-dec-19 Episodeと3つのvariantは公開の構築子を持たない`() {
        // verifies: CI-E5 / T-S1
        for (type in episodeClasses) {
            val declared = type.declaredConstructors.filter { !it.isSynthetic }

            assertTrue("${type.simpleName} は非 synthetic の構築子を宣言している（空振り防止）", declared.isNotEmpty())
            assertTrue(
                "${type.simpleName} に公開の構築子がある: $declared",
                declared.none { Modifier.isPublic(it.modifiers) },
            )
        }
    }

    @Test
    fun `android-check-05 android-dec-18 Episodeと3つのvariantは公開のcopyを持たない`() {
        // verifies: CI-E5 / T-S1
        for (type in episodeClasses) {
            val copies = type.declaredMethods.filter { it.name == "copy" && !it.isSynthetic }

            assertTrue(
                "${type.simpleName} に公開の copy がある: $copies",
                copies.none { Modifier.isPublic(it.modifiers) },
            )
        }
    }

    @Test
    fun `android-check-05 Episodeを返す公開の関数はcompanionのcreateだけ`() {
        // verifies: CI-E5 / T-S2
        val publicEntries = Episode.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.returnType == Episode::class.java }
            .map { it.name }

        assertEquals(listOf("create"), publicEntries)
    }

    // ---- internal の工場（正負の対照） ----

    private fun playable(audioUrl: String): Episode = Episode.Playable.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 3.5,
        articleIds = listOf("a1"),
        transcript = null,
        glossary = null,
        quiz = null,
        sourceKind = EpisodeSourceKind.User,
        sourceArticles = emptyList(),
        audioUrl = audioUrl,
    )

    @Test
    fun `android-check-05 正の対照 internalの工場は正当な入力から値を保持したPlayableを作る`() {
        // verifies: CI-E6 / T-S3
        val episode = playable(url)

        assertTrue("Playable であるべき: $episode", episode is Episode.Playable)
        assertEquals(url, (episode as Episode.Playable).audioUrl)
        assertEquals("ep-1", episode.id)
        assertEquals(3.5, episode.resumeHintSeconds, 0.0)
        assertEquals(EpisodeSourceKind.User, episode.sourceKind)
    }

    @Test
    fun `android-check-05 負の対照 音声URLが空の入力はPlayableにならない`() {
        // verifies: CI-E6, CI-E1 / T-S4
        assertThrows(IllegalArgumentException::class.java) { playable("") }

        val viaPublicEntry = Episode.create(
            id = "ep-1",
            kind = EpisodeKind.Single,
            status = EpisodeStatus.Completed,
            audioUrl = "",
            errorMessage = null,
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
        )
        assertTrue("公開の入口は例外でなく Failed にする: $viaPublicEntry", viaPublicEntry is Episode.Failed)
    }

    // ---- list の防御的複製 ----

    private class Inputs {
        val articleIds = mutableListOf("a1", "a2")
        val segments = mutableListOf(EpisodeTranscriptSegment("A", "Hello", null))
        val glossary = mutableListOf(EpisodeGlossaryEntry("term", "意味", "example"))
        val options = mutableListOf("x", "y")
        val quiz = mutableListOf(EpisodeQuizQuestion("Q1", options))
        val sources = mutableListOf(EpisodeSourceArticle("art-1", "Title", "https://example.com/art", "Source"))

        fun mutate() {
            articleIds.add("zzz")
            segments.clear()
            glossary.clear()
            options.add("z")
            quiz.clear()
            sources.clear()
        }
    }

    private fun viaPublic(status: EpisodeStatus, i: Inputs): Episode = Episode.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        status = status,
        audioUrl = if (status == EpisodeStatus.Completed) url else "",
        errorMessage = if (status == EpisodeStatus.Failed) "boom" else null,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = i.articleIds,
        transcript = i.segments,
        glossary = i.glossary,
        quiz = i.quiz,
        sourceKind = EpisodeSourceKind.Featured,
        sourceArticles = i.sources,
    )

    private fun viaPlayableFactory(i: Inputs): Episode = Episode.Playable.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = i.articleIds,
        transcript = i.segments,
        glossary = i.glossary,
        quiz = i.quiz,
        sourceKind = EpisodeSourceKind.Featured,
        sourceArticles = i.sources,
        audioUrl = url,
    )

    private fun viaGeneratingFactory(i: Inputs): Episode = Episode.Generating.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = i.articleIds,
        transcript = i.segments,
        glossary = i.glossary,
        quiz = i.quiz,
        sourceKind = EpisodeSourceKind.Featured,
        sourceArticles = i.sources,
    )

    private fun viaFailedFactory(i: Inputs): Episode = Episode.Failed.create(
        id = "ep-1",
        kind = EpisodeKind.Single,
        title = "題名",
        japaneseIntroText = "イントロ",
        difficultyCode = "toeic_600",
        durationSeconds = 120,
        createdAt = "2026-07-01T09:00:00+00:00",
        resumeHintSeconds = 0.0,
        articleIds = i.articleIds,
        transcript = i.segments,
        glossary = i.glossary,
        quiz = i.quiz,
        sourceKind = EpisodeSourceKind.Featured,
        sourceArticles = i.sources,
        errorMessage = "boom",
    )

    private fun assertListsAreOriginal(label: String, episode: Episode) {
        assertEquals("$label articleIds", listOf("a1", "a2"), episode.articleIds)
        assertEquals("$label transcript", listOf(EpisodeTranscriptSegment("A", "Hello", null)), episode.transcript)
        assertEquals("$label glossary", listOf(EpisodeGlossaryEntry("term", "意味", "example")), episode.glossary)
        assertEquals("$label quiz", listOf(EpisodeQuizQuestion("Q1", listOf("x", "y"))), episode.quiz)
        assertEquals(
            "$label sourceArticles",
            listOf(EpisodeSourceArticle("art-1", "Title", "https://example.com/art", "Source")),
            episode.sourceArticles,
        )
    }

    @Test
    fun `android-check-06 公開の入口へ渡したlistを後から書き換えてもEpisodeの値は変わらない`() {
        // verifies: CI-E3 / T-S5
        for (status in listOf(EpisodeStatus.Completed, EpisodeStatus.Processing, EpisodeStatus.Failed)) {
            val inputs = Inputs()
            val episode = viaPublic(status, inputs)

            inputs.mutate()

            assertListsAreOriginal("公開の入口 status=$status", episode)
        }
    }

    @Test
    fun `android-check-06 variantのinternalの工場へ渡したlistを後から書き換えてもEpisodeの値は変わらない`() {
        // verifies: CI-E6, CI-E3 / T-S7
        val factories: List<Pair<String, (Inputs) -> Episode>> = listOf(
            "Playable.create" to ::viaPlayableFactory,
            "Generating.create" to ::viaGeneratingFactory,
            "Failed.create" to ::viaFailedFactory,
        )
        for ((label, factory) in factories) {
            val inputs = Inputs()
            val episode = factory(inputs)

            inputs.mutate()

            assertListsAreOriginal(label, episode)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> tryToMutate(list: List<T>, element: T) {
        runCatching { (list as MutableList<T>).add(element) }
        runCatching { (list as MutableList<T>).clear() }
    }

    @Test
    fun `android-check-06 返したlistをMutableListにcastして書き換えても次に読んだ値は変わらない`() {
        // verifies: CI-E3 / T-S6
        val factories: List<Pair<String, (Inputs) -> Episode>> = listOf(
            "公開の入口" to { i -> viaPublic(EpisodeStatus.Completed, i) },
            "Playable.create" to ::viaPlayableFactory,
            "Generating.create" to ::viaGeneratingFactory,
            "Failed.create" to ::viaFailedFactory,
        )
        for ((label, factory) in factories) {
            val episode = factory(Inputs())

            tryToMutate(episode.articleIds, "zzz")
            tryToMutate(episode.transcript!!, EpisodeTranscriptSegment("Z", "Z", null))
            tryToMutate(episode.glossary!!, EpisodeGlossaryEntry("z", "z", "z"))
            tryToMutate(episode.quiz!!.first().options, "zzz")
            tryToMutate(episode.quiz!!, EpisodeQuizQuestion("Z", emptyList()))
            tryToMutate(episode.sourceArticles, EpisodeSourceArticle("z", "z", "z", "z"))

            assertListsAreOriginal("$label の返した list を書き換えた後", episode)
        }
    }
}

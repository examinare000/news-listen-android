package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.app.EpisodeRow
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.EpisodeDecoder
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面が読むリードモデルの値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 生成に渡した list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class PlaybackReadModelsEncapsulationTest {

    private fun entry(id: String) = UpNextEntry(
        episodeId = id,
        displayTitle = "title-$id",
        difficultyCode = "toeic_600",
        durationSeconds = 60,
        canMoveUp = false,
        canMoveDown = false,
    )

    @Test
    fun `android-check-06 QueueViewのupNextは構築後に渡したlistを書き換えても変わらない`() {
        val source = mutableListOf(entry("a"), entry("b"))

        val view = QueueView(nowPlaying = null, upNext = source, upNextCount = 2)
        source.clear()
        source.add(entry("z"))

        assertEquals(listOf("a", "b"), view.upNext.map { it.episodeId })
    }

    @Test
    fun `android-check-06 QueueViewのupNextは公開された参照から書き換えられない`() {
        val view = QueueView(nowPlaying = null, upNext = listOf(entry("a"), entry("b")), upNextCount = 2)

        @Suppress("UNCHECKED_CAST")
        val exposed = view.upNext as MutableList<UpNextEntry>
        assertThrows(UnsupportedOperationException::class.java) { exposed.add(entry("z")) }
        assertThrows(UnsupportedOperationException::class.java) { exposed.removeAt(0) }

        assertEquals(listOf("a", "b"), view.upNext.map { it.episodeId })
    }

    @Test
    fun `android-check-06 EpisodeRowは書き換え可能な項目も書き込みの関数も持たない`() {
        val type = EpisodeRow::class.java
        val instanceFields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

        assertTrue("EpisodeRow は項目を宣言している（空振り防止）", instanceFields.isNotEmpty())
        assertTrue(
            "書き換え可能な項目がある: $instanceFields",
            instanceFields.all { Modifier.isFinal(it.modifiers) },
        )
        assertTrue(
            "set で始まる関数がある",
            type.declaredMethods.none { it.name.startsWith("set") },
        )
    }

    @Test
    fun `android-check-06 PlaybackNoticeのGenerationFailedは書き換え可能な項目も書き込みの関数も持たない`() {
        val type = PlaybackNotice.GenerationFailed::class.java
        val instanceFields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

        assertTrue("GenerationFailed は項目を宣言している（空振り防止）", instanceFields.isNotEmpty())
        assertTrue(
            "書き換え可能な項目がある: $instanceFields",
            instanceFields.all { Modifier.isFinal(it.modifiers) },
        )
        assertTrue(
            "set で始まる関数がある",
            type.declaredMethods.none { it.name.startsWith("set") },
        )
        // 項目は理由の enum だけで、識別子の生の文字列を持たない
        assertEquals(listOf(GenerationFailureReason::class.java), instanceFields.map { it.type })
    }

    @Test
    fun `android-check-06 PlaybackNoticeはunplayableから二度読んでも等価で理由はenumの閉じた集合`() {
        val episode = EpisodeDecoder.decode(
            PodcastResponse(
                id = "e1",
                type = "daily",
                articleIds = listOf("a1"),
                difficulty = "toeic_600",
                audioUrl = "https://example.com/e1.mp3",
                japaneseIntroText = "intro",
                durationSeconds = 60,
                status = "failed",
                errorMessage = "quota_exhausted",
                playbackPositionSeconds = 0.0,
                createdAt = "2026-07-01T00:00:00Z",
            ),
        )

        val first = PlaybackNotice.unplayable(episode)
        val second = PlaybackNotice.unplayable(episode)

        assertEquals(PlaybackNotice.GenerationFailed(GenerationFailureReason.QUOTA_EXHAUSTED), first)
        assertEquals(first, second)
        assertEquals(
            listOf("GENERATION_FAILED", "QUOTA_EXHAUSTED"),
            GenerationFailureReason.entries.map { it.name }.sorted(),
        )
    }
}

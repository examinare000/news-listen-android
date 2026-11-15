package com.rioikeda.newslisten.feed.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 保留の規則（android-rule-catalog-04）。積む関数と戻す関数は純粋で、引数の list を変えない。 */
class PendingCurationTest {

    private fun article(id: String) = Article(
        id = id,
        title = "title-$id",
        url = "https://example.com/$id",
        source = "source",
        score = 0.9,
        publishedAt = "2026-07-01T00:00:00Z",
        isRead = false,
    )

    @Test
    fun 積むと対象を除いた一覧と元の位置つきの保留を返し引数の一覧は変えない() {
        val source = listOf(article("1"), article("2"), article("3"))

        val staged = PendingCuration.stage(source, "2", PendingCuration.Kind.DISMISS, null)

        assertEquals(
            listOf(article("1"), article("3")) to PendingCuration(article("2"), 1, PendingCuration.Kind.DISMISS, null),
            staged,
        )
        assertEquals(listOf("1", "2", "3"), source.map { it.id })
    }

    @Test
    fun 積むとき対象が一覧に無ければnullを返す() {
        assertNull(PendingCuration.stage(listOf(article("1")), "x", PendingCuration.Kind.STAR, null))
    }

    @Test
    fun 積むとき難易度を保留に持たせる() {
        val staged = PendingCuration.stage(listOf(article("1")), "1", PendingCuration.Kind.STAR, "toeic_900")

        assertEquals("toeic_900", staged?.second?.difficulty)
    }

    @Test
    fun 戻すと元の位置へ挿入し引数の一覧は変えない() {
        val pending = PendingCuration(article("2"), 1, PendingCuration.Kind.DISMISS, null)
        val remaining = listOf(article("1"), article("3"))

        val restored = pending.restoreInto(remaining)

        assertEquals(listOf("1", "2", "3"), restored.map { it.id })
        assertEquals(listOf("1", "3"), remaining.map { it.id })
    }

    @Test
    fun 戻すとき元の位置が一覧の長さを超えていれば末尾へ丸める() {
        val pending = PendingCuration(article("3"), 5, PendingCuration.Kind.STAR, null)

        val restored = pending.restoreInto(listOf(article("1")))

        assertEquals(listOf("1", "3"), restored.map { it.id })
    }
}

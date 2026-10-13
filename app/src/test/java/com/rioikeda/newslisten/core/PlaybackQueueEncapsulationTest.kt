package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaybackQueue` の実行時のカプセル化（Spec §7 android-check-06・product-quality-06。契約 CI-17）。
 *
 * 渡した list・返した list を書き換えても、次に読む値と不変条件が変わらないことを検査する。
 * main の `PlaybackQueue` は android-slice-skeleton-01 では変えない。
 *
 * 比較元は必ずリテラルで書く。`q.items` を変数に取って比較元にすると、書き換えと同じ
 * インスタンスを見比べて空振りの green になるため（SR-06）。
 */
class PlaybackQueueEncapsulationTest {

    private data class Item(override val id: String) : QueueItem

    private val a = Item("a")
    private val b = Item("b")
    private val c = Item("c")
    private val x = Item("x")
    private val y = Item("y")

    /** `setQueue(arrayListOf(a, b, c), 1)` の状態が変わっていないこと（①②で共通）。 */
    private fun assertQueueOfAbcAtB(q: PlaybackQueue<Item>) {
        assertEquals(listOf(a, b, c), q.items)
        assertEquals(b, q.current)
        assertEquals(listOf(c), q.upNext)
        assertEquals(1, q.currentIndex)
        assertTrue("currentIndex が items の範囲外", q.currentIndex in q.items.indices)
    }

    /** 書き換えは「できなかった」ことも許す（不変 list を返す実装を妨げない）。 */
    private fun mutateAllowingRejection(mutation: () -> Unit) {
        val failure = runCatching(mutation).exceptionOrNull()
        if (failure != null) {
            assertTrue(
                "拒否の例外は UnsupportedOperationException か ClassCastException だけ: $failure",
                failure is UnsupportedOperationException || failure is ClassCastException,
            )
        }
    }

    @Test
    fun android_check_06_product_quality_06_構築子に渡したlistの要素を置き換えてもキューは変わらない() {
        // verifies: CI-17 ①
        val input = arrayListOf(a, b, c)
        val q = PlaybackQueue(items = input, currentIndex = 1)

        input[0] = x

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_copyに渡したlistの要素を置き換えてもキューは変わらない() {
        // verifies: CI-17 ①
        val input = arrayListOf(a, b, c)
        val q = PlaybackQueue<Item>().copy(items = input, currentIndex = 1)

        input[0] = x

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_setQueueに渡したlistの要素を置き換えてもキューは変わらない() {
        // verifies: CI-17 ① / T-18
        val input = arrayListOf(a, b, c)
        val q = PlaybackQueue<Item>().setQueue(input, 1)
        assertNotSame(listOf(a, b, c), input)

        input[0] = x

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_setQueueに渡したlistへ要素を足してもキューは変わらない() {
        // verifies: CI-17 ① / T-19
        val input = arrayListOf(a, b, c)
        val q = PlaybackQueue<Item>().setQueue(input, 1)
        assertNotSame(listOf(a, b, c), input)

        input.add(y)

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_setQueueに渡したlistを空にしてもキューは変わらない() {
        // verifies: CI-17 ① / T-20
        val input = arrayListOf(a, b, c)
        val q = PlaybackQueue<Item>().setQueue(input, 1)
        assertNotSame(listOf(a, b, c), input)

        input.clear()

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_返したitemsをMutableListとして書き換えてもキューは変わらない_setQueue() {
        // verifies: CI-17 ② / T-21
        val q = PlaybackQueue<Item>().setQueue(arrayListOf(a, b, c), 1)
        val returned = q.items
        assertNotSame(listOf(a, b, c), returned)

        mutateAllowingRejection { (returned as MutableList<Item>)[0] = x }

        assertQueueOfAbcAtB(q)
    }

    @Test
    fun android_check_06_product_quality_06_返したitemsをMutableListとして書き換えてもキューは変わらない_add() {
        // verifies: CI-17 ② / T-22
        val q = PlaybackQueue<Item>().add(a).add(b)
        val returned = q.items
        assertNotSame(listOf(a, b), returned)

        mutateAllowingRejection { (returned as MutableList<Item>).add(c) }

        assertEquals(listOf(a, b), q.items)
    }

    @Test
    fun android_check_06_product_quality_06_currentIndexが無いときに返したupNextを空にしてもキューは変わらない() {
        // verifies: CI-17 ② / T-23
        val q = PlaybackQueue<Item>().add(a).add(b)
        assertEquals(null, q.currentIndex)
        val returned = q.upNext
        assertNotSame(listOf(a, b), returned)

        mutateAllowingRejection { (returned as MutableList<Item>).clear() }

        assertEquals(listOf(a, b), q.items)
        assertEquals(listOf(a, b), q.upNext)
    }

    @Test
    fun android_check_06_product_quality_06_currentIndexがあるときに返したupNextを空にしてもキューは変わらない() {
        // verifies: CI-17 ② / T-24（現行で green。drop が新しい list を作るため）
        val q = PlaybackQueue<Item>().setQueue(arrayListOf(a, b, c), 0)
        val returned = q.upNext
        assertNotSame(listOf(b, c), returned)

        mutateAllowingRejection { (returned as MutableList<Item>).clear() }

        assertEquals(listOf(a, b, c), q.items)
        assertEquals(listOf(b, c), q.upNext)
    }
}

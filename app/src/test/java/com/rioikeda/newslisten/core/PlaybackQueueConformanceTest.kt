package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/design/shared-playback-spec.md §4.1（Q表）の全32行に準拠するテスト。
 * テスト名の行IDを正本の表と1対1で照合できるようにする。
 */
class PlaybackQueueConformanceTest {

    private fun ep(id: String) = TestEpisode(id)

    // --- 状態モデル + アクセサ ---

    @Test
    fun `Q-01 current と upNext は currentIndex を基準に返る`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 1)

        assertEquals(ep("b"), queue.current)
        assertEquals(listOf(ep("c")), queue.upNext)
    }

    @Test
    fun `Q-02 currentIndex が null なら current は null で upNext は items 全体`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b")), currentIndex = null)

        assertNull(queue.current)
        assertEquals(listOf(ep("a"), ep("b")), queue.upNext)
    }

    // --- start / setQueue / add ---

    @Test
    fun `Q-03 start は既存キューを破棄し単一エピソードで開始する`() {
        val queue = PlaybackQueue(items = listOf(ep("x"), ep("y")), currentIndex = 0)

        val result = queue.start(ep("z"))

        assertEquals(listOf(ep("z")), result.items)
        assertEquals(ep("z"), result.current)
    }

    @Test
    fun `Q-04 setQueue は指定位置から再生する`() {
        val queue = PlaybackQueue<TestEpisode>()

        val result = queue.setQueue(listOf(ep("a"), ep("b"), ep("c")), startAt = 1)

        assertEquals(listOf(ep("a"), ep("b"), ep("c")), result.items)
        assertEquals(ep("b"), result.current)
        assertEquals(listOf(ep("c")), result.upNext)
    }

    @Test
    fun `Q-05 setQueue の startAt が負値なら 0 にクランプする`() {
        val queue = PlaybackQueue<TestEpisode>()

        val result = queue.setQueue(listOf(ep("a"), ep("b"), ep("c")), startAt = -5)

        assertEquals(0, result.currentIndex)
        assertEquals(ep("a"), result.current)
    }

    @Test
    fun `Q-06 setQueue の startAt が上限超過なら末尾にクランプする`() {
        val queue = PlaybackQueue<TestEpisode>()

        val result = queue.setQueue(listOf(ep("a"), ep("b"), ep("c")), startAt = 10)

        assertEquals(2, result.currentIndex)
        assertEquals(ep("c"), result.current)
        assertEquals(emptyList<TestEpisode>(), result.upNext)
    }

    @Test
    fun `Q-07 setQueue に空リストを渡すと空キューになる`() {
        val queue = PlaybackQueue<TestEpisode>()

        val result = queue.setQueue(emptyList(), startAt = 0)

        assertEquals(emptyList<TestEpisode>(), result.items)
        assertNull(result.currentIndex)
    }

    @Test
    fun `Q-08 add は末尾に追加する`() {
        val queue = PlaybackQueue(items = listOf(ep("a")), currentIndex = 0)

        val result = queue.add(ep("b"))

        assertEquals(listOf(ep("a"), ep("b")), result.items)
        assertEquals(listOf(ep("b")), result.upNext)
    }

    @Test
    fun `Q-09 add は既に同一 id が含まれていれば無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b")), currentIndex = 0)

        val result = queue.add(ep("b"))

        assertEquals(listOf(ep("a"), ep("b")), result.items)
    }

    // --- playNext ---

    @Test
    fun `Q-10 playNext は現在の直後へ挿入する`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 1)

        val result = queue.playNext(ep("d"))

        assertEquals(listOf(ep("a"), ep("b"), ep("d"), ep("c")), result.items)
        assertEquals(ep("b"), result.current)
        assertEquals(listOf(ep("d"), ep("c")), result.upNext)
    }

    @Test
    fun `Q-11 playNext は既存の重複を取り除いてから現在の直後へ挿入する`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val result = queue.playNext(ep("c"))

        assertEquals(listOf(ep("a"), ep("c"), ep("b")), result.items)
        assertEquals(ep("a"), result.current)
        assertEquals(listOf(ep("c"), ep("b")), result.upNext)
    }

    @Test
    fun `Q-12 playNext は現在再生中と同一 id なら無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val result = queue.playNext(ep("a"))

        assertEquals(queue, result)
    }

    @Test
    fun `Q-13 playNext は現在が無い場合は先頭に挿入し currentIndex は null のまま`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b")), currentIndex = null)

        val result = queue.playNext(ep("c"))

        assertEquals(listOf(ep("c"), ep("a"), ep("b")), result.items)
        assertNull(result.currentIndex)
    }

    // --- jump / advance ---

    @Test
    fun `Q-14 jump は指定 id が既にあればそれを現在位置にする`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val (result, found) = queue.jump("c")

        assertTrue(found)
        assertEquals(ep("c"), result.current)
    }

    @Test
    fun `Q-15 jump は id が見つからなければ found=false でキューは不変`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val (result, found) = queue.jump("zzz")

        assertFalse(found)
        assertEquals(ep("a"), result.current)
    }

    @Test
    fun `Q-16 advance は currentIndex が null かつ非空なら先頭を現在にする`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = null)

        val (result, next) = queue.advance()

        assertEquals(ep("a"), next)
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `Q-17 advance は次があれば currentIndex を進めて返す`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val (result, next) = queue.advance()

        assertEquals(ep("b"), next)
        assertEquals(1, result.currentIndex)
    }

    @Test
    fun `Q-18 advance は末尾で次が無ければ currentIndex を維持して null を返す`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 2)

        val (result, next) = queue.advance()

        assertNull(next)
        assertEquals(ep("c"), result.current)
    }

    @Test
    fun `Q-19 advance は空キューで currentIndex が null なら null を返す`() {
        val queue = PlaybackQueue<TestEpisode>()

        val (_, next) = queue.advance()

        assertNull(next)
    }

    // --- remove ---

    @Test
    fun `Q-20 remove は削除位置が currentIndex より前なら現在を追従する`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 2)

        val result = queue.remove("a")

        assertEquals(listOf(ep("b"), ep("c")), result.items)
        assertEquals(ep("c"), result.current)
    }

    @Test
    fun `Q-21 remove は削除位置が currentIndex と同じなら同位置の次を現在に昇格する`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 1)

        val result = queue.remove("b")

        assertEquals(listOf(ep("a"), ep("c")), result.items)
        assertEquals(ep("c"), result.current)
    }

    @Test
    fun `Q-22 remove は現在が末尾で削除されたら末尾にクランプする`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 2)

        val result = queue.remove("c")

        assertEquals(listOf(ep("a"), ep("b")), result.items)
        assertEquals(ep("b"), result.current)
    }

    @Test
    fun `Q-23 remove は削除位置が currentIndex より後ろなら currentIndex は不変`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 0)

        val result = queue.remove("c")

        assertEquals(listOf(ep("a"), ep("b")), result.items)
        assertEquals(ep("a"), result.current)
    }

    @Test
    fun `Q-24 remove は id が見つからなければ無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = 1)

        val result = queue.remove("zzz")

        assertEquals(queue, result)
        assertEquals(ep("b"), result.current)
    }

    @Test
    fun `Q-25 remove で空になれば currentIndex は null になる`() {
        val queue = PlaybackQueue(items = listOf(ep("a")), currentIndex = 0)

        val result = queue.remove("a")

        assertEquals(emptyList<TestEpisode>(), result.items)
        assertNull(result.currentIndex)
    }

    // --- moveUpNext（SwiftUI onMove 規約＝削除前オフセット方式） ---

    @Test
    fun `Q-26 moveUpNext 前方移動は削除前オフセット方式`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 0, toOffset = 2)

        assertEquals(listOf(ep("c"), ep("b"), ep("d")), result.upNext)
        assertEquals(listOf(ep("a"), ep("c"), ep("b"), ep("d")), result.items)
        assertEquals(ep("a"), result.current)
    }

    @Test
    fun `Q-27 moveUpNext 後方移動は両方式が一致する`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 2, toOffset = 0)

        assertEquals(listOf(ep("d"), ep("b"), ep("c")), result.upNext)
        assertEquals(listOf(ep("a"), ep("d"), ep("b"), ep("c")), result.items)
    }

    @Test
    fun `Q-28 moveUpNext toOffset が upNextCount と等しければ末尾移動`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 0, toOffset = 3)

        assertEquals(listOf(ep("c"), ep("d"), ep("b")), result.upNext)
        assertEquals(listOf(ep("a"), ep("c"), ep("d"), ep("b")), result.items)
    }

    @Test
    fun `Q-29 moveUpNext toOffset が範囲外なら無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 0, toOffset = 4)

        assertEquals(queue, result)
    }

    @Test
    fun `Q-30 moveUpNext from が範囲外なら無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 5, toOffset = 0)

        assertEquals(queue, result)
    }

    @Test
    fun `Q-31 moveUpNext 同位置は無変更`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c"), ep("d")), currentIndex = 0)

        val result = queue.moveUpNext(from = 1, toOffset = 1)

        assertEquals(listOf(ep("b"), ep("c"), ep("d")), result.upNext)
    }

    @Test
    fun `Q-32 moveUpNext は currentIndex が null なら upNext は items 全体を対象にする`() {
        val queue = PlaybackQueue(items = listOf(ep("a"), ep("b"), ep("c")), currentIndex = null)

        val result = queue.moveUpNext(from = 0, toOffset = 2)

        assertEquals(listOf(ep("b"), ep("a"), ep("c")), result.upNext)
        assertEquals(listOf(ep("b"), ep("a"), ep("c")), result.items)
        assertNull(result.currentIndex)
    }
}

/** テスト専用の最小 QueueItem 実装（Podcast DTO 接続前のプレースホルダ）。 */
private data class TestEpisode(override val id: String) : QueueItem

package com.rioikeda.newslisten.core

/**
 * 再生キューが扱う要素が満たすべき最小契約。
 *
 * WHY: Podcast DTO はまだ存在しないため（後続フェーズで接続予定）、
 * id の一意性さえ保証できればキュー操作は成立する。ジェネリクスにすることで
 * DTO 未確定でも本コアを先行実装できる。
 * @see docs/design/shared-playback-spec.md §2.1 状態モデル
 */
interface QueueItem {
    val id: String
}

/**
 * 再生キュー（プレイリスト）の純粋な状態モデル。
 *
 * 不変（イミュータブル）な値オブジェクト。各操作は新しいインスタンスを返す純関数であり、
 * プレイヤー・DOM・AVPlayer 等の外部 I/O に依存しない。
 *
 * @see docs/design/shared-playback-spec.md §2 再生キュー仕様
 */
data class PlaybackQueue<T : QueueItem>(
    val items: List<T> = emptyList(),
    val currentIndex: Int? = null,
) {
    /** 現在再生中の要素。`currentIndex` が null または範囲外なら null。 */
    val current: T?
        get() = currentIndex?.let { items.getOrNull(it) }

    /** 再生待ち（現在より後ろ）。`currentIndex` が null のときは items 全体。 */
    val upNext: List<T>
        get() = if (currentIndex == null) items else items.drop(currentIndex + 1)

    /** 既存キューを破棄し、単一エピソードで開始する。 */
    fun start(item: T): PlaybackQueue<T> = PlaybackQueue(items = listOf(item), currentIndex = 0)

    /** 一覧を指定位置から再生する。範囲外の [startAt] は `[0, items.size - 1]` にクランプする。 */
    fun setQueue(items: List<T>, startAt: Int): PlaybackQueue<T> =
        if (items.isEmpty()) {
            PlaybackQueue()
        } else {
            PlaybackQueue(items = items, currentIndex = startAt.coerceIn(0, items.size - 1))
        }

    /** 末尾に追加する。既に同一 id が含まれていれば no-op（重複防止）。 */
    fun add(item: T): PlaybackQueue<T> =
        if (items.any { it.id == item.id }) this else copy(items = items + item)

    /**
     * 現在の直後へ挿入する（「次に再生」）。
     * 現在再生中と同一 id なら no-op。既存の重複（現在再生中を除く）は取り除いてから挿入する。
     * 現在が無い（[currentIndex] が null）場合は先頭に挿入し、[currentIndex] は null のまま。
     */
    fun playNext(item: T): PlaybackQueue<T> {
        val currentId = current?.id
        if (item.id == currentId) return this

        val filtered = items.filterNot { it.id == item.id }
        // 削除で位置がずれうるため、現在 id から新しい currentIndex を再計算する。
        val newCurrentIndex = currentId?.let { id -> filtered.indexOfFirst { it.id == id } }
        val insertAt = if (newCurrentIndex != null) newCurrentIndex + 1 else 0
        val newItems = filtered.toMutableList().apply { add(insertAt.coerceAtMost(size), item) }

        return PlaybackQueue(items = newItems, currentIndex = newCurrentIndex)
    }

    /**
     * 指定 id が既にキューにあれば、それを現在位置にする。
     * 見つかれば `found = true`。無ければキューは不変で `found = false`。
     */
    fun jump(id: String): Pair<PlaybackQueue<T>, Boolean> {
        val idx = items.indexOfFirst { it.id == id }
        return if (idx < 0) this to false else copy(currentIndex = idx) to true
    }

    /**
     * 次のエピソードへ進む。
     * - 未再生かつ非空 ⟹ 先頭を現在にして返す。
     * - 未再生かつ空 ⟹ null（停止）。
     * - 次がある ⟹ currentIndex を +1 して次を返す。
     * - 末尾で次が無い ⟹ currentIndex は末尾のまま、null を返す（停止）。
     */
    fun advance(): Pair<PlaybackQueue<T>, T?> {
        val i = currentIndex
        if (i == null) {
            return if (items.isEmpty()) this to null else copy(currentIndex = 0) to items[0]
        }
        val next = i + 1
        return if (next >= items.size) this to null else copy(currentIndex = next) to items[next]
    }

    /**
     * 指定 id を削除する。[currentIndex] は現在のアイテムを追従して調整する。
     * - id が無ければ no-op。
     * - 削除後に空 ⟹ currentIndex = null。
     * - 削除位置 < currentIndex ⟹ currentIndex − 1（現在アイテムを追従）。
     * - 削除位置 == currentIndex ⟹ min(currentIndex, size - 1)（次の要素を現在に昇格。末尾ならクランプ）。
     * - 削除位置 > currentIndex ⟹ currentIndex は不変。
     */
    fun remove(id: String): PlaybackQueue<T> {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return this

        val newItems = items.filterIndexed { i, _ -> i != idx }
        val cur = currentIndex ?: return PlaybackQueue(items = newItems, currentIndex = null)
        val newCurrentIndex = when {
            newItems.isEmpty() -> null
            idx < cur -> cur - 1
            idx == cur -> cur.coerceAtMost(newItems.size - 1)
            else -> cur
        }
        return PlaybackQueue(items = newItems, currentIndex = newCurrentIndex)
    }

    /**
     * 待機列（[upNext]）内の 1 要素を並べ替える。再生済み・現在は動かさないため [currentIndex] は不変。
     * [from] / [toOffset] はいずれも upNext 基準のインデックス（キュー全体ではない）。
     *
     * 意味論 = SwiftUI `onMove(fromOffsets:toOffset:)` 規約（削除前オフセット方式）。
     * `toOffset` は要素を取り除く前の位置系で解釈し、`toOffset == upNextCount` は末尾への移動を表す。
     * [from] または [toOffset] が範囲外なら no-op。
     * @see docs/design/shared-playback-spec.md §2.7 moveUpNext（正本アルゴリズム）
     */
    fun moveUpNext(from: Int, toOffset: Int): PlaybackQueue<T> {
        val up = upNext  // 既存プロパティで待機リストを取得
        if (from !in up.indices || toOffset !in 0..up.size) return this

        val base = currentIndex?.let { it + 1 } ?: 0
        val moved = up[from]
        val rest = up.filterIndexed { i, _ -> i != from }
        val insertAt = toOffset - if (from < toOffset) 1 else 0
        val newUp = rest.toMutableList().apply { add(insertAt, moved) }

        return copy(items = items.take(base) + newUp)
    }
}

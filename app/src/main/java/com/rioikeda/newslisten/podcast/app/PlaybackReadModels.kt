package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.EpisodeGlossaryEntry
import com.rioikeda.newslisten.catalog.domain.EpisodeQuizQuestion
import com.rioikeda.newslisten.catalog.domain.EpisodeTranscriptSegment
import java.util.Collections

/**
 * 「何が再生中か」の読み取り用の値。list は [com.rioikeda.newslisten.catalog.domain.Episode.Playable] の読み取り専用 list をそのまま持つ（再複製しない）。
 * NothingPlaying と Errored では持たない。
 */
data class NowPlaying(
    val episodeId: String,
    val displayTitle: String,
    val japaneseIntroText: String,
    val segments: List<EpisodeTranscriptSegment>?,
    val vocabulary: List<EpisodeGlossaryEntry>?,
    val quiz: List<EpisodeQuizQuestion>?,
    val difficulty: String,
)

/** キューの 1 行。画面は domain の `Episode` を読まず、この値だけを読む（android-dec-66: 題名は `japaneseIntroText`）。 */
data class QueueEntry(
    val episodeId: String,
    val displayTitle: String,
    val difficultyCode: String,
    val durationSeconds: Int,
)

/** 待機列の 1 行。並べ替えの可否は位置から導いた値で持ち、画面で index を計算させない。 */
data class UpNextEntry(
    val episodeId: String,
    val displayTitle: String,
    val difficultyCode: String,
    val durationSeconds: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * 画面が読むキューのリードモデル。[nowPlaying] は query `nowPlaying` が null のとき null（android-dec-67）。
 * list は渡された list と共有しない読み取り専用の写し。
 */
class QueueView(
    val nowPlaying: QueueEntry?,
    upNext: List<UpNextEntry>,
    val upNextCount: Int,
) {
    val upNext: List<UpNextEntry> = Collections.unmodifiableList(ArrayList(upNext))

    init {
        require(upNextCount == this.upNext.size) { "upNextCount は upNext の件数と一致しなければならない: $upNextCount != ${this.upNext.size}" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is QueueView && nowPlaying == other.nowPlaying && upNext == other.upNext && upNextCount == other.upNextCount)

    override fun hashCode(): Int = 31 * (31 * (nowPlaying?.hashCode() ?: 0) + upNext.hashCode()) + upNextCount

    override fun toString(): String = "QueueView(nowPlaying=$nowPlaying, upNext=$upNext, upNextCount=$upNextCount)"
}

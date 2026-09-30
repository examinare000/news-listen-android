package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.model.PodcastResponse

/**
 * 再生セッションの状態。遷移関数は新しい値を返し、受信側は変えない。
 * 定義にない「状態 × 操作」は [IllegalStateException]。停止の操作は持たない
 * （呼び出し側が [NothingPlaying] を代入する）。
 *
 * WHY 純粋な値: 遷移表を単体テストで固定し、再生の副作用（player 操作・永続化）と分離するため。
 */
sealed class PlaybackSession {
    data object NothingPlaying : PlaybackSession()
    data class Starting(val episode: PodcastResponse, val resumePosition: Double, val speed: Double) : PlaybackSession()
    data class Active(val episode: PodcastResponse, val speed: Double) : PlaybackSession()
    data class Completed(val episode: PodcastResponse) : PlaybackSession()
    data class Stopped(val episode: PodcastResponse) : PlaybackSession()
    data class Errored(val ref: SessionEpisodeRef, val reason: SessionErrorReason) : PlaybackSession()

    /** NothingPlaying / Stopped / Errored から再生を始める。 */
    fun start(episode: PodcastResponse, resumePosition: Double, speed: Double): Starting = when (this) {
        is NothingPlaying, is Stopped, is Errored -> Starting(episode, resumePosition, speed)
        else -> rejected("start")
    }

    /** Errored から、取り直したエピソードで再試行する。 */
    fun retry(episode: PodcastResponse, resumePosition: Double, speed: Double): Starting = when (this) {
        is Errored -> Starting(episode, resumePosition, speed)
        else -> rejected("retry")
    }

    /** Completed から次のエピソードへ進む。 */
    fun advance(next: PodcastResponse, resumePosition: Double, speed: Double): Starting = when (this) {
        is Completed -> Starting(next, resumePosition, speed)
        else -> rejected("advance")
    }

    /** Active から別のエピソードを即座に再生する。 */
    fun playNow(episode: PodcastResponse, resumePosition: Double, speed: Double): Starting = when (this) {
        is Active -> Starting(episode, resumePosition, speed)
        else -> rejected("playNow")
    }

    fun playerStarted(): Active = when (this) {
        is Starting -> Active(episode, speed)
        else -> rejected("playerStarted")
    }

    fun playerEnded(): Completed = when (this) {
        is Active -> Completed(episode)
        else -> rejected("playerEnded")
    }

    /** Starting / Active から失敗へ。ref と reason の組合せは呼び出し側が決める。 */
    fun fail(ref: SessionEpisodeRef, reason: SessionErrorReason): Errored = when (this) {
        is Starting, is Active -> Errored(ref, reason)
        else -> rejected("fail")
    }

    /** キューが尽きて再生が終わったとき、Completed から Stopped へ。 */
    fun queueExhausted(): Stopped = when (this) {
        is Completed -> Stopped(episode)
        else -> rejected("queueExhausted")
    }

    /** 再生中エピソードが queue の現在位置と一致するか（INV-P1 の session 側）。 */
    fun satisfiesInvariantP1(queueCurrentId: String?): Boolean = when (this) {
        is NothingPlaying -> true
        is Starting -> episode.id == queueCurrentId
        is Active -> episode.id == queueCurrentId
        is Completed -> episode.id == queueCurrentId
        is Stopped -> episode.id == queueCurrentId
        is Errored -> ref.id == queueCurrentId
    }

    private fun rejected(operation: String): Nothing =
        throw IllegalStateException("$operation は ${this::class.simpleName} から呼べません")
}

/** Errored が指すエピソード。DTO を取得済みか、id だけ分かっているかを区別する。 */
sealed interface SessionEpisodeRef {
    val id: String

    data class Loaded(val episode: PodcastResponse) : SessionEpisodeRef {
        override val id: String get() = episode.id
    }

    data class IdOnly(override val id: String) : SessionEpisodeRef
}

sealed interface SessionErrorReason {
    data object SourceUnavailable : SessionErrorReason
    data object FetchFailed : SessionErrorReason
    data object NotPlayable : SessionErrorReason
    data class Player(val reason: PlaybackFailureReason) : SessionErrorReason
}

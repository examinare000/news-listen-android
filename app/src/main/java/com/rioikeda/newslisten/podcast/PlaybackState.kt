package com.rioikeda.newslisten.podcast

/**
 * [PlayerController] が外へ出す再生状態。6 状態は排他。
 * 時間の単位は秒（[PlayerController.positionSeconds] と同じ）。
 * `duration` が null なのは、総再生時間が未確定の間があるため（[PlayerController.durationSeconds] と同じ契約）。
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Loading : PlaybackState
    data class Playing(val position: Double, val duration: Double?) : PlaybackState
    data class Paused(val position: Double, val duration: Double?) : PlaybackState
    data class Ended(val duration: Double?) : PlaybackState
    data class Failed(val reason: PlaybackFailureReason) : PlaybackState
}

/** 再生失敗の分類。呼び出し側が再生ライブラリのエラーコードを知らずに扱えるようにする。 */
sealed interface PlaybackFailureReason {
    data object Source : PlaybackFailureReason
    data object Decode : PlaybackFailureReason
    data object Network : PlaybackFailureReason

    /** 分類表にないコード。原因調査のためコードを保持する。 */
    data class Unknown(val code: Int) : PlaybackFailureReason
}

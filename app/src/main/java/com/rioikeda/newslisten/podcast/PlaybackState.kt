package com.rioikeda.newslisten.podcast

import androidx.media3.common.PlaybackException

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

/** 再生失敗の分類。呼び出し側が Media3 のエラーコードを知らずに扱えるようにする。 */
sealed interface PlaybackFailureReason {
    data object Source : PlaybackFailureReason
    data object Decode : PlaybackFailureReason
    data object Network : PlaybackFailureReason

    /** 分類表にないコード。原因調査のためコードを保持する。 */
    data class Unknown(val code: Int) : PlaybackFailureReason
}

// WHY 明示集合: 数値範囲での推測判定は、範囲内に将来追加される定数を暗黙に分類してしまうため。
@Suppress("UnsafeOptInUsageError")
private val SOURCE_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
)

@Suppress("UnsafeOptInUsageError")
private val NETWORK_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
)

@Suppress("UnsafeOptInUsageError")
private val DECODE_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
)

/**
 * Media3 の `PlaybackException.errorCode` を [PlaybackFailureReason] に分類する。
 * `ERROR_CODE_DECODER_*` は接頭辞が `DECODING_*` と異なるため [PlaybackFailureReason.Unknown] になる。
 */
fun classifyPlaybackError(errorCode: Int): PlaybackFailureReason = when (errorCode) {
    in SOURCE_ERROR_CODES -> PlaybackFailureReason.Source
    in NETWORK_ERROR_CODES -> PlaybackFailureReason.Network
    in DECODE_ERROR_CODES -> PlaybackFailureReason.Decode
    else -> PlaybackFailureReason.Unknown(errorCode)
}

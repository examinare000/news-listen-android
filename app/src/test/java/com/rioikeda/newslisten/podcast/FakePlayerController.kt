package com.rioikeda.newslisten.podcast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [PlayerController] のテスト専用フェイク。
 *
 * 呼び出し履歴を記録しつつ、テストから状態（isPlaying/positionSeconds/durationSeconds）を
 * 手動遷移できるようにする。[com.rioikeda.newslisten.podcast.ExoPlayerController]（実機）に
 * 先行して [PodcastViewModel] の TDD を進めるためのテストダブル。
 *
 * WHY release() 後の使用を例外にする: 実機の ExoPlayer.release() は「以後再利用不可」契約を
 * 持つが、Fake が単に状態をリセットするだけだと [PodcastViewModel] が誤って release() を
 * エピソード切替に使ってしまう回帰をテストで検出できない。この Fake でも同じ契約を
 * 強制することで、契約違反をテスト失敗として顕在化させる。
 */
class FakePlayerController : PlayerController {
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionSeconds = MutableStateFlow(0.0)
    override val positionSeconds: StateFlow<Double> = _positionSeconds

    private val _durationSeconds = MutableStateFlow<Double?>(null)
    override val durationSeconds: StateFlow<Double?> = _durationSeconds

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed

    override var onPlaybackCompleted: (() -> Unit)? = null

    /** prepare に渡された URL の呼び出し履歴。 */
    val prepareCalls: MutableList<String> = mutableListOf()

    /** prepare に渡された [PlaybackMetadata] の呼び出し履歴（[prepareCalls] と対応するインデックス）。 */
    val metadataCalls: MutableList<PlaybackMetadata> = mutableListOf()

    /** play が呼ばれた回数。 */
    var playCallCount = 0
        private set

    /** pause が呼ばれた回数。 */
    var pauseCallCount = 0
        private set

    /** seekTo に渡された秒数の呼び出し履歴。 */
    val seekCalls: MutableList<Double> = mutableListOf()

    /** setSpeed に渡された倍率の呼び出し履歴。 */
    val speedCalls: MutableList<Float> = mutableListOf()

    /** stop が呼ばれた回数。 */
    var stopCallCount = 0
        private set

    /** release が呼ばれた回数。 */
    var releaseCallCount = 0
        private set

    /**
     * release() 済みかどうか。実機（ExoPlayer）の「release 後は再利用不可」契約を
     * Fake でも再現するためのフラグ。これが立った後に prepare()/play() が呼ばれるのは
     * 呼び出し元（[PodcastViewModel]）のバグなので、テストで検出できるよう例外にする。
     */
    private var released = false

    override fun prepare(url: String, metadata: PlaybackMetadata) {
        checkNotReleased()
        prepareCalls.add(url)
        metadataCalls.add(metadata)
    }

    override fun play() {
        checkNotReleased()
        playCallCount++
        _isPlaying.value = true
    }

    override fun pause() {
        pauseCallCount++
        _isPlaying.value = false
    }

    override fun seekTo(seconds: Double) {
        seekCalls.add(seconds)
        _positionSeconds.value = seconds
    }

    override fun setSpeed(speed: Float) {
        speedCalls.add(speed)
        _playbackSpeed.value = speed
    }

    override fun stop() {
        stopCallCount++
        _isPlaying.value = false
        _positionSeconds.value = 0.0
        _durationSeconds.value = null
    }

    override fun release() {
        releaseCallCount++
        released = true
        _isPlaying.value = false
        _positionSeconds.value = 0.0
        _durationSeconds.value = null
    }

    private fun checkNotReleased() {
        if (released) {
            throw AssertionError(
                "PlayerController は release() 後に再利用できない" +
                    "（実機 ExoPlayer の契約。エピソード切替には release() ではなく stop() を使うこと）"
            )
        }
    }

    /** テストから現在の再生位置を直接設定する（PATCH 送信内容・スキップ計算の検証用）。 */
    fun setPosition(seconds: Double) {
        _positionSeconds.value = seconds
    }

    /** テストから総再生時間を直接設定する（スキップ時の上限クランプ検証用）。 */
    fun setDuration(seconds: Double?) {
        _durationSeconds.value = seconds
    }

    /** テストから再生完了を模擬する。 */
    fun completePlayback() {
        onPlaybackCompleted?.invoke()
    }
}

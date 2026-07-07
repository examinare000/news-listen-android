package com.rioikeda.newslisten.podcast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Media3 ExoPlayer を用いた PlayerController 実装（フェーズ5 T3）。
 *
 * ExoPlayer API はメインスレッド制約があるため、全ての操作は Looper.getMainLooper() の
 * Handler 経由で実行される（prepare/play/pause/seekTo 等の状態変更呼び出し）。
 * 位置ポーリングは 0.5 秒毎に実行し、isPlaying/positionSeconds/durationSeconds を
 * StateFlow で更新する（iOS の addPeriodicTimeObserver 相当）。
 *
 * オーディオフォーカス取得は handleAudioFocus=true で自動化する（iOS の .playback + .spokenAudio
 * に相当）。マナーモードでも再生が行われ、他のアプリからのオーディオフォーカス割り込みが
 * フェーズ7 以降で実装される予定。
 *
 * イヤホン抜去での一時停止は setHandleAudioBecomingNoisy(true) で実装（iOS の
 * handleRouteChange:551-558 相当）。
 */
class ExoPlayerController(context: Context) : PlayerController {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // CONTENT_TYPE_SPEECH: 音声コンテンツ（ポッドキャストなど）
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                // USAGE_MEDIA: 一般的なメディア再生用途
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true  // handleAudioFocus: オーディオフォーカス自動取得
        )
        .setHandleAudioBecomingNoisy(true)  // イヤホン抜去で一時停止
        .build()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionSeconds = MutableStateFlow(0.0)
    override val positionSeconds: StateFlow<Double> = _positionSeconds.asStateFlow()

    private val _durationSeconds = MutableStateFlow<Double?>(null)
    override val durationSeconds: StateFlow<Double?> = _durationSeconds.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    override var onPlaybackCompleted: (() -> Unit)? = null

    private var positionPollingRunnable: Runnable? = null

    init {
        // Player.Listener: ExoPlayer のイベント（再生状態変更、STATE_ENDED 等）を観測
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            // メディア準備完了: durationSeconds を確定させる。
                            // WHY: ExoPlayer.duration は Long（非 null）を返すため `!= null` は
                            // 常に true の死んだ条件だった。未確定を表すセンチネルは C.TIME_UNSET。
                            val durationMs = exoPlayer.duration
                            if (durationMs != C.TIME_UNSET && durationMs > 0) {
                                _durationSeconds.value = durationMs / 1000.0
                            }
                        }

                        Player.STATE_ENDED -> {
                            // 再生終了: コールバック発火（onPlaybackCompleted が非 suspend コンテキストなのでここで OK）
                            _isPlaying.value = false
                            onPlaybackCompleted?.invoke()
                        }

                        Player.STATE_IDLE -> {
                            // アイドル状態（未準備）
                            _isPlaying.value = false
                            _positionSeconds.value = 0.0
                            _durationSeconds.value = null
                        }

                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                    _isPlaying.value = isPlayingChanged
                    // 再生開始時にポーリング開始、停止時はポーリング停止
                    if (isPlayingChanged) {
                        startPositionPolling()
                    } else {
                        stopPositionPolling()
                    }
                }
            }
        )
    }

    override fun prepare(url: String, metadata: PlaybackMetadata) {
        // ExoPlayer API はメインスレッド制約。mainHandler 経由で実行。
        mainHandler.post {
            // MediaMetadata に title/artist を載せることで、MediaStyle 通知（ロック画面含む）の
            // タイトル/アーティスト表示に反映される（正本: NowPlayingInfo.swift:36-37）。
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artist)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(mediaMetadata)
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            // prepare() 直後は durationSeconds は未確定（STATE_READY で確定）
            _durationSeconds.value = null
        }
    }

    override fun play() {
        mainHandler.post {
            exoPlayer.play()
        }
    }

    override fun pause() {
        mainHandler.post {
            exoPlayer.pause()
        }
    }

    override fun seekTo(seconds: Double) {
        mainHandler.post {
            exoPlayer.seekTo((seconds * 1000).toLong())
            // 一時停止中は 0.5 秒ポーリングが止まっているため、シーク位置を即時反映する
            _positionSeconds.value = seconds
        }
    }

    override fun setSpeed(speed: Float) {
        mainHandler.post {
            exoPlayer.setPlaybackSpeed(speed)
            _playbackSpeed.value = speed
        }
    }

    override fun stop() {
        // exoPlayer.stop() 単体ではプレイリスト/位置は保持され再開可能なままなので
        // （Media3 の意図的な変更点）、clearMediaItems() で完全にリセットし
        // 次の prepare() が汚染された状態を引き継がないようにする。
        mainHandler.post {
            stopPositionPolling()
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    override fun release() {
        mainHandler.post {
            stopPositionPolling()
            exoPlayer.release()
        }
    }

    /**
     * 0.5 秒毎にポーリング開始（iOS の addPeriodicTimeObserver 相当）。
     * iOS では CMTimebaseCreateSourceTimer を用いて精密なタイミングで観測するが、
     * Android では Handler.postDelayed で十分（UI更新が 16ms フレームレート）。
     */
    private fun startPositionPolling() {
        // 既に実行中なら重複起動しない
        if (positionPollingRunnable != null) return

        positionPollingRunnable = object : Runnable {
            override fun run() {
                // 再生中の場合のみ位置を更新
                if (exoPlayer.isPlaying) {
                    val positionMs = exoPlayer.currentPosition
                    _positionSeconds.value = positionMs / 1000.0
                }
                // 次回ポーリングをスケジュール（0.5 秒 = 500ms）
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(positionPollingRunnable!!)
    }

    /**
     * ポーリング停止。再生停止時に呼ばれる。
     */
    private fun stopPositionPolling() {
        positionPollingRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        positionPollingRunnable = null
    }
}

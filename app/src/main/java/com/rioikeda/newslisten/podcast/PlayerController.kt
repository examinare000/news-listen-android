package com.rioikeda.newslisten.podcast

import kotlinx.coroutines.flow.StateFlow

/**
 * 音声再生を担う抽象境界。
 *
 * 実装は [ExoPlayerController]（Media3 ExoPlayer）。テストでは [FakePlayerController] に
 * 差し替える。観測可能な状態を StateFlow で公開するのは、実装の詳細（ポーリング方式や
 * リスナー方式）が変わっても Compose 側やこの interface の利用者（再生の Coordinator）が
 * 変更を強いられないため。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift の `AVPlayer` 操作
 * （play/pause/seek/setSpeed/stop）に相当する操作を抽出したもの。
 *
 * WHY port に終端の解放を置かない理由: 実装はアプリ生存期間中はシングルトンとして DI される
 * （[com.rioikeda.newslisten.di.AppContainer]）ため、エピソード切替のたびに解放すると
 * 2エピソード目以降の再生が一切できなくなる。エピソード切替・一時停止相当の「止めて再利用する」
 * 操作は必ず [stop] を使うこと。終端の解放は具象（[ExoPlayerController]）の操作に留める。
 */
interface PlayerController {
    /** 現在の再生位置（秒）。 */
    val positionSeconds: StateFlow<Double>

    /** 現在の音声の総再生時間（秒）。未確定（prepare 直後等）は null。 */
    val durationSeconds: StateFlow<Double?>

    /** 現在の再生速度（倍率）。既定: 1.0f。 */
    val playbackSpeed: StateFlow<Float>

    /** 再生状態（6 状態は排他）。再生失敗も [PlaybackState.Failed] として現れる。 */
    val state: StateFlow<PlaybackState>

    /**
     * 指定 URL の音声を再生準備する。
     *
     * [metadata] は MediaItem の MediaMetadata に載せ、MediaStyle 通知（ロック画面含む）の
     * タイトル/アーティストとして表示される（正本: NowPlayingInfo.swift:36-37 の
     * `MPMediaItemPropertyTitle`/`MPMediaItemPropertyArtist` 相当）。
     */
    fun prepare(url: String, metadata: PlaybackMetadata)

    /** 再生を開始（または再開）する。 */
    fun play()

    /** 再生を一時停止する。 */
    fun pause()

    /** 指定位置（秒）へシークする。 */
    fun seekTo(seconds: Double)

    /** 再生速度（倍率）を設定する。 */
    fun setSpeed(speed: Float)

    /**
     * 再生を停止し、再利用可能な状態へリセットする（メディア・位置・再生状態をクリア）。
     * この呼び出し後も [prepare]/[play] で別のメディアを再生できる。エピソード切替・停止操作はこちらを使う。
     */
    fun stop()
}

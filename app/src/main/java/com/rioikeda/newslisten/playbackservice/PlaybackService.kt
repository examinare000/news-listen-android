package com.rioikeda.newslisten.playbackservice

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rioikeda.newslisten.MainActivity
import com.rioikeda.newslisten.NewsListenApplication

/**
 * shared-player 方式の MediaSessionService（フェーズ7 T4）。
 *
 * 役割: AppContainer の ExoPlayerController が保有する ExoPlayer インスタンスを
 * 同じ MediaSession で共有し、MediaStyle 通知（ロック画面制御含む）と MediaController
 * 接続層を提供する。
 *
 * 設計上の重要なポイント:
 * 1. **ExoPlayer 所有権**: ExoPlayer インスタンスは AppContainer.ExoPlayerController の所有。
 *    このサービスは MediaSession 構築の際にそのインスタンスを参照するのみ。release() されない。
 *    WHY: ExoPlayer は アプリ生存期間中シングルトンであり、サービスの生と死に依存しない。
 *
 * 2. **MediaSession 所有権**: このサービスが onCreate で構築し、onDestroy で release する。
 *    外部からの MediaController 接続はこの MediaSession 経由で成立する。
 *
 * 3. **通知管理**: Media3 の DefaultMediaNotificationProvider に全てを委譲。
 *    カスタム通知ロジック不要（Media3 が title/artist/playback speed/seek controls 対応）。
 *
 * 正本: docs/adr/066-android-internal-architecture.md（バックグラウンド再生 = MediaSessionService + MediaStyle 通知）/ docs/design/android-design.md。
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // AppContainer から ExoPlayerController を取得し、その ExoPlayer を共有する
        val appContainer = NewsListenApplication.getAppContainer()
        val playerController = appContainer.getPlayerController()
        val player = playerController.player

        // MediaSession 構築: shared-player 方式（MediaController 非同期接続層を導入しない）
        // WHY: ExoPlayer インスタンスを直接共有することで、PlayerController 経由の再生操作と
        // MediaSession 経由のコマンド（通知ボタン押下等）が同一の ExoPlayer に同期される。
        // 通知タップで MainActivity が開くよう setSessionActivity で PendingIntent を設定。
        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // MediaSession のみ release（ExoPlayer 本体は release しない）
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

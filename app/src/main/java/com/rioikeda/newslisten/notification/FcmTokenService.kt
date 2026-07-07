package com.rioikeda.newslisten.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rioikeda.newslisten.MainActivity
import com.rioikeda.newslisten.NewsListenApplication
import com.rioikeda.newslisten.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FCM トークン更新の受信と Podcast 生成完了通知の表示を担う（フェーズ9・プッシュ通知）。
 *
 * 単体テスト対象外（AndroidManifest 経由で OS が生成するクラスであり、FirebaseMessagingService の
 * ライフサイクルメソッドは Robolectric 抜きの素の JVM テストでは検証できないため）。
 * 通知登録ロジック本体は [FcmTokenRegistrar]（JVM テスト対象）に切り出し済みで、
 * このクラスは Android フレームワークとの薄い配線のみを担う。
 *
 * WHY data-only 前提: backend は notification ブロックを送らず data ブロックのみで送信する
 * （P9 spec）。data-only メッセージは foreground/background いずれでも必ず onMessageReceived が
 * 呼ばれるため、通知表示ロジックをアプリ側に一本化でき、OS 自動表示（notification ブロック時の
 * バックグラウンド挙動）との分岐を考慮しなくてよい。
 */
class FcmTokenService : FirebaseMessagingService() {

    // WHY @Suppress("DEPRECATION"): firebase-messaging 25.1.0 では基底クラスの onNewToken 自体が
    // 非推奨化されている（コンパイル時に確認済み）が、後継の代替コールバックは未確定かつ現行 SDK でも
    // トークン更新時に確実に呼ばれる（後方互換維持）。P9 spec が明示するコールバック方式であり、
    // ここだけ差し替えれば追随できるよう FcmTokenRegistrar 側とは疎結合にしてある。
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.Default).launch {
            NewsListenApplication.getAppContainer().getFcmTokenRegistrar().onNewToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // POST_NOTIFICATIONS 未許可時は表示しない（MainActivity のランタイム要求と同じ方針）。
        // WHY checkSelfPermission も併用: areNotificationsEnabled() だけでは Android Lint の
        // MissingPermission（NotificationManagerCompat.notify() は POST_NOTIFICATIONS 必須）が
        // 静的解析で検出できず、ビルドが red になる。API 33 未満はランタイム権限自体が存在しない
        // ため常に許可扱い（MainActivity.onCreate の判定基準と同一）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val title = message.data[KEY_TITLE] ?: return
        val body = message.data[KEY_BODY]
        val podcastId = message.data[KEY_PODCAST_ID]

        ensureNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            podcastId?.let { putExtra(EXTRA_PODCAST_ID, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    /**
     * 専用通知チャンネルを作成する（Media3 のメディア再生通知とは別チャンネル）。
     * createNotificationChannel は冪等（同一 ID なら上書き）のため、毎回呼んでよい。
     */
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_podcast_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        /** MainActivity へ渡す Podcast ID の Intent extra キー（将来の画面遷移で使用）。 */
        const val EXTRA_PODCAST_ID = "podcast_id"

        private const val CHANNEL_ID = "podcast_updates"
        private const val NOTIFICATION_ID = 1001
        private const val KEY_TITLE = "title"
        private const val KEY_BODY = "body"
        private const val KEY_PODCAST_ID = "podcast_id"
    }
}

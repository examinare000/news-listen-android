package com.rioikeda.newslisten.notification.infra

import com.google.firebase.messaging.FirebaseMessaging
import com.rioikeda.newslisten.notification.app.PushTokenSource
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * FCM トークン取得の suspend ラッパ（フェーズ9・プッシュ通知）。
 *
 * WHY suspendCancellableCoroutine: kotlinx-coroutines-play-services への追加依存
 * (Task.await()) を避け、OkHttpApiClient.executeCall と同型のパターンで既存の
 * Task API(addOnCompleteListener) をラップする。取得失敗時は例外を投げず null を返し、
 * 「トークン取得できない」として FcmTokenRegistrar 側に判断を委ねる。
 *
 * WHY @Suppress("DEPRECATION"): firebase-bom 34.15.0 (firebase-messaging 25.1.0) では
 * `FirebaseMessaging.getToken()` が非推奨化されている（コンパイル時に確認済み。register() は
 * Task<Void> しか返さずトークン文字列を取得できないため代替にならない）。ログイン時に
 * その場でトークンを pull 取得する用途では、現時点で Google 公式ドキュメントもこの API を
 * 案内しており、機能上は問題ない。SDK の将来のメジャー更新で本当に削除される場合は
 * [PushTokenSource] を変えずにここだけ差し替えればよい。
 */
class FirebasePushTokenSource : PushTokenSource {
    @Suppress("DEPRECATION")
    override suspend fun token(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            continuation.resume(if (task.isSuccessful) task.result else null)
        }
    }
}

package com.rioikeda.newslisten.notification.app

/** push 通知の token を取得する port。取得できなければ null。 */
interface PushTokenSource {
    suspend fun token(): String?
}

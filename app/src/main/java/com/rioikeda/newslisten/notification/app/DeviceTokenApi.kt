package com.rioikeda.newslisten.notification.app

/** デバイス token の登録の port。解除は持たない（サーバー側の登録はセッション削除の連鎖で消える。android-dec-01）。 */
interface DeviceTokenApi {
    suspend fun register(token: String, platform: String)
}

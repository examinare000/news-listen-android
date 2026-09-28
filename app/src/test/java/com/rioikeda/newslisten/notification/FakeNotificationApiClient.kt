package com.rioikeda.newslisten.notification

import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [FcmTokenRegistrar] のテスト専用フェイク。
 *
 * フェーズ9（プッシュ通知）で使う registerDeviceToken/unregisterDeviceToken のみ挙動を
 * 差し替え可能にする。それ以外はこのテストスイートのスコープ外のため、誤って呼ばれた場合は
 * 即座に失敗させて検出できるよう例外を投げる（auth/feed/podcast の Fake と同じ方針）。
 */
class FakeNotificationApiClient(
    private val onRegisterDeviceToken: suspend (token: String, platform: String) -> Unit = { _, _ -> },
    private val onUnregisterDeviceToken: suspend (token: String, platform: String) -> Unit = { _, _ -> },
) : BaseFakeApiClient() {
    /** registerDeviceToken に渡された (token, platform) の呼び出し履歴。 */
    val registerCalls: MutableList<Pair<String, String>> = mutableListOf()

    /** unregisterDeviceToken に渡された (token, platform) の呼び出し履歴。 */
    val unregisterCalls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun registerDeviceToken(token: String, platform: String) {
        registerCalls.add(token to platform)
        onRegisterDeviceToken(token, platform)
    }

    override suspend fun unregisterDeviceToken(token: String, platform: String) {
        unregisterCalls.add(token to platform)
        onUnregisterDeviceToken(token, platform)
    }
}

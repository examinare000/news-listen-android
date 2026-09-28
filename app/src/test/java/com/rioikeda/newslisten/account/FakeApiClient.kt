package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.UserResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * account パッケージ（[SessionsViewModel] / [AccountViewModel]）のテスト専用フェイク。
 *
 * フェーズ11 P11 T4（ログイン中デバイス管理）で使う listSessions/revokeSession/
 * revokeOtherSessions、および T2+T3（表示名更新・パスワード変更）で使う updateProfile/
 * changePassword のみ挙動を差し替え可能にする。それ以外はスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （settings/FakeApiClient.kt・auth/FakeApiClient.kt と同じ設計方針）。
 */
class FakeApiClient(
    private val onListSessions: suspend () -> SessionsListResponse =
        { error("listSessions is not stubbed") },
    private val onRevokeSession: suspend (id: String) -> Unit =
        { error("revokeSession is not stubbed") },
    private val onRevokeOtherSessions: suspend () -> RevokeSessionsResponse =
        { error("revokeOtherSessions is not stubbed") },
    private val onUpdateProfile: suspend (displayName: String) -> UserResponse =
        { error("updateProfile is not stubbed") },
    private val onChangePassword: suspend (currentPassword: String, newPassword: String) -> Unit =
        { _, _ -> error("changePassword is not stubbed") },
) : BaseFakeApiClient() {

    /** [revokeSession] が呼ばれた回数。current セッションでもガードなく呼ばれることの検証に使う。 */
    var revokeSessionCallCount = 0
        private set

    override suspend fun updateProfile(displayName: String): UserResponse =
        onUpdateProfile(displayName)

    override suspend fun changePassword(currentPassword: String, newPassword: String) =
        onChangePassword(currentPassword, newPassword)

    override suspend fun listSessions(): SessionsListResponse = onListSessions()

    override suspend fun revokeSession(id: String) {
        revokeSessionCallCount++
        onRevokeSession(id)
    }

    override suspend fun revokeOtherSessions(): RevokeSessionsResponse = onRevokeOtherSessions()
}

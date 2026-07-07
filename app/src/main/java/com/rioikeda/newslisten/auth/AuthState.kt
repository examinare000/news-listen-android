package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.model.UserResponse

/**
 * 認証状態。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:83-87
 * （`authStatus: AuthStatus` + `currentUser: AuthUser?` の組）のミラー。iOS は状態とユーザーを
 * 別プロパティで保持するが、Kotlin 版は「Authenticated には必ず user が伴う」という不変条件を
 * 型で表現するため、sealed class 1本に統合する。
 */
sealed class AuthState {
    /** 保存済みトークンで /auth/me を解決する前（起動直後のローディング）。 */
    data object Unknown : AuthState()

    /** 未ログイン。 */
    data object Unauthenticated : AuthState()

    /** ログイン済み。 */
    data class Authenticated(val user: UserResponse) : AuthState()
}

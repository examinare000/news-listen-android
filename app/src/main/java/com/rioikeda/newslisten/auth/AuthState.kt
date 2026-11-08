package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.auth.domain.AccountUser

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
    data class Authenticated(val user: AccountUser) : AuthState()
}

/**
 * 端末ローカル資産（音声キャッシュなど）の持ち主。[userId] は `[A-Za-z0-9_-]+` を満たすときだけ値を持ち、
 * 欠落・形式不正は `null`（その主体ではキャッシュを無効にして動作を続ける。ADR-104 決定 16）。
 * 形式の判断は domain の id 型の生成関数（`of`）が持つ。
 */
data class Subject(val userId: String?, val username: String) {
    /**
     * 同じ主体か。双方の [userId] が非 null なら [userId] の等しさ、どちらかが null なら [username] の等しさで判定する
     * （欠落した id を持つ主体どうしでも、同一主体の更新を離脱と取り違えないため）。
     */
    fun isSameSubjectAs(other: Subject): Boolean =
        if (userId != null && other.userId != null) userId == other.userId else username == other.username

    companion object {
        fun from(user: AccountUser): Subject =
            Subject(userId = user.subjectId?.value, username = user.username)
    }
}

/**
 * 主体離脱の後始末の 1 手順。[name] は失敗の観測（[CleanupIncomplete.parts]）に使う識別子。
 * 手順列の組み立ては構成側（AppContainer）が持ち、実行は [SubjectCleanup] が担う。
 */
data class CleanupStep(val name: String, val run: suspend (Subject) -> Unit)

/** 直近に完了した後始末で失敗した手順の名前。全手順が成功したときは持たない（`null`）。 */
data class CleanupIncomplete(val parts: Set<String>)

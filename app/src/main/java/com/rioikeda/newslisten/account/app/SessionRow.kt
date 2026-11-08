package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.core.ApiException
import java.util.ArrayList
import java.util.Collections

/** 画面に出るログイン中セッション 1 件。 */
data class SessionRow(
    val id: String,
    val deviceLabel: String?,
    val createdAt: String,
    val lastUsedAt: String?,
    val current: Boolean,
)

/** セッション一覧。渡された list は複写して変更不能にする（android-check-06）。 */
class SessionsView(sessions: List<SessionRow>) {
    val sessions: List<SessionRow> = Collections.unmodifiableList(ArrayList(sessions))

    /** 現在のデバイス以外にログイン中のセッションがあるか。 */
    val hasOtherSessions: Boolean = this.sessions.any { !it.current }
}

sealed interface SessionsLoad {
    class Loaded(val view: SessionsView) : SessionsLoad

    class Failed(val cause: ApiException) : SessionsLoad
}

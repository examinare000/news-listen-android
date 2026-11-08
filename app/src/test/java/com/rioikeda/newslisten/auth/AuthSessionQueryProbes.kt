package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.auth.app.AuthSession

/**
 * AppContainer と同じ形で `AuthSession` を組む。後始末（`onSubjectLeave`）・保存済みトークンでの確立（`onSessionRestored`）・認証確立（`onAuthenticated`）・
 * 起動時回収（`onSubjectResolved`）の hook は、実行単位の工場の WRITE の関数に置き換える。`AuthViewModel` の probe も、自分のグラフの中にこれを組む。
 */
internal fun probeAuthSession(env: ProbeEnvironment): AuthSession = AuthSession(
    authApi = env.authApi,
    sessionStore = env.sessionStore,
    dispatcher = env.newDispatcher(),
    onSubjectLeave = env.hooks.write1<Subject>("AuthSession#onSubjectLeave"),
    onSessionRestored = env.hooks.suspendWrite("AuthSession#onSessionRestored"),
    onAuthenticated = env.hooks.suspendWrite("AuthSession#onAuthenticated"),
    onSubjectResolved = env.hooks.write1<Subject?>("AuthSession#onSubjectResolved"),
)

/** `AuthSession` の query は state の getter と `currentSubjectId`。warm-up で認証の遷移（ログイン・再認証・再試行）を済ませた状態を観測する。 */
internal val authSessionQueryProbes: QueryProbeSet = run {
    val tokenCanned = mapOf("SessionStore#load" to ProbeData.repeated<String?>("probe-token"))
    val canned = tokenCanned + mapOf(
        "AuthApi#me" to ProbeData.repeated(ProbeData.accountUser),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val session = owner as AuthSession
        session.login("probe-user", "probe-password")
        session.refreshAuth()
        session.retryRefreshAuth()
    }
    queryProbeSet(
        owner = "AuthSession",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(canned = tokenCanned, warmUp = warmUp),
        ),
        calls = listOf(CallSpec("AuthSession#currentSubjectId")),
        assemble = ::probeAuthSession,
    )
}

package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.RecordingDefaults
import com.rioikeda.newslisten.architecture.queryProbeSet

/**
 * AppContainer と同じ形で `AuthViewModel` を組む。後始末（`onSubjectLeave`）と認証確立（`onAuthenticated`）の hook は、
 * 実行単位の工場の WRITE の関数に置き換える。`AccountViewModel` の probe も、自分のグラフの中にこれを組む。
 */
internal fun probeAuthViewModel(env: ProbeEnvironment): AuthViewModel = AuthViewModel(
    apiClient = env.apiClient,
    sessionStore = env.sessionStore,
    dispatcher = env.newDispatcher(),
    preferencesStore = env.preferencesStore,
    onSubjectLeave = env.hooks.suspendWrite("AuthViewModel#onSubjectLeave"),
    onAuthenticated = env.hooks.suspendWrite("AuthViewModel#onAuthenticated"),
)

/** `AuthViewModel` の query は state の getter だけ。warm-up で認証の遷移（ログイン・再認証・再試行）を済ませた状態を観測する。 */
internal val authViewModelQueryProbes: QueryProbeSet = run {
    val tokenCanned = mapOf("SessionStore#load" to ProbeData.repeated<String?>("probe-token"))
    val canned = tokenCanned + mapOf(
        "ApiClient#me" to ProbeData.repeated(ProbeData.user),
        "ApiClient#fetchPreferences" to ProbeData.repeated(RecordingDefaults.preferencesResponse),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as AuthViewModel
        viewModel.login("probe-user", "probe-password")
        viewModel.refreshAuth()
        viewModel.retryRefreshAuth()
    }
    queryProbeSet(
        owner = "AuthViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(canned = tokenCanned, warmUp = warmUp),
        ),
        assemble = ::probeAuthViewModel,
    )
}

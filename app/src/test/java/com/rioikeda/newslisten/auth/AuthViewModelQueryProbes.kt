package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/** AppContainer と同じ形で `AuthViewModel` を組む。認証状態の持ち主 `AuthSession` は [probeAuthSession] で組む。`AccountViewModel` の probe も、自分のグラフの中にこれを組む。 */
internal fun probeAuthViewModel(env: ProbeEnvironment): AuthViewModel = AuthViewModel(authSession = probeAuthSession(env))

/** `AuthViewModel` の query は state の getter だけ。warm-up で認証の遷移（ログイン・再認証・再試行）を済ませた状態を観測する。 */
internal val authViewModelQueryProbes: QueryProbeSet = run {
    val tokenCanned = mapOf("SessionStore#load" to ProbeData.repeated<String?>("probe-token"))
    val canned = tokenCanned + mapOf(
        "AuthApi#me" to ProbeData.repeated(ProbeData.accountUser),
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

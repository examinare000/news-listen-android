package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/** `SessionsViewModel` の query は一覧の読み。warm-up で失効の command を済ませた状態で観測する。 */
internal val sessionsViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("ApiClient#listSessions" to ProbeData.repeated(ProbeData.sessions))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as SessionsViewModel
        viewModel.loadSessions()
        viewModel.revokeSession("session-other")
        viewModel.revokeOtherSessions()
    }
    queryProbeSet(
        owner = "SessionsViewModel",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        calls = listOf(CallSpec("SessionsViewModel#loadSessions")),
    ) { env -> SessionsViewModel(env.apiClient, env.newDispatcher()) }
}

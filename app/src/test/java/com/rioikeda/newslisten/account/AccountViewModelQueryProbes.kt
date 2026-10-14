package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.auth.probeAuthViewModel

/** `AccountViewModel` の query は state の getter だけ。`AuthViewModel` を自分のグラフの中に 1 つ組む。 */
internal val accountViewModelQueryProbes: QueryProbeSet = run {
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as AccountViewModel
        viewModel.prefillDisplayName()
        viewModel.saveProfile()
        viewModel.changePassword()
    }
    queryProbeSet(
        owner = "AccountViewModel",
        scenarios = listOf(ProbeData.readSuccess(warmUp = warmUp), ProbeData.readFailure(warmUp = warmUp)),
    ) { env ->
        AccountViewModel(
            apiClient = env.apiClient,
            authViewModel = probeAuthViewModel(env),
            dispatcher = env.newDispatcher(),
        )
    }
}

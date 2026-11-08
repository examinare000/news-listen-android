package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.account.app.AccountCommands
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.auth.probeAuthSession

/** `AccountViewModel` の query は state の getter だけ。`AuthSession` を 1 つ組み、`AuthViewModel` と `AccountCommands` の両方へ渡す。 */
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
        val authSession = probeAuthSession(env)
        AccountViewModel(
            accountCommands = AccountCommands(env.accountApi, authSession),
            authViewModel = AuthViewModel(authSession = authSession),
            dispatcher = env.newDispatcher(),
        )
    }
}

package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.model.LoginResponse

/** `PasskeyCredentialsViewModel` の query は一覧の読み。warm-up で削除の command を済ませる。 */
internal val passkeyCredentialsViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("ApiClient#listPasskeyCredentials" to ProbeData.repeated(ProbeData.passkeyCredentials))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as PasskeyCredentialsViewModel
        viewModel.loadCredentials()
        viewModel.deleteCredential("credential-1")
    }
    queryProbeSet(
        owner = "PasskeyCredentialsViewModel",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        calls = listOf(CallSpec("PasskeyCredentialsViewModel#loadCredentials")),
    ) { env -> PasskeyCredentialsViewModel(env.apiClient, env.newDispatcher()) }
}

/** `PasskeyLoginViewModel` の query は state の getter だけ。warm-up でパスキーのログインを済ませた状態を観測する。 */
internal val passkeyLoginViewModelQueryProbes: QueryProbeSet = run {
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PasskeyLoginViewModel).loginWithPasskey() }
    queryProbeSet(
        owner = "PasskeyLoginViewModel",
        scenarios = listOf(ProbeData.readSuccess(warmUp = warmUp), ProbeData.readFailure(warmUp = warmUp)),
    ) { env ->
        PasskeyLoginViewModel(
            apiClient = env.apiClient,
            passkeyProvider = env.passkeyProvider,
            dispatcher = env.newDispatcher(),
            onLoginSuccess = env.hooks.suspendWrite1<LoginResponse>("PasskeyLoginViewModel#onLoginSuccess"),
        )
    }
}

/** `PasskeyRegistrationViewModel` の query は state の getter だけ。warm-up でパスキーの登録を済ませた状態を観測する。 */
internal val passkeyRegistrationViewModelQueryProbes: QueryProbeSet = run {
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PasskeyRegistrationViewModel).register() }
    queryProbeSet(
        owner = "PasskeyRegistrationViewModel",
        scenarios = listOf(ProbeData.readSuccess(warmUp = warmUp), ProbeData.readFailure(warmUp = warmUp)),
    ) { env ->
        PasskeyRegistrationViewModel(
            apiClient = env.apiClient,
            passkeyProvider = env.passkeyProvider,
            dispatcher = env.newDispatcher(),
            onRegistered = env.hooks.suspendWrite("PasskeyRegistrationViewModel#onRegistered"),
        )
    }
}

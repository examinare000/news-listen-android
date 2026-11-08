package com.rioikeda.newslisten.passkey

import com.rioikeda.newslisten.account.app.AccountCommands
import com.rioikeda.newslisten.account.app.SessionQueries
import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.auth.probeAuthSession
import com.rioikeda.newslisten.passkey.app.PasskeyCommands

/** hook 2 つ（登録後・ログイン成功後）を、実行単位の工場の WRITE の関数で渡して `PasskeyCommands` を組む。 */
private fun probePasskeyCommands(env: ProbeEnvironment): PasskeyCommands = PasskeyCommands(
    accountApi = env.accountApi,
    passkeyProvider = env.passkeyProvider,
    onRegistered = env.hooks.suspendWrite("PasskeyCommands#onRegistered"),
    onLoginSuccess = env.hooks.suspendWrite1<IssuedSession>("PasskeyCommands#onLoginSuccess"),
)

/** `PasskeyCredentialsViewModel` の query は一覧の読み。warm-up で削除の command を済ませる。 */
internal val passkeyCredentialsViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("AccountApi#listPasskeyCredentials" to ProbeData.repeated(ProbeData.passkeyRecords))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as PasskeyCredentialsViewModel
        viewModel.loadCredentials()
        viewModel.deleteCredential("credential-1")
    }
    queryProbeSet(
        owner = "PasskeyCredentialsViewModel",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        calls = listOf(CallSpec("PasskeyCredentialsViewModel#loadCredentials")),
    ) { env ->
        PasskeyCredentialsViewModel(
            sessionQueries = SessionQueries(env.accountApi),
            accountCommands = AccountCommands(env.accountApi, probeAuthSession(env)),
            dispatcher = env.newDispatcher(),
        )
    }
}

/** `PasskeyLoginViewModel` の query は state の getter だけ。warm-up でパスキーのログインを済ませた状態を観測する。 */
internal val passkeyLoginViewModelQueryProbes: QueryProbeSet = run {
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PasskeyLoginViewModel).loginWithPasskey() }
    queryProbeSet(
        owner = "PasskeyLoginViewModel",
        scenarios = listOf(ProbeData.readSuccess(warmUp = warmUp), ProbeData.readFailure(warmUp = warmUp)),
    ) { env ->
        PasskeyLoginViewModel(
            passkeyCommands = probePasskeyCommands(env),
            dispatcher = env.newDispatcher(),
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
            passkeyCommands = probePasskeyCommands(env),
            dispatcher = env.newDispatcher(),
        )
    }
}

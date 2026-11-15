package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.onboarding.app.OnboardingCommands

/** `OnboardingCommands` の query は、オンボーディングの状態の読み。warm-up で完了の command を済ませる。 */
internal val onboardingCommandsQueryProbes: QueryProbeSet = run {
    val canned = mapOf("SourcesApi#fetchOnboardingStatus" to ProbeData.repeated(ProbeData.onboardingStatus))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as OnboardingCommands).finishOnboarding() }
    queryProbeSet(
        owner = "OnboardingCommands",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        calls = listOf(CallSpec("OnboardingCommands#refreshOnboardingStatus")),
    ) { env -> OnboardingCommands(env.sourcesApi, env.newDispatcher()) }
}

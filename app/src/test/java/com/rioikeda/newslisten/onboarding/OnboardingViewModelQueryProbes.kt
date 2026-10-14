package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/** `OnboardingViewModel` の query は、おすすめサイトとオンボーディングの状態の読み。warm-up で購読と完了の command を済ませる。 */
internal val onboardingViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "ApiClient#fetchFeaturedSites" to ProbeData.repeated(ProbeData.featuredSites),
        "ApiClient#fetchOnboardingStatus" to ProbeData.repeated(ProbeData.onboardingStatus),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as OnboardingViewModel
        viewModel.subscribe(ProbeData.featuredSite)
        viewModel.finish()
    }
    queryProbeSet(
        owner = "OnboardingViewModel",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        calls = listOf(CallSpec("OnboardingViewModel#load"), CallSpec("OnboardingViewModel#refreshOnboardingStatus")),
    ) { env -> OnboardingViewModel(env.apiClient, env.newDispatcher()) }
}

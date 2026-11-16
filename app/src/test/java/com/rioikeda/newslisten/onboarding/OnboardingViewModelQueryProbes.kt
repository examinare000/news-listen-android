package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.onboarding.app.OnboardingCommands
import com.rioikeda.newslisten.onboarding.ui.OnboardingViewModel
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.app.SourcesState

/** `OnboardingViewModel` の query は、おすすめサイトとオンボーディングの状態の読み。warm-up で購読と完了の command を済ませる。 */
internal val onboardingViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "SourcesApi#fetchFeaturedSites" to ProbeData.repeated(ProbeData.featuredSites),
        "SourcesApi#fetchOnboardingStatus" to ProbeData.repeated(ProbeData.onboardingStatus),
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
    ) { env ->
        val state = SourcesState(env.newDispatcher())
        OnboardingViewModel(
            sourcesCommands = SourcesCommands(env.sourcesApi, state, env.hooks.read("SourcesCommands#isAdminProvider") { false }),
            sourcesQueries = SourcesQueries(env.sourcesApi, state),
            onboardingCommands = OnboardingCommands(env.sourcesApi, env.newDispatcher()),
            dispatcher = env.newDispatcher(),
        )
    }
}

package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.probeStreakQuery
import com.rioikeda.newslisten.preferences.probePreferencesSync

/**
 * `SettingsViewModel` の query。`SettingsViewModel#getPreferencesSyncFailed` は、自分のグラフの `PreferencesSync` の状態を読むだけで書かない。
 * `loadListeningStreak` は、自分のグラフの `StreakQuery` の `refresh` を呼ぶ。 */
internal val settingsViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "ApiClient#fetchSources" to ProbeData.repeated(ProbeData.sources),
        "ApiClient#fetchFeaturedSites" to ProbeData.repeated(ProbeData.featuredSites),
        "ApiClient#fetchGenerationQuota" to ProbeData.repeated(ProbeData.generationQuota),
        "LearningApi#fetchStreak" to ProbeData.increasingStreaks(),
    )
    val warmUp: suspend (Any, com.rioikeda.newslisten.architecture.ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as SettingsViewModel
        viewModel.addSource("Example", "https://example.com/rss")
        viewModel.syncDefaultDifficulty("toeic_600")
    }
    queryProbeSet(
        owner = "SettingsViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(warmUp = warmUp),
        ),
        calls = listOf(
            CallSpec("SettingsViewModel#loadSources"),
            CallSpec("SettingsViewModel#loadFeaturedSites"),
            CallSpec("SettingsViewModel#loadGenerationQuota"),
            CallSpec("SettingsViewModel#loadListeningStreak"),
        ),
    ) { env ->
        SettingsViewModel(
            apiClient = env.apiClient,
            preferencesSync = probePreferencesSync(env),
            dispatcher = env.newDispatcher(),
            isAdminProvider = env.hooks.read("SettingsViewModel#isAdminProvider") { false },
            streakQuery = probeStreakQuery(env),
        )
    }
}

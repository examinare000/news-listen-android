package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.ui.LearningViewModel

/**
 * `LearningViewModel` の query は state の getter だけ。warm-up で `load()` を済ませ、既読の保存（command）が
 * 済んだ状態で観測する。`load()` 自体は command なので呼び方に載せない。
 */
internal val learningViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "LearningApi#fetchDashboard" to ProbeData.repeated(ProbeData.dashboardRecord),
        "LearningApi#fetchVocabularySummary" to ProbeData.repeated(ProbeData.vocabularySummary),
        "LearningApi#hasVocabularyTest" to ProbeData.repeated(true),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as LearningViewModel).load() }
    queryProbeSet(
        owner = "LearningViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(warmUp = warmUp),
        ),
    ) { env ->
        LearningViewModel(
            query = probeLearningDashboardQuery(env),
            commands = probeLearningCommands(env),
        )
    }
}

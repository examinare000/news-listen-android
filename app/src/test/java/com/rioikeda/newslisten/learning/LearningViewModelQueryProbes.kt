package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/** `LearningViewModel#load` は読み込みの中で既読の実績を保存する（既知の違反）。呼出ごとに保存するので同値の書込も数える。 */
internal val learningViewModelQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "LearningViewModel",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf(
                "ApiClient#fetchLearningDashboard" to ProbeData.repeated(ProbeData.dashboard),
                "ApiClient#fetchVocabulary" to ProbeData.repeated(ProbeData.vocabularyList),
                "ApiClient#fetchVocabularyTestSession" to ProbeData.repeated(ProbeData.vocabularyTestSession),
            ),
            noWarmUpReason = "COMMAND は画面の強調を消すだけで、load の観測に前提の状態が要らない",
        ),
        ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では保存に至らない"),
    ),
    calls = listOf(CallSpec("LearningViewModel#load")),
) { env -> LearningViewModel(env.apiClient, env.preferencesStore, env.newDispatcher()) }

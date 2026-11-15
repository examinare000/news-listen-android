package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.app.LearningDashboardQuery

/** AppContainer の主構築子と同じ形（読みの port・設定の store・主体を読む関数・dispatcher）で組む。 */
internal fun probeLearningDashboardQuery(env: ProbeEnvironment): LearningDashboardQuery =
    LearningDashboardQuery(
        api = env.learningApi,
        preferencesStore = env.preferencesStore,
        currentSubject = env.hooks.read("LearningDashboardQuery#currentSubject") { "probe-subject" },
        dispatcher = env.newDispatcher(),
    )

/** `LearningDashboardQuery#load` は読むだけで、port の書込も端末への書込も現れない。 */
internal val learningDashboardQueryQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "LearningDashboardQuery",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf(
                "LearningApi#fetchDashboard" to ProbeData.repeated(ProbeData.dashboardRecord),
                "LearningApi#fetchVocabularySummary" to ProbeData.repeated(ProbeData.vocabularySummary),
                "LearningApi#hasVocabularyTest" to ProbeData.repeated(true),
            ),
            noWarmUpReason = "load は引数なしで呼ばれ、前提の状態が要らない",
        ),
        ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では書込に至らない"),
    ),
    calls = listOf(CallSpec("LearningDashboardQuery#load")),
    assemble = ::probeLearningDashboardQuery,
)

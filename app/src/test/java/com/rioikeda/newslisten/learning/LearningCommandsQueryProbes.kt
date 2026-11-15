package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.preferences.probePreferencesSync

/** probe の持ち主が使う `LearningCommands`。dispatcher は実行単位の scheduler に載るものだけを渡す。 */
internal fun probeLearningCommands(env: ProbeEnvironment): LearningCommands = LearningCommands(
    api = env.learningApi,
    preferencesSync = probePreferencesSync(env),
    dispatcher = env.newDispatcher(),
)

/** `LearningCommands` の query は登録済み語彙の読みと判定。読みの失敗は握られ、書込は現れない。 */
internal val learningCommandsQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "LearningCommands",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf("LearningApi#fetchVocabularySummary" to ProbeData.repeated(ProbeData.vocabularySummary)),
            noWarmUpReason = "読みが済んでいない状態を観測する",
        ),
        ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では登録済みは空のまま"),
    ),
    calls = listOf(
        CallSpec("LearningCommands#loadVocabularyRegistrations"),
        CallSpec("LearningCommands#isVocabularyRegistered") { listOf("podcast-1", "term") },
    ),
    assemble = ::probeLearningCommands,
)

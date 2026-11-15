package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.app.VocabularyTestCommands

/** probe の持ち主が使う `VocabularyTestCommands`。選択肢の並べ替え（`shuffle`）は入力をそのまま返す（乱数を使わない）。 */
internal fun probeVocabularyTestCommands(env: ProbeEnvironment): VocabularyTestCommands = VocabularyTestCommands(
    api = env.vocabularyTestApi,
    dispatcher = env.newDispatcher(),
    shuffle = env.hooks.read1<List<String>, List<String>>("VocabularyTestCommands#shuffle") { it },
)

/** `VocabularyTestCommands` の query は画面の状態の getter だけ。warm-up で単語テストを開始し、自己評価を進めた状態を観測する。 */
internal val vocabularyTestCommandsQueryProbes: QueryProbeSet = run {
    val canned = mapOf("VocabularyTestApi#fetchVocabularyTestSession" to ProbeData.repeated(ProbeData.vocabularyTestSessionRecord))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val commands = owner as VocabularyTestCommands
        commands.startVocabularyTest()
        commands.assess(false)
    }
    queryProbeSet(
        owner = "VocabularyTestCommands",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
        assemble = ::probeVocabularyTestCommands,
    )
}

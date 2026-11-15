package com.rioikeda.newslisten.vocabulary

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.probeVocabularyTestCommands

/**
 * `VocabularyTestViewModel` の query は画面の状態の getter だけ。warm-up で単語テストを開始し、自己評価を進めた状態を観測する。
 * 選択肢の並べ替え（`shuffle`）は入力をそのまま返す工場の関数にする（乱数を使わない）。
 */
internal val vocabularyTestViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("VocabularyTestApi#fetchVocabularyTestSession" to ProbeData.repeated(ProbeData.vocabularyTestSessionRecord))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        val viewModel = owner as VocabularyTestViewModel
        viewModel.load()
        viewModel.assess(false)
    }
    queryProbeSet(
        owner = "VocabularyTestViewModel",
        scenarios = listOf(ProbeData.readSuccess(canned, warmUp), ProbeData.readFailure(warmUp = warmUp)),
    ) { env ->
        VocabularyTestViewModel(probeVocabularyTestCommands(env))
    }
}

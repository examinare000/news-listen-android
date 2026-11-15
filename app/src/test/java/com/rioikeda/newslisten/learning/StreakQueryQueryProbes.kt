package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.learning.app.StreakQuery

/**
 * 聴取ストリークの読みを、AppContainer の主構築子と同じ形（読みの port と dispatcher）で組む。
 * `SettingsViewModel`・`PodcastViewModel`・`PlaybackCoordinator` の probe もこの関数で、それぞれ自分のグラフの中に 1 つ組む
 * （実行単位をまたいで共有しない）。取得は記録 fake の列の読みへ委譲する。
 */
internal fun probeStreakQuery(env: ProbeEnvironment): StreakQuery = StreakQuery(env.learningApi, env.newDispatcher())

/** 聴取日数が 1 回目より 2 回目で大きい列を読ませ、増加の事象を出しても書込が現れないことを観測する。 */
internal val streakQueryQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "StreakQuery",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf("LearningApi#fetchStreak" to ProbeData.increasingStreaks()),
            noWarmUpReason = "増加は treatment の 2 回の refresh の間で起きる。前提の状態が要らない",
        ),
        ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では事象の発火に至らない"),
    ),
    calls = listOf(CallSpec("StreakQuery#refresh")),
    assemble = ::probeStreakQuery,
)

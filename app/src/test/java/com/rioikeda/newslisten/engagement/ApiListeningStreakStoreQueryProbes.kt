package com.rioikeda.newslisten.engagement

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/**
 * 聴取ストリークの store を、AppContainer の主構築子と同じ形（取得を表す関数と dispatcher）で組む。
 * `SettingsViewModel`・`PodcastViewModel` の probe もこの関数で、それぞれ自分のグラフの中に store を 1 つ組む
 * （実行単位をまたいで共有しない）。取得は記録 fake の列の読みへ委譲する。
 */
internal fun probeListeningStreakStore(env: ProbeEnvironment): ApiListeningStreakStore =
    ApiListeningStreakStore(
        env.hooks.suspendRead("ApiListeningStreakStore#fetchListeningStreak") { env.apiClient.fetchListeningStreak() },
        env.newDispatcher(),
    )

/** 聴取日数が 1 回目より 2 回目で大きい列を読ませ、増加の callback の発火（既知でない違反）を観測する。 */
internal val apiListeningStreakStoreQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "ApiListeningStreakStore",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf("ApiClient#fetchListeningStreak" to ProbeData.increasingStreaks()),
            noWarmUpReason = "COMMAND は callback の代入だけ。増加は treatment の 2 回の refresh の間で起きる",
        ),
        ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では callback の発火に至らない"),
    ),
    calls = listOf(CallSpec("ListeningStreakStore#refresh")),
    assemble = ::probeListeningStreakStore,
)

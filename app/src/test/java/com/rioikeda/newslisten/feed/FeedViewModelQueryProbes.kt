package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/**
 * `FeedViewModel` の query の契約。`loadFeed`・`refresh` は読む前に保留を確定送信する（既知の違反）ので、
 * 保留を作る warm-up（`loadFeed` → `star`／`dismiss`）を持つ scenario で観測する。
 */
internal val feedViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("ApiClient#fetchFeed" to ProbeData.repeated(ProbeData.feed))
    queryProbeSet(
        owner = "FeedViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, noWarmUpReason = "保留が無い基本形。保留の確定は warm-up を持つ scenario（保留を作る C1・C2）が観測する"),
            ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では、一覧を前提にした保留を作らない"),
            ProbeData.readSuccess(
                canned,
                name = "C1",
                warmUp = { owner, _ ->
                    val viewModel = owner as FeedViewModel
                    viewModel.loadFeed()
                    viewModel.star(viewModel.articles.value.first())
                },
            ),
            ProbeData.readSuccess(
                canned,
                name = "C2",
                warmUp = { owner, _ ->
                    val viewModel = owner as FeedViewModel
                    viewModel.loadFeed()
                    viewModel.dismiss(viewModel.articles.value.first())
                },
            ),
        ),
        calls = listOf(CallSpec("FeedViewModel#loadFeed"), CallSpec("FeedViewModel#refresh")),
    ) { env -> FeedViewModel(env.apiClient, env.newDispatcher(), env.preferencesStore) }
}

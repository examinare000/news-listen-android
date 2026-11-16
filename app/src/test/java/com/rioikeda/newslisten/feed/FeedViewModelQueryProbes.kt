package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.feed.app.FeedCommands
import com.rioikeda.newslisten.feed.app.FeedQueries
import com.rioikeda.newslisten.feed.app.FeedState
import com.rioikeda.newslisten.feed.ui.FeedViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `FeedViewModel` の query の契約。getter は既定で呼ぶ。`loadFeed`・`refresh` は保留を確定する中継（command）なので
 * query の対象ではなく、保留を作る warm-up（`loadFeed` → `star`／`dismiss`）を持つ scenario で getter が書込を起こさないことを観測する。
 */
internal val feedViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf("FeedApi#fetchFeed" to ProbeData.repeated(ProbeData.feedArticles))
    queryProbeSet(
        owner = "FeedViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, noWarmUpReason = "保留が無い基本形。保留がある状態は warm-up を持つ scenario（保留を作る C1・C2）が観測する"),
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
    ) { env ->
        val state = FeedState(env.newDispatcher())
        FeedViewModel(
            commands = FeedCommands(env.feedApi, state),
            queries = FeedQueries(env.feedApi, state),
            preferences = MutableStateFlow(ProbeData.preferencesView),
        )
    }
}

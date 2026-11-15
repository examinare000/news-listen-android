package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.feed.app.FeedCommands
import com.rioikeda.newslisten.feed.app.FeedQueries
import com.rioikeda.newslisten.feed.app.FeedState
import java.util.Collections
import java.util.WeakHashMap

// warm-up が受け取るのは所有者と環境だけなので、同じ FeedState を共有する FeedCommands をここから引く。
// FeedQueries は equals を上書きしないため同一性で引ける。実行単位を test の JVM の最後まで持たないよう弱い参照にする。
private val commandsByQueries: MutableMap<FeedQueries, FeedCommands> =
    Collections.synchronizedMap(WeakHashMap<FeedQueries, FeedCommands>())

/**
 * `FeedQueries` の query の契約。`loadFeed`・`refresh` は保留があっても `FeedApi#star`・`#dismiss` を送らない
 * （android-check-07）。保留は同じ `FeedState` を共有する `FeedCommands` が warm-up で作る。
 */
internal val feedQueriesQueryProbes: QueryProbeSet = run {
    val canned = mapOf("FeedApi#fetchFeed" to ProbeData.repeated(ProbeData.feedArticles))
    queryProbeSet(
        owner = "FeedQueries",
        scenarios = listOf(
            ProbeData.readSuccess(canned, noWarmUpReason = "保留が無い基本形。保留がある状態は warm-up を持つ scenario（保留を作る C1・C2）が観測する"),
            ProbeData.readFailure(noWarmUpReason = "読みが失敗する scenario では、一覧を前提にした保留を作らない"),
            ProbeData.readSuccess(
                canned,
                name = "C1",
                warmUp = { owner, _ ->
                    val queries = owner as FeedQueries
                    queries.loadFeed()
                    commandsByQueries.getValue(queries).star(queries.articles.value.first().id)
                },
            ),
            ProbeData.readSuccess(
                canned,
                name = "C2",
                warmUp = { owner, _ ->
                    val queries = owner as FeedQueries
                    queries.loadFeed()
                    commandsByQueries.getValue(queries).dismiss(queries.articles.value.first().id)
                },
            ),
        ),
        calls = listOf(CallSpec("FeedQueries#loadFeed"), CallSpec("FeedQueries#refresh")),
    ) { env ->
        val state = FeedState(env.newDispatcher())
        FeedQueries(env.feedApi, state).also { commandsByQueries[it] = FeedCommands(env.feedApi, state) }
    }
}

package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.app.SourcesState
import java.util.Collections
import java.util.WeakHashMap

// warm-up が受け取るのは所有者と環境だけなので、同じ SourcesState を共有する SourcesCommands をここから引く。
// SourcesQueries は equals を上書きしないため同一性で引ける。実行単位を test の JVM の最後まで持たないよう弱い参照にする。
private val commandsByQueries: MutableMap<SourcesQueries, SourcesCommands> =
    Collections.synchronizedMap(WeakHashMap<SourcesQueries, SourcesCommands>())

/**
 * `SourcesQueries` の query の契約。`loadSources`・`loadFeaturedSites`・`loadGenerationQuota` と getter は
 * `SourcesApi` の書込（`addSource`・`updateSource`・`removeSource`・`completeOnboarding`）を呼ばない
 * （android-check-07）。書込は同じ `SourcesState` を共有する `SourcesCommands` が warm-up で済ませる。
 */
internal val sourcesQueriesQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "SourcesApi#fetchSources" to ProbeData.repeated(ProbeData.sources),
        "SourcesApi#fetchFeaturedSites" to ProbeData.repeated(ProbeData.featuredSites),
        "SourcesApi#fetchGenerationQuota" to ProbeData.repeated(ProbeData.generationQuota),
    )
    val warmUp: suspend (Any, com.rioikeda.newslisten.architecture.ProbeEnvironment) -> Unit = { owner, _ ->
        commandsByQueries.getValue(owner as SourcesQueries).addSource("Example", "https://example.com/rss")
    }
    queryProbeSet(
        owner = "SourcesQueries",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(warmUp = warmUp),
        ),
        calls = listOf(
            CallSpec("SourcesQueries#loadSources"),
            CallSpec("SourcesQueries#loadFeaturedSites"),
            CallSpec("SourcesQueries#loadGenerationQuota"),
        ),
    ) { env ->
        val state = SourcesState(env.newDispatcher())
        SourcesQueries(env.sourcesApi, state).also {
            commandsByQueries[it] = SourcesCommands(env.sourcesApi, state, env.hooks.read("SourcesCommands#isAdminProvider") { false })
        }
    }
}

package com.rioikeda.newslisten.catalog

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.domain.Episode

/**
 * AppContainer と同じ形（取得 port・dispatcher・取得後の hook）で `EpisodeCatalog` を組む。
 * `OfflineLibrary`・`PlaybackCoordinator`・`PodcastViewModel` の probe もこの関数で、自分のグラフの中に 1 つ組む
 * （実行単位をまたいで共有しない）。
 */
internal fun probeEpisodeCatalog(env: ProbeEnvironment): EpisodeCatalog = EpisodeCatalog(
    podcastApi = env.podcastApi,
    dispatcher = env.newDispatcher(),
    afterRefresh = env.hooks.suspendWrite1<List<Episode>>("EpisodeCatalog#afterRefresh"),
)

/** `EpisodeCatalog` の query。warm-up で一覧を読み込んだ状態で、`find` と StateFlow の getter が書かないことを観測する。 */
internal val episodeCatalogQueryProbes: QueryProbeSet = run {
    val canned = mapOf("PodcastApi#fetchPodcasts" to ProbeData.repeated(ProbeData.podcastList))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as EpisodeCatalog).refresh() }
    queryProbeSet(
        owner = "EpisodeCatalog",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(warmUp = warmUp),
        ),
        calls = listOf(
            CallSpec("EpisodeLookup#find") { listOf("podcast-1") },
        ),
        assemble = ::probeEpisodeCatalog,
    )
}

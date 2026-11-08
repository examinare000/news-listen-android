package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.catalog.probeEpisodeCatalog

/**
 * `PodcastViewModel` の query。warm-up で再生を始め、15 秒周期の位置同期（内部 scope の timer）を張った状態で観測する。
 * timer の書込は control と treatment の両方に現れ、query に帰属しない。
 */
internal val podcastViewModelQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "PodcastApi#fetchPodcasts" to ProbeData.repeated(ProbeData.podcastList),
        "PodcastApi#fetchPodcast" to ProbeData.repeated(ProbeData.podcast),
        "ApiClient#fetchVocabulary" to ProbeData.repeated(ProbeData.vocabularyList),
        "ApiClient#fetchListeningStreak" to ProbeData.increasingStreaks(),
        "AudioStore#isCached" to ProbeData.repeated(false, 64),
    )
    // 先に一覧を読み込んでから id で再生を始める（未知の id は無視されるので、読み込まずに呼ぶと probe が空振りする）。
    // scenario A だけが、再生が始まったことを確かめる。scenario B は一覧の取得が失敗するので、再生は始まらない。
    suspend fun load(owner: Any) {
        val viewModel = owner as PodcastViewModel
        viewModel.fetchPodcasts()
        viewModel.playNow(ProbeData.podcast.id)
    }
    val warmUpSuccess: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ ->
        load(owner)
        check((owner as PodcastViewModel).nowPlaying.value != null) { "warm-up が再生を始められなかった（probe が空振りする）" }
    }
    val warmUpFailure: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> load(owner) }
    queryProbeSet(
        owner = "PodcastViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUpSuccess),
            ProbeData.readFailure(canned = mapOf("AudioStore#isCached" to ProbeData.repeated(false, 64)), warmUp = warmUpFailure),
        ),
        calls = listOf(
            CallSpec("PodcastViewModel#loadVocabularyRegistrations"),
            CallSpec("PodcastViewModel#isVocabularyRegistered") { listOf("podcast-1", "term") },
        ),
    ) { env ->
        val catalog = probeEpisodeCatalog(env)
        val offlineLibrary = probeOfflineLibrary(env, catalog)
        PodcastViewModel(
            apiClient = env.apiClient,
            coordinator = probePlaybackCoordinator(env, offlineLibrary, catalog),
            catalog = catalog,
            offlineLibrary = offlineLibrary,
            dispatcher = env.newDispatcher(),
        )
    }
}

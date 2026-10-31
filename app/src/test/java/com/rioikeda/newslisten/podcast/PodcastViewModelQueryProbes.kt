package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.engagement.probeListeningStreakStore
import com.rioikeda.newslisten.network.AudioCacheManager

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
        "FileSystem#exists" to ProbeData.repeated(false, 64),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PodcastViewModel).play(ProbeData.podcast) }
    queryProbeSet(
        owner = "PodcastViewModel",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(canned = mapOf("FileSystem#exists" to ProbeData.repeated(false, 64)), warmUp = warmUp),
        ),
        calls = listOf(
            CallSpec("PodcastViewModel#fetchPodcasts"),
            CallSpec("PodcastViewModel#loadVocabularyRegistrations"),
            CallSpec("PodcastViewModel#isVocabularyRegistered") { listOf("podcast-1", "term") },
        ),
    ) { env ->
        PodcastViewModel(
            podcastApi = env.podcastApi,
            apiClient = env.apiClient,
            playerController = env.playerController,
            cacheManager = AudioCacheManager(env.fileSystem, "/probe-cache"),
            networkMonitor = env.networkMonitor,
            dispatcher = env.newDispatcher(),
            listeningStreakStore = probeListeningStreakStore(env),
        )
    }
}

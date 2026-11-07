package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.engagement.probeListeningStreakStore
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator

/**
 * AppContainer の主構築子と同じ形（取得 port・player・音声の保存庫を持つ `OfflineLibrary`・ネットワーク監視・設定・
 * 聴取ストリーク・dispatcher・現在の主体を読む関数）で `PlaybackCoordinator` を組む。
 * `OfflineLibrary` は引数で受ける: `PodcastViewModel` の probe が自分のグラフの中で同じインスタンスを
 * `PodcastViewModel` と Coordinator の両方へ渡すため（実行単位をまたいで共有しない）。
 */
internal fun probePlaybackCoordinator(env: ProbeEnvironment, offlineLibrary: OfflineLibrary): PlaybackCoordinator =
    PlaybackCoordinator(
        podcastApi = env.podcastApi,
        playerController = env.playerController,
        offlineLibrary = offlineLibrary,
        networkMonitor = env.networkMonitor,
        preferencesStore = env.preferencesStore,
        listeningStreakStore = probeListeningStreakStore(env),
        dispatcher = env.newDispatcher(),
        currentSubject = env.hooks.read("PlaybackCoordinator#currentSubject") { null },
    )

/**
 * `PlaybackCoordinator` の query。warm-up で再生を始め、15 秒周期の位置同期（内部 scope の timer）を張った状態で観測する。
 * timer の書込は control と treatment の両方に現れ、query に帰属しない。query はすべて getter なので `calls` は無い
 * （runner が宣言から生成する）。
 */
internal val playbackCoordinatorQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "PodcastApi#fetchPodcast" to ProbeData.repeated(ProbeData.podcast),
        "ApiClient#fetchListeningStreak" to ProbeData.increasingStreaks(),
        "AudioStore#isCached" to ProbeData.repeated(false, 64),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PlaybackCoordinator).playNow(ProbeData.podcast) }
    queryProbeSet(
        owner = "PlaybackCoordinator",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(canned = mapOf("AudioStore#isCached" to ProbeData.repeated(false, 64)), warmUp = warmUp),
        ),
    ) { env ->
        probePlaybackCoordinator(env, probeOfflineLibrary(env))
    }
}

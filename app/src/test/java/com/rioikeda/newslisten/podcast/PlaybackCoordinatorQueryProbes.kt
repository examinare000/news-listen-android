package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.probeEpisodeCatalog
import com.rioikeda.newslisten.engagement.probeListeningStreakStore
import com.rioikeda.newslisten.podcast.app.PlaybackCoordinator
import java.util.Collections
import java.util.WeakHashMap

/**
 * AppContainer の主構築子と同じ形（取得 port・player・音声の保存庫を持つ `OfflineLibrary`・ネットワーク監視・設定・
 * 聴取ストリーク・dispatcher・現在の主体を読む関数）で `PlaybackCoordinator` を組む。
 * `OfflineLibrary` と `EpisodeCatalog` は引数で受ける: `PodcastViewModel` の probe が自分のグラフの中で同じインスタンスを
 * `PodcastViewModel` と Coordinator の両方へ渡すため（実行単位をまたいで共有しない）。
 */
internal fun probePlaybackCoordinator(
    env: ProbeEnvironment,
    offlineLibrary: OfflineLibrary,
    catalog: EpisodeCatalog,
): PlaybackCoordinator =
    PlaybackCoordinator(
        podcastApi = env.podcastApi,
        playerController = env.playerController,
        offlineLibrary = offlineLibrary,
        networkMonitor = env.networkMonitor,
        preferencesStore = env.preferencesStore,
        listeningStreakStore = probeListeningStreakStore(env),
        dispatcher = env.newDispatcher(),
        currentSubject = env.hooks.read("PlaybackCoordinator#currentSubject") { null },
        episodeLookup = catalog,
    )

/** warm-up が一覧を読み込むために、実行単位（環境）ごとの `EpisodeCatalog` を引く。環境が消えれば一緒に消える。 */
private val catalogsByEnvironment: MutableMap<ProbeEnvironment, EpisodeCatalog> =
    Collections.synchronizedMap(WeakHashMap())

/**
 * `PlaybackCoordinator` の query。warm-up で再生を始め、15 秒周期の位置同期（内部 scope の timer）を張った状態で観測する。
 * timer の書込は control と treatment の両方に現れ、query に帰属しない。query はすべて getter なので `calls` は無い
 * （runner が宣言から生成する）。
 */
internal val playbackCoordinatorQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "PodcastApi#fetchPodcasts" to ProbeData.repeated(ProbeData.podcastList),
        "PodcastApi#fetchPodcast" to ProbeData.repeated(ProbeData.podcast),
        "ApiClient#fetchListeningStreak" to ProbeData.increasingStreaks(),
        "AudioStore#isCached" to ProbeData.repeated(false, 64),
    )
    // 先に一覧を読み込んでから id で再生を始める（未知の id は無視されるので、読み込まずに呼ぶと probe が空振りする）。
    // scenario A だけが、再生が始まったことを確かめる。scenario B は一覧の取得が失敗するので、再生は始まらない。
    suspend fun load(owner: Any, env: ProbeEnvironment) {
        catalogsByEnvironment.getValue(env).refresh()
        (owner as PlaybackCoordinator).playNow(ProbeData.podcast.id)
    }
    val warmUpSuccess: suspend (Any, ProbeEnvironment) -> Unit = { owner, env ->
        load(owner, env)
        check((owner as PlaybackCoordinator).nowPlaying.value != null) { "warm-up が再生を始められなかった（probe が空振りする）" }
    }
    val warmUpFailure: suspend (Any, ProbeEnvironment) -> Unit = { owner, env -> load(owner, env) }
    queryProbeSet(
        owner = "PlaybackCoordinator",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUpSuccess),
            ProbeData.readFailure(canned = mapOf("AudioStore#isCached" to ProbeData.repeated(false, 64)), warmUp = warmUpFailure),
        ),
    ) { env ->
        val catalog = probeEpisodeCatalog(env).also { catalogsByEnvironment[env] = it }
        probePlaybackCoordinator(env, probeOfflineLibrary(env, catalog), catalog)
    }
}

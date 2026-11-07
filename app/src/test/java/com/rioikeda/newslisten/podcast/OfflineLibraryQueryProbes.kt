package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.network.AudioCacheManager

/**
 * AppContainer の主構築子と同じ形（音声キャッシュ・取得 port・現在の主体を読む関数・dispatcher）で `OfflineLibrary` を組む。
 * `PodcastViewModel` の probe もこの関数で、自分のグラフの中に 1 つ組む（実行単位をまたいで共有しない）。
 */
internal fun probeOfflineLibrary(env: ProbeEnvironment): OfflineLibrary = OfflineLibrary(
    AudioCacheManager(env.fileSystem, "/probe-cache"),
    env.podcastApi,
    env.hooks.read("OfflineLibrary#currentSubject") { "probe-user" },
    env.newDispatcher(),
)

/** `OfflineLibrary` の query。キャッシュの有無は読むだけで、書く port を呼ばないことを観測する。 */
internal val offlineLibraryQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "OfflineLibrary",
    scenarios = listOf(
        ProbeData.readSuccess(
            canned = mapOf("FileSystem#exists" to ProbeData.repeated(false, 64)),
            noWarmUpReason = "query は読むだけで、保留・開始済みの処理を要しない",
        ),
    ),
    calls = listOf(
        CallSpec("OfflineLibrary#isCached") { listOf("probe-user", "podcast-1") },
        CallSpec("OfflineLibrary#cachedFileUri") { listOf("probe-user", "podcast-1") },
        CallSpec("OfflineLibrary#syncDownloadedState") { listOf(ProbeData.podcastList) },
    ),
    assemble = ::probeOfflineLibrary,
)

package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.podcast.app.PositionCommands
import com.rioikeda.newslisten.podcast.app.PositionQueries
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle

// warm-up が受け取るのは所有者と環境だけなので、同じ PositionStore を共有する PositionCommands をここから引く。
// PositionQueries は equals を上書きしないため同一性で引ける。実行単位を test の JVM の最後まで持たないよう弱い参照にする。
private val commandsByQueries: MutableMap<PositionQueries, PositionCommands> =
    Collections.synchronizedMap(WeakHashMap<PositionQueries, PositionCommands>())

/**
 * `PositionQueries` の query の契約。`resumeCandidate` は端末の記録を読むだけで、`PositionStore` の書込と
 * `PodcastApi` の書込を呼ばない（android-query-playback-06）。書込は同じ store を共有する `PositionCommands` が warm-up で済ませ、
 * その背景の再送は warm-up の終わりで進め切る（control と treatment に事象が漏れないように）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val positionQueriesQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "PositionStore#read" to ProbeData.repeated<PositionRecord?>(null, 64),
        "PositionStore#unsent" to ProbeData.repeated(emptyList<PositionRecord>(), 64),
    )
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, env ->
        commandsByQueries.getValue(owner as PositionQueries).record("u1", "podcast-1", 1.0)
        env.scheduler.advanceUntilIdle()
    }
    queryProbeSet(
        owner = "PositionQueries",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(),
        ),
        calls = listOf(
            CallSpec("PositionQueries#resumeCandidate") { listOf("u1", ProbeData.podcast) },
        ),
    ) { env ->
        val commands = PositionCommands(
            env.podcastApi,
            env.positionStore,
            Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC),
            env.hooks.read("PositionCommands#currentSubject") { "u1" },
            env.newDispatcher(),
        )
        PositionQueries(env.positionStore).also { commandsByQueries[it] = commands }
    }
}

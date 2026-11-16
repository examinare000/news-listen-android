package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher

/**
 * [PositionStore] の test double。`MutableMap` に持つ。
 *
 * WHY `adoptIfNewer` を [com.rioikeda.newslisten.podcast.domain.PositionRules] に頼らず自前で比べる:
 * fake が規則を呼ぶと、規則の誤りを fake と production が共有してテストが通ってしまう。規則の正本は `PositionRulesTest`。
 */
class FakePositionStore : PositionStore {
    private val records = LinkedHashMap<Pair<String, String>, PositionRecord>()

    /** [write] に渡された記録の履歴。 */
    val writes: MutableList<PositionRecord> = mutableListOf()

    /** [markSent] の呼出と、その戻り値（同じインデックスが対応する）。 */
    val markSentCalls: MutableList<Triple<String, String, Instant>> = mutableListOf()
    val markSentResults: MutableList<Boolean> = mutableListOf()

    /** [adoptIfNewer] の呼出の履歴と戻り値。 */
    val adoptCalls: MutableList<Triple<String, String, ServerPosition>> = mutableListOf()
    val adoptResults: MutableList<Boolean> = mutableListOf()

    /** [read] が呼ばれた回数。 */
    var readCalls: Int = 0
        private set

    /** 次の [write] だけ [IOException] を投げる。 */
    var failNextWrite: Boolean = false

    /** 非 null の間、[write] はこの完了を待ってから書く（書込の進行中を作る）。 */
    var writeGate: CompletableDeferred<Unit>? = null

    /** 非 null の間、[read] はこの完了を待ってから値を返す。 */
    var readGate: CompletableDeferred<Unit>? = null

    /** 非 null の間、[adoptIfNewer] はこの完了を待ってから比べる。 */
    var adoptGate: CompletableDeferred<Unit>? = null

    /** 履歴を作らずに記録を置く（Given 用）。 */
    fun seed(record: PositionRecord) {
        records[record.subjectId to record.episodeId] = record
    }

    fun recordOf(subjectId: String, episodeId: String): PositionRecord? = records[subjectId to episodeId]

    fun all(): List<PositionRecord> = records.values.toList()

    override suspend fun read(subjectId: String, episodeId: String): PositionRecord? {
        readCalls++
        readGate?.await()
        return records[subjectId to episodeId]
    }

    override suspend fun unsent(subjectId: String): List<PositionRecord> =
        records.values.filter { it.subjectId == subjectId && it.unsent }

    override suspend fun write(record: PositionRecord) {
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("disk full")
        }
        writeGate?.await()
        writes.add(record)
        records[record.subjectId to record.episodeId] = record
    }

    override suspend fun markSent(subjectId: String, episodeId: String, recordedAt: Instant): Boolean {
        markSentCalls.add(Triple(subjectId, episodeId, recordedAt))
        val stored = records[subjectId to episodeId]
        val matched = stored != null && stored.recordedAt == recordedAt
        if (stored != null && matched) records[subjectId to episodeId] = stored.copy(unsent = false)
        markSentResults.add(matched)
        return matched
    }

    override suspend fun adoptIfNewer(subjectId: String, episodeId: String, server: ServerPosition): Boolean {
        adoptCalls.add(Triple(subjectId, episodeId, server))
        adoptGate?.await()
        val stored = records[subjectId to episodeId]
        val serverAt = server.recordedAt
        val adopt = stored != null && serverAt != null && serverAt.isAfter(stored.recordedAt)
        if (adopt && serverAt != null) {
            records[subjectId to episodeId] = PositionRecord(subjectId, episodeId, server.seconds, serverAt, unsent = false)
        }
        adoptResults.add(adopt)
        return adopt
    }

    override suspend fun removeSubject(subjectId: String) {
        records.keys.removeAll { it.first == subjectId }
    }

    override suspend fun reclaim(currentSubjectId: String?) {
        records.keys.removeAll { it.first != currentSubjectId }
    }
}

/** 時刻をテストから動かせる [Clock]。 */
class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

/** 位置の値（[resumeHintSeconds]・[recordedAt]）を持つ再生可能な [Episode] を DTO から作る。 */
fun positionEpisode(
    id: String = "p1",
    resumeHintSeconds: Double = 0.0,
    recordedAt: String? = null,
    durationSeconds: Int = 600,
): Episode = EpisodeDecoder.decode(
    PodcastResponse(
        id = id,
        type = "daily",
        articleIds = listOf("a1"),
        difficulty = "toeic_600",
        audioUrl = "https://example.com/$id.mp3",
        japaneseIntroText = "intro",
        durationSeconds = durationSeconds,
        status = "completed",
        playbackPositionSeconds = resumeHintSeconds,
        playbackPositionRecordedAt = recordedAt,
        createdAt = "2026-07-01T00:00:00Z",
    ),
)

/** Coordinator の構築に渡す [PositionCommands]・[PositionQueries] と、その下の [FakePositionStore]。 */
class PositionWiring(val commands: PositionCommands, val queries: PositionQueries, val store: FakePositionStore)

/**
 * 既存の Coordinator の構築サイトが使う組立。[podcastApi]・[currentSubject]・[dispatcher] は Coordinator に渡すものと同じ値にする
 * （別の instance を渡すと、位置の送信が Coordinator の見ている api と別の所へ流れる）。時計は固定。
 */
fun newPositionWiring(podcastApi: PodcastApi, currentSubject: () -> String?, dispatcher: CoroutineDispatcher): PositionWiring {
    val store = FakePositionStore()
    val commands = PositionCommands(
        podcastApi = podcastApi,
        store = store,
        clock = TestClock(Instant.parse("2026-11-16T00:00:00Z")),
        currentSubject = currentSubject,
        dispatcher = dispatcher,
    )
    return PositionWiring(commands, PositionQueries(store), store)
}

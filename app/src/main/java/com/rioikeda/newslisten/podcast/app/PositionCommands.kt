package com.rioikeda.newslisten.podcast.app

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.network.PodcastApi
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 位置の記録を「どう」保存して送るか（android-cmd-playback-09）。「いつ」記録するかは Coordinator が決める。
 *
 * 記録は port（[PositionStore]）の向こうにあり、ここが持つのは [Mutex] 2 つと scope だけ。
 * - [storeLock]: store への変更を直列にし、変更の直前に主体を照合する。ネットワークを待つ間は持たない。
 * - [sendLock]: 背景の再送どうしを直列にする。[record] の自分の記録の送信は取らない。
 * 主体の照合は、離脱の後に遅れて走った記録・送信・応答が前の主体の記録を作り直したり、別の主体の記録を書き換えたり、
 * 次の主体のトークンで前の主体の記録を送ったりしないための、唯一の守り（shared-leave-11・14）。
 */
class PositionCommands(
    private val podcastApi: PodcastApi,
    private val store: PositionStore,
    private val clock: Clock,
    private val currentSubject: () -> String?,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val storeLock = Mutex()
    private val sendLock = Mutex()

    /**
     * 時刻を付けて保存し、自分の記録 1 件だけをこの呼出の中で送る。付けた時刻を返す（主体が null でも返す）。
     * 自分の記録以外の未送信は、待たずに背景で再送する（遅い再送が次の開始を待たせない）。
     */
    suspend fun record(subjectId: String?, episodeId: String, seconds: Double): Instant = withContext(dispatcher) {
        val recordedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        if (subjectId == null) return@withContext recordedAt
        val record = PositionRecord(subjectId, episodeId, seconds, recordedAt, unsent = true)
        val stillCurrent = storeLock.withLock {
            if (currentSubject() != subjectId) return@withLock false
            // 保存に失敗しても、この記録は引数から作った値なので送る（shared-dec-34）。
            try {
                store.write(record)
            } catch (_: IOException) {
                // 書けなかった記録は store に無いので再送の対象にならない。この回の送信だけを試みる（shared-dec-34）。
            }
            true
        }
        if (stillCurrent) {
            sendOne(record)
            scope.launch { resendOthers(subjectId, except = record) }
        }
        recordedAt
    }

    /** 接続の復帰・主体の確定から呼ばれる。完了まで待つ。 */
    suspend fun resendUnsent(subjectId: String?): Unit = withContext(dispatcher) {
        if (subjectId == null) return@withContext
        resendOthers(subjectId, except = null)
    }

    suspend fun adoptServerValue(subjectId: String?, episodeId: String, server: ServerPosition): Unit = withContext(dispatcher) {
        if (subjectId == null || currentSubject() != subjectId) return@withContext
        storeLock.withLock {
            if (currentSubject() != subjectId) return@withLock
            try {
                store.adoptIfNewer(subjectId, episodeId, server)
            } catch (_: IOException) {
                // 取り込めなくても、再開位置の候補は episode の値と端末の記録を比べて決まる。
            }
        }
    }

    suspend fun adoptEpisodes(subjectId: String?, episodes: List<Episode>): Unit = withContext(dispatcher) {
        for (episode in episodes) {
            adoptServerValue(subjectId, episode.id, ServerPosition(episode.resumeHintSeconds, episode.resumeRecordedAt))
        }
    }

    /** 離脱の後始末。進行中の書込が終わってから消すので、離脱の後に記録が復活しない。例外は握らない。 */
    suspend fun discardSubject(subjectId: String): Unit = withContext(dispatcher) {
        storeLock.withLock { store.removeSubject(subjectId) }
    }

    suspend fun reclaim(currentSubjectId: String?): Unit = withContext(dispatcher) {
        storeLock.withLock {
            try {
                store.reclaim(currentSubjectId)
            } catch (_: IOException) {
                // 次の起動が消す。
            }
        }
    }

    /** `AuthSession.onSubjectResolved` が非 suspend なので、背景の 1 つの coroutine で回収 → 再送の順に行う。 */
    fun onSubjectResolved(subjectId: String?) {
        scope.launch {
            reclaim(subjectId)
            resendUnsent(subjectId)
        }
    }

    private suspend fun sendOne(record: PositionRecord) {
        if (currentSubject() != record.subjectId) return
        val server = try {
            podcastApi.updatePlaybackPosition(record.episodeId, record.seconds, record.recordedAt)
        } catch (e: ApiException) {
            // 404・422 だけが恒久の失敗。送信済みにして再送の対象から外す（記録は再開位置として残す）。
            if (e is ApiException.NotFound || e is ApiException.UnprocessableInput) markSent(record)
            return
        }
        applyResponse(record, server)
    }

    private suspend fun markSent(record: PositionRecord) {
        storeLock.withLock {
            if (currentSubject() != record.subjectId) return@withLock
            try {
                store.markSent(record.subjectId, record.episodeId, record.recordedAt)
            } catch (_: IOException) {
                // 保存の失敗は呼出側へ出さない。未送信の記録は次の送信の契機で再送される。
            }
        }
    }

    private suspend fun applyResponse(record: PositionRecord, server: ServerPosition) {
        if (currentSubject() != record.subjectId) return
        storeLock.withLock {
            try {
                if (currentSubject() != record.subjectId) return@withLock
                store.markSent(record.subjectId, record.episodeId, record.recordedAt)
                if (currentSubject() != record.subjectId) return@withLock
                store.adoptIfNewer(record.subjectId, record.episodeId, server)
            } catch (_: IOException) {
                // 保存の失敗は呼出側へ出さない。未送信の記録は次の送信の契機で再送される。
            }
        }
    }

    private suspend fun resendOthers(subjectId: String, except: PositionRecord?) {
        sendLock.withLock {
            val pending = try {
                store.unsent(subjectId)
            } catch (_: IOException) {
                return@withLock
            }
            for (record in pending) {
                if (except != null && record.episodeId == except.episodeId && record.recordedAt == except.recordedAt) continue
                if (currentSubject() != subjectId) return@withLock
                sendOne(record)
            }
        }
    }
}

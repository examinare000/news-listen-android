package com.rioikeda.newslisten.podcast.infra

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rioikeda.newslisten.podcast.FakePodcastApi
import com.rioikeda.newslisten.podcast.app.PositionCommands
import com.rioikeda.newslisten.podcast.app.PositionQueries
import com.rioikeda.newslisten.podcast.app.TestClock
import com.rioikeda.newslisten.podcast.app.positionEpisode
import com.rioikeda.newslisten.podcast.domain.PositionRecord
import com.rioikeda.newslisten.podcast.domain.ServerPosition
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DataStorePositionStore]（adapter）の保存の層の検証。一時ファイルの実 DataStore に対して行い、
 * 再起動をまたぐ読み戻し・比較と書込の原子性・壊れた値・主体の接頭辞を確かめる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePositionStoreTest {

    private val t0 = Instant.parse("2026-11-16T01:02:02.456Z")
    private val t1 = Instant.parse("2026-11-16T01:02:03.456Z")
    private val t2 = Instant.parse("2026-11-16T01:02:04.456Z")

    private class Opened(val dataStore: DataStore<Preferences>, val store: DataStorePositionStore, val job: Job)

    private fun TestDispatcher.open(file: File): Opened {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + this), produceFile = { file })
        return Opened(dataStore, DataStorePositionStore(dataStore), job)
    }

    private fun tempFile(name: String): File = File.createTempFile(name, ".preferences_pb").apply { deleteOnExit() }

    private suspend fun DataStore<Preferences>.positionKeys(): Set<String> =
        data.first().asMap().keys.map { it.name }.filter { it.startsWith("pos/") }.toSet()

    @Test
    fun `android-possync-05 shared-sync-01 保存した記録はDataStoreを作り直しても読み戻せる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = tempFile("pos_restart")
        val first = dispatcher.open(file)
        assertNull("Given: 空", first.store.read("u1", "p1"))

        first.store.write(PositionRecord("u1", "p1", 12.5, t1, unsent = true))
        first.store.write(PositionRecord("u1", "p2", 3.0, t2, unsent = false))
        advanceUntilIdle()
        first.job.cancel() // アプリ終了を模擬: アクティブファイルのロックを解放する
        advanceUntilIdle()

        val second = dispatcher.open(file)
        assertEquals(PositionRecord("u1", "p1", 12.5, t1, unsent = true), second.store.read("u1", "p1"))
        assertEquals(PositionRecord("u1", "p2", 3.0, t2, unsent = false), second.store.read("u1", "p2"))
        assertEquals(listOf("p1"), second.store.unsent("u1").map { it.episodeId })
    }

    @Test
    fun `writeは同じ主体とエピソードを無条件に置き換え同じ値の2回は結果が同じ`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_write"))
        val older = PositionRecord("u1", "p1", 50.0, t2, unsent = false)
        val newer = PositionRecord("u1", "p1", 10.0, t0, unsent = true)
        opened.store.write(older)

        opened.store.write(newer) // 時刻が古くても無条件（比較は呼出側の責務ではなく write の契約）
        opened.store.write(newer)

        assertEquals(newer, opened.store.read("u1", "p1"))
        assertEquals(1, opened.store.unsent("u1").size)
    }

    @Test
    fun `android-possync-17 shared-sync-04 markSentは記録時刻が一致するときだけ送信済みにする`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_marksent"))
        opened.store.write(PositionRecord("u1", "p1", 20.0, t2, unsent = true))

        assertFalse("古い時刻では変えない", opened.store.markSent("u1", "p1", t1))
        assertTrue("未送信のまま", opened.store.read("u1", "p1")!!.unsent)
        assertFalse("記録が無い", opened.store.markSent("u1", "no-such", t2))

        assertTrue("一致する時刻", opened.store.markSent("u1", "p1", t2))
        assertFalse(opened.store.read("u1", "p1")!!.unsent)
        assertTrue("2 回目も true で状態は同じ", opened.store.markSent("u1", "p1", t2))
        assertEquals(PositionRecord("u1", "p1", 20.0, t2, unsent = false), opened.store.read("u1", "p1"))
    }

    @Test
    fun `android-possync-21 shared-sync-06 adoptIfNewerは新しいときだけ原子的に置き換える`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_adopt"))
        val original = PositionRecord("u1", "p1", 40.0, t1, unsent = true)
        opened.store.write(original)

        assertFalse("古い", opened.store.adoptIfNewer("u1", "p1", ServerPosition(90.0, t0)))
        assertFalse("同じ時刻", opened.store.adoptIfNewer("u1", "p1", ServerPosition(90.0, t1)))
        assertFalse("時刻なし", opened.store.adoptIfNewer("u1", "p1", ServerPosition(90.0, null)))
        assertEquals("変わらない", original, opened.store.read("u1", "p1"))

        assertTrue("厳密に新しい", opened.store.adoptIfNewer("u1", "p1", ServerPosition(90.0, t2)))
        assertEquals(PositionRecord("u1", "p1", 90.0, t2, unsent = false), opened.store.read("u1", "p1"))
        assertFalse("同じ server の 2 回目は同時刻", opened.store.adoptIfNewer("u1", "p1", ServerPosition(90.0, t2)))

        assertFalse("記録が無ければ作らない", opened.store.adoptIfNewer("u1", "none", ServerPosition(90.0, t2)))
        assertNull(opened.store.read("u1", "none"))
        assertFalse("記録が無いキーは作られていない", opened.dataStore.positionKeys().contains("pos/u1/none"))
    }

    @Test
    fun `android-possync-35 shared-leave-12 reclaimは現在の主体の記録を残し他の主体と主体なしの値を消す`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_reclaim"))
        opened.store.write(PositionRecord("u1", "p1", 1.0, t1, unsent = true))
        opened.store.write(PositionRecord("u2", "p1", 2.0, t1, unsent = true))
        opened.dataStore.edit { it[stringPreferencesKey("pos/orphan")] = """{"s":3.0,"t":${t1.toEpochMilli()},"u":true}""" }
        assertEquals(setOf("pos/u1/p1", "pos/u2/p1", "pos/orphan"), opened.dataStore.positionKeys())

        opened.store.reclaim("u1")
        opened.store.reclaim("u1")

        assertEquals(setOf("pos/u1/p1"), opened.dataStore.positionKeys())

        opened.store.reclaim(null)

        assertEquals(emptySet<String>(), opened.dataStore.positionKeys())
    }

    @Test
    fun `android-possync-27 shared-leave-11 removeSubjectは接頭辞の主体の記録だけを消す`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_remove"))
        opened.store.write(PositionRecord("u1", "p1", 1.0, t1, unsent = true))
        opened.store.write(PositionRecord("u1", "p2", 1.0, t1, unsent = false))
        // 接頭辞が前方一致するだけの別の主体（u10）は消えない。
        opened.store.write(PositionRecord("u10", "p1", 5.0, t1, unsent = true))

        opened.store.removeSubject("u1")
        opened.store.removeSubject("u1")

        assertEquals(setOf("pos/u10/p1"), opened.dataStore.positionKeys())
        assertEquals(5.0, opened.store.read("u10", "p1")!!.seconds, 0.0)
    }

    @Test
    fun `android-possync-26 主体か時刻を欠く値・壊れた値は読まれず送られず候補にならず回収で消える`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opened = dispatcher.open(tempFile("pos_legacy"))
        val api = FakePodcastApi(onUpdatePlaybackPosition = { id, _ -> positionEpisode(id) })
        val commands = PositionCommands(
            podcastApi = api,
            store = opened.store,
            clock = TestClock(t2),
            currentSubject = { "u1" },
            dispatcher = dispatcher,
        )
        val queries = PositionQueries(opened.store)
        val epMissingSubject = positionEpisode("p1", resumeHintSeconds = 77.0)
        val epMissingTime = positionEpisode("p2", resumeHintSeconds = 78.0)
        val epBroken = positionEpisode("p3", resumeHintSeconds = 79.0)
        opened.dataStore.edit {
            it[stringPreferencesKey("pos/p1")] = """{"s":1.0,"t":${t1.toEpochMilli()},"u":true}"""
            it[stringPreferencesKey("pos/u1/p2")] = """{"s":1.0,"u":true}"""
            it[stringPreferencesKey("pos/u1/p3")] = "{not json"
            it[stringPreferencesKey("pos/u1/p5")] = """{"s":1.0,"t":${t1.toEpochMilli()},"u":"yes"}"""
        }
        assertEquals("Given: 4 つの旧い形が入っている", 4, opened.dataStore.positionKeys().size)

        assertNull(opened.store.read("u1", "p1"))
        assertNull(opened.store.read("u1", "p2"))
        assertNull(opened.store.read("u1", "p3"))
        assertNull("型違いの値", opened.store.read("u1", "p5"))
        assertTrue(opened.store.unsent("u1").isEmpty())
        commands.resendUnsent("u1")
        advanceUntilIdle()
        assertTrue("送られない", api.updatePlaybackPositionCalls.isEmpty())
        assertEquals(77.0, queries.resumeCandidate("u1", epMissingSubject), 0.0)
        assertEquals(78.0, queries.resumeCandidate("u1", epMissingTime), 0.0)
        assertEquals(79.0, queries.resumeCandidate("u1", epBroken), 0.0)

        // 対照: 正しい値は読める・送られる・回収で残る。
        opened.store.write(PositionRecord("u1", "p4", 9.0, t1, unsent = true))
        assertEquals(9.0, opened.store.read("u1", "p4")!!.seconds, 0.0)
        commands.resendUnsent("u1")
        advanceUntilIdle()
        assertEquals(listOf("p4" to 9.0), api.updatePlaybackPositionCalls)

        opened.store.reclaim("u1")

        assertEquals(setOf("pos/u1/p4"), opened.dataStore.positionKeys())
    }
}

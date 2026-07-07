package com.rioikeda.newslisten.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [DataStorePreferencesStore] の挙動検証。Context を使わず
 * `PreferenceDataStoreFactory.create(produceFile = ...)` で一時ファイルに対して実際の
 * DataStore I/O を行い、コンパイルだけでなく永続化の実挙動を検証する（フェーズ10 P10 Task3）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePreferencesStoreTest {

    /**
     * [DataStore][androidx.datastore.core.DataStore] は同一ファイルへの同時アクティブインスタンスを
     * 禁止するため、呼び出し元ごとに専用の [Job] を渡し、インスタンスを使い終えたら
     * `job.cancel()` してからでないと同じファイルで新しいインスタンスを作れない
     * （起動→終了→再起動を模擬する [起動時に永続化済みの値を新しいインスタンスへ復元する] で使用）。
     */
    private fun TestDispatcher.newStore(file: File, job: Job = Job()): DataStorePreferencesStore {
        val scope = CoroutineScope(job + this)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return DataStorePreferencesStore(dataStore, scope)
    }

    @Test
    fun 未初期化のファイルでは既定値を返す() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_default", ".preferences_pb").apply { deleteOnExit() }

        val store = dispatcher.newStore(file)
        advanceUntilIdle()

        assertEquals("toeic_600", store.defaultDifficulty.value)
        assertEquals(1.0, store.defaultPlaybackSpeed.value, 0.0)
        assertEquals(ArticleOpenMode.IN_APP, store.articleOpenMode.value)
        assertEquals(TimeFormat.ABSOLUTE, store.timeFormat.value)
    }

    @Test
    fun set系メソッドで書き込んだ値がStateFlowへ反映される() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_set", ".preferences_pb").apply { deleteOnExit() }
        val store = dispatcher.newStore(file)
        advanceUntilIdle()

        store.setDefaultDifficulty("toeic_900")
        store.setDefaultPlaybackSpeed(1.5)
        store.setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        store.setTimeFormat(TimeFormat.RELATIVE)
        advanceUntilIdle()

        assertEquals("toeic_900", store.defaultDifficulty.value)
        assertEquals(1.5, store.defaultPlaybackSpeed.value, 0.0)
        assertEquals(ArticleOpenMode.EXTERNAL, store.articleOpenMode.value)
        assertEquals(TimeFormat.RELATIVE, store.timeFormat.value)
    }

    @Test
    fun 起動時に永続化済みの値を新しいインスタンスへ復元する() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_restart", ".preferences_pb").apply { deleteOnExit() }
        val firstJob = Job()
        val firstStore = dispatcher.newStore(file, firstJob)
        advanceUntilIdle()
        firstStore.setDefaultDifficulty("ielts_7")
        firstStore.setDefaultPlaybackSpeed(2.0)
        firstStore.setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        firstStore.setTimeFormat(TimeFormat.RELATIVE)
        advanceUntilIdle()
        // アプリ終了を模擬: DataStore のアクティブファイルロックを解放する。
        firstJob.cancel()
        advanceUntilIdle()

        // アプリ再起動を模擬: 同じファイルに対する新しい DataStore/Store インスタンス。
        val secondStore = dispatcher.newStore(file)
        advanceUntilIdle()

        assertEquals("ielts_7", secondStore.defaultDifficulty.value)
        assertEquals(2.0, secondStore.defaultPlaybackSpeed.value, 0.0)
        assertEquals(ArticleOpenMode.EXTERNAL, secondStore.articleOpenMode.value)
        assertEquals(TimeFormat.RELATIVE, secondStore.timeFormat.value)
    }
}

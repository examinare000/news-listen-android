package com.rioikeda.newslisten.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.rioikeda.newslisten.preferences.domain.PreferenceItem
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [DataStorePreferencesStore] の挙動検証。Context を使わず
 * `PreferenceDataStoreFactory.create(produceFile = ...)` で一時ファイルに対して実際の
 * DataStore I/O を行い、コンパイルだけでなく永続化の実挙動を検証する（フェーズ10 P10 Task3）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePreferencesStoreTest {
    @Test
    fun `フィードバック設定は既定で有効でDataStoreへ永続化する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_feedback", ".preferences_pb").apply { deleteOnExit() }
        val store = dispatcher.newStore(file)
        advanceUntilIdle()

        assertTrue(store.sfxEnabled.value)
        assertTrue(store.hapticsEnabled.value)
        assertEquals(3, store.weeklyGoalEpisodes.value)

        store.setSfxEnabled(false)
        store.setHapticsEnabled(false)
        store.setWeeklyGoalEpisodes(7)
        advanceUntilIdle()

        assertFalse(store.sfxEnabled.value)
        assertFalse(store.hapticsEnabled.value)
        assertEquals(7, store.weeklyGoalEpisodes.value)
    }

    @Test
    fun `表示済み実績IDをDataStoreへ重複なく永続化する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_achievements", ".preferences_pb").apply { deleteOnExit() }
        val store = dispatcher.newStore(file)
        advanceUntilIdle()

        store.markAchievementsSeen(setOf("streak_7", "completed_10"))
        store.markAchievementsSeen(setOf("streak_7"))
        advanceUntilIdle()

        assertEquals(setOf("streak_7", "completed_10"), store.seenAchievementIds.value)
    }

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
        assertEquals(3, store.weeklyGoalEpisodes.value)
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
    fun setDefaultPlaybackSpeedは8段外の値を拒否し直前の値を維持する() = runTest {
        // verifies: android-contract-17
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_speed_reject", ".preferences_pb").apply { deleteOnExit() }
        val store = dispatcher.newStore(file)
        advanceUntilIdle()
        store.setDefaultPlaybackSpeed(1.5)
        advanceUntilIdle()
        assertEquals(1.5, store.defaultPlaybackSpeed.value, 0.0)

        listOf(0.0, 3.0, 1.0001, -1.0, Double.NaN).forEach { rejected ->
            store.setDefaultPlaybackSpeed(rejected)
            advanceUntilIdle()

            assertEquals(1.5, store.defaultPlaybackSpeed.value, 0.0)
        }
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

    private suspend fun DataStorePreferencesStore.writeAllEightNonDefault() {
        setDefaultDifficulty("toeic_900")
        setDefaultPlaybackSpeed(2.0)
        setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        setTimeFormat(TimeFormat.RELATIVE)
        setSfxEnabled(false)
        setHapticsEnabled(false)
        setWeeklyGoalEpisodes(9)
        markAchievementsSeen(setOf("streak_7"))
    }

    @Test
    fun `shared-dec-02 clearSubjectScopedは主体依存の4keyだけを消し端末設定の4keyを残す`() = runTest {
        // verifies: shared-dec-02
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_clear_subject", ".preferences_pb").apply { deleteOnExit() }
        val store = dispatcher.newStore(file)
        advanceUntilIdle()
        store.writeAllEightNonDefault()
        advanceUntilIdle()

        store.clearSubjectScoped()
        store.clearSubjectScoped()
        advanceUntilIdle()

        assertEquals("toeic_600", store.defaultDifficulty.value)
        assertEquals(1.0, store.defaultPlaybackSpeed.value, 0.0)
        assertEquals(3, store.weeklyGoalEpisodes.value)
        assertEquals(emptySet<String>(), store.seenAchievementIds.value)
        assertEquals(ArticleOpenMode.EXTERNAL, store.articleOpenMode.value)
        assertEquals(TimeFormat.RELATIVE, store.timeFormat.value)
        assertFalse(store.sfxEnabled.value)
        assertFalse(store.hapticsEnabled.value)
    }

    @Test
    fun `android-rule-prefs-04 PreferenceItemのkeyの集合は全8項目を保存したDataStoreのkeyの集合と一致する`() = runTest {
        // verifies: android-rule-prefs-04
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = File.createTempFile("prefs_key_set", ".preferences_pb").apply { deleteOnExit() }
        val job = Job()
        val scope = CoroutineScope(job + dispatcher)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val store = DataStorePreferencesStore(dataStore, scope)
        advanceUntilIdle()
        store.writeAllEightNonDefault()
        advanceUntilIdle()

        val keyNames = dataStore.data.first().asMap().keys.map { it.name }.toSet()

        assertEquals(PreferenceItem.entries.map { it.key }.toSet(), keyNames)
        job.cancel()
    }
}

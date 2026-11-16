package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.app.AchievementRecord
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningDashboardQuery
import com.rioikeda.newslisten.learning.app.RecordingPreferencesStore
import com.rioikeda.newslisten.learning.app.VocabularyRecord
import com.rioikeda.newslisten.learning.app.VocabularySummary
import com.rioikeda.newslisten.learning.app.dashboardRecord
import com.rioikeda.newslisten.learning.ui.LearningViewModel
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.app.PreferencesApi
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.preferences.app.ServerPreferences
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningViewModelTest {

    private object NoopPreferencesApi : PreferencesApi {
        override suspend fun fetchPreferences(): ServerPreferences = ServerPreferences("toeic_600", 1.0, 3)
        override suspend fun updateDefaultDifficulty(code: String) {}
        override suspend fun updateDefaultPlaybackSpeed(speed: Double) {}
        override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {}
    }

    private class Rig(
        val viewModel: LearningViewModel,
        val api: FakeLearningApi,
        val store: RecordingPreferencesStore,
        val log: MutableList<String>,
    ) {
        var subject: String? = "u-1"
    }

    private fun TestScope.rig(seen: Set<String> = emptySet()): Rig {
        val log = mutableListOf<String>()
        val api = FakeLearningApi(log)
        val store = RecordingPreferencesStore(log, InMemoryPreferencesStore(initialSeenAchievementIds = seen))
        lateinit var rig: Rig
        val query = LearningDashboardQuery(api, store, { rig.subject }, StandardTestDispatcher(testScheduler))
        val commands = LearningCommands(api, PreferencesSync(NoopPreferencesApi, store, { rig.subject }, backgroundScope), StandardTestDispatcher(testScheduler))
        rig = Rig(LearningViewModel(query, commands), api, store, log)
        return rig
    }

    @Test
    fun `初回ロードでも未表示の解錠実績を祝福対象にして表示済みへ保存する`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = {
            dashboardRecord(
                achievements = listOf(
                    AchievementRecord("first_episode_completed", "2026-07-29"),
                    AchievementRecord("streak_7", "2026-07-29"),
                ),
            )
        }

        rig.viewModel.load()

        assertEquals(
            listOf("first_episode_completed", "streak_7"),
            rig.viewModel.uiState.value.newlyUnlockedAchievements.map { it.id },
        )
        assertEquals(
            setOf("first_episode_completed", "streak_7"),
            rig.store.seenAchievementIds.value,
        )
    }

    @Test
    fun `表示済み実績を除いた差分だけを祝福する`() = runTest {
        val rig = rig(seen = setOf("first_episode_completed"))
        rig.api.onFetchDashboard = {
            dashboardRecord(
                achievements = listOf(
                    AchievementRecord("first_episode_completed", "2026-07-29"),
                    AchievementRecord("completed_10", "2026-07-30"),
                ),
            )
        }

        rig.viewModel.load()

        assertEquals(
            listOf("completed_10"),
            rig.viewModel.uiState.value.newlyUnlockedAchievements.map { it.id },
        )
        assertEquals(
            setOf("first_episode_completed", "completed_10"),
            rig.store.seenAchievementIds.value,
        )
    }

    @Test
    fun `登録語彙は最新5件に絞り期日語がある時だけ単語テストを表示する`() = runTest {
        val rig = rig()
        rig.api.onFetchVocabularySummary = {
            VocabularySummary(7, (1..7).map { VocabularyRecord("p-$it", "term-$it", "意味-$it", "2026-07-${30 - it}") })
        }
        rig.api.onHasVocabularyTest = { true }

        rig.viewModel.load()

        val dashboard = checkNotNull(rig.viewModel.uiState.value.dashboard)
        assertEquals(7, dashboard.vocabularyCount)
        assertEquals((1..5).map { "term-$it" }, dashboard.recentVocabulary.map { it.term })
        assertTrue(dashboard.hasVocabularyTest)
    }

    @Test
    fun `期日語が0件なら単語テスト導線を表示しない`() = runTest {
        val rig = rig()
        rig.api.onHasVocabularyTest = { false }

        rig.viewModel.load()

        assertFalse(checkNotNull(rig.viewModel.uiState.value.dashboard).hasVocabularyTest)
    }

    @Test
    fun `android-check-07 android-deriv-23 loadは読みの3操作の後に既読の保存を1回だけ行う`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = { dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29"))) }

        rig.viewModel.load()

        assertEquals(
            listOf("fetchDashboard", "fetchVocabularySummary", "hasVocabularyTest", "markAchievementsSeen"),
            rig.log,
        )
    }

    @Test
    fun `android-dec-56 読みの途中で主体が替わったloadは旧主体の既読を書かず替えない対照は保存される`() = runTest {
        val swapped = rig()
        swapped.api.onFetchDashboard = {
            swapped.subject = "u-2"
            dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29")))
        }
        swapped.viewModel.load()
        assertEquals(emptySet<String>(), swapped.store.seenAchievementIds.value)

        val control = rig()
        control.api.onFetchDashboard = { dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29"))) }
        control.viewModel.load()
        assertEquals(setOf("streak_7"), control.store.seenAchievementIds.value)
    }

    @Test
    fun `android-dec-98 ダッシュボードの読みが失敗したらloadFailedで直前のdashboardを保ち保存を呼ばない`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = { throw ApiException.HttpError(500) }

        rig.viewModel.load()

        assertTrue(rig.viewModel.uiState.value.loadFailed)
        assertFalse(rig.viewModel.uiState.value.isLoading)
        assertNull(rig.viewModel.uiState.value.dashboard)
        assertFalse("markAchievementsSeen" in rig.log)

        val success = rig()
        success.viewModel.load()
        val previous = checkNotNull(success.viewModel.uiState.value.dashboard)
        success.log.clear()
        success.api.onFetchDashboard = { throw ApiException.NetworkError(RuntimeException("offline")) }

        success.viewModel.load()

        assertTrue(success.viewModel.uiState.value.loadFailed)
        assertEquals(previous.streakDays, checkNotNull(success.viewModel.uiState.value.dashboard).streakDays)
        assertFalse("markAchievementsSeen" in success.log)
    }

    @Test
    fun `clearAchievementHighlightは強調だけを空にしてdashboardは変えない`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = { dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29"))) }
        rig.viewModel.load()

        rig.viewModel.clearAchievementHighlight()

        val state = rig.viewModel.uiState.value
        assertEquals(emptyList<String>(), state.newlyUnlockedAchievements.map { it.id })
        assertEquals(listOf("streak_7"), checkNotNull(state.dashboard).unlockedAchievements.map { it.id })
        assertEquals(listOf("streak_7"), state.dashboard?.newlyUnlockedAchievements?.map { it.id })
    }
}

package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.model.AchievementResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.MonthlyActivityResponse
import com.rioikeda.newslisten.model.QuizStatsResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import com.rioikeda.newslisten.model.WeeklyGoalResponse
import com.rioikeda.newslisten.network.LearningApi
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningViewModelTest {
    @Test
    fun `初回ロードでも未表示の解錠実績を祝福対象にして表示済みへ保存する`() = runTest {
        val preferencesStore = InMemoryPreferencesStore()
        val viewModel = LearningViewModel(
            api = FakeLearningApi(
                dashboard = dashboard(
                    achievements = listOf(
                        AchievementResponse("first_episode_completed", "2026-07-29"),
                        AchievementResponse("streak_7", "2026-07-29"),
                    ),
                ),
            ),
            preferencesStore = preferencesStore,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.load()

        assertEquals(
            listOf("first_episode_completed", "streak_7"),
            viewModel.uiState.value.newlyUnlockedAchievements.map { it.id },
        )
        assertEquals(
            setOf("first_episode_completed", "streak_7"),
            preferencesStore.seenAchievementIds.value,
        )
    }

    @Test
    fun `表示済み実績を除いた差分だけを祝福する`() = runTest {
        val preferencesStore = InMemoryPreferencesStore(
            initialSeenAchievementIds = setOf("first_episode_completed"),
        )
        val viewModel = LearningViewModel(
            api = FakeLearningApi(
                dashboard = dashboard(
                    achievements = listOf(
                        AchievementResponse("first_episode_completed", "2026-07-29"),
                        AchievementResponse("completed_10", "2026-07-30"),
                    ),
                ),
            ),
            preferencesStore = preferencesStore,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.load()

        assertEquals(
            listOf("completed_10"),
            viewModel.uiState.value.newlyUnlockedAchievements.map { it.id },
        )
        assertEquals(
            setOf("first_episode_completed", "completed_10"),
            preferencesStore.seenAchievementIds.value,
        )
    }

    @Test
    fun `登録語彙は最新5件に絞り期日語がある時だけ単語テストを表示する`() = runTest {
        val vocabulary = (1..7).map { index ->
            VocabularyItemResponse(
                vocabularyId = "v-$index",
                podcastId = "p-$index",
                term = "term-$index",
                meaning = "意味-$index",
                example = "example-$index",
                registeredAt = "2026-07-${30 - index}",
            )
        }
        val viewModel = LearningViewModel(
            api = FakeLearningApi(
                dashboard = dashboard(),
                vocabulary = VocabularyListResponse(vocabulary, 7),
                session = VocabularyTestSessionResponse(listOf(testItem())),
            ),
            preferencesStore = InMemoryPreferencesStore(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.load()

        assertEquals(7, viewModel.uiState.value.vocabularyCount)
        assertEquals(vocabulary.take(5), viewModel.uiState.value.recentVocabulary)
        assertTrue(viewModel.uiState.value.hasVocabularyTest)
    }

    @Test
    fun `期日語が0件なら単語テスト導線を表示しない`() = runTest {
        val viewModel = LearningViewModel(
            api = FakeLearningApi(
                dashboard = dashboard(),
                session = VocabularyTestSessionResponse(emptyList()),
            ),
            preferencesStore = InMemoryPreferencesStore(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.load()

        assertFalse(viewModel.uiState.value.hasVocabularyTest)
    }

    private fun dashboard(
        achievements: List<AchievementResponse> = emptyList(),
    ) = LearningDashboardResponse(
        streak = ListeningStreakResponse(4, true, "2026-07-29"),
        totalEpisodes = 12,
        vocabularyAcquired = 31,
        quiz = QuizStatsResponse(2, 0.75, emptyList()),
        monthlyActivity = listOf(MonthlyActivityResponse("2026-07", 8)),
        currentDifficulty = "toeic_900",
        weeklyGoal = WeeklyGoalResponse(5, "2026-W31", 3, emptyList()),
        achievements = achievements,
    )

    private fun testItem() = VocabularyTestItemResponse(
        vocabularyId = "v-1",
        term = "resilient",
        meaning = "回復力のある",
        example = "The system is resilient.",
        distractors = listOf("壊れやすい", "短期的な", "不透明な"),
    )
}

private class FakeLearningApi(
    private val dashboard: LearningDashboardResponse,
    private val vocabulary: VocabularyListResponse = VocabularyListResponse(emptyList(), 0),
    private val session: VocabularyTestSessionResponse = VocabularyTestSessionResponse(emptyList()),
) : LearningApi {
    override suspend fun fetchLearningDashboard(): LearningDashboardResponse = dashboard
    override suspend fun fetchVocabulary(): VocabularyListResponse = vocabulary
    override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse = session
}

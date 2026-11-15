package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.app.AchievementRecord
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningDashboardQuery
import com.rioikeda.newslisten.learning.app.MonthlyActivityRecord
import com.rioikeda.newslisten.learning.app.QuizTrendRecord
import com.rioikeda.newslisten.learning.app.RecordingPreferencesStore
import com.rioikeda.newslisten.learning.app.VocabularyRecord
import com.rioikeda.newslisten.learning.app.VocabularySummary
import com.rioikeda.newslisten.learning.app.WeeklyGoalRecord
import com.rioikeda.newslisten.learning.app.WeeklyHistoryRecord
import com.rioikeda.newslisten.learning.app.dashboardRecord
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LearningDashboardQuery]（android-query-learning-01、android-dec-94・97・98、android-deriv-23、android-check-07）。 */
class LearningDashboardQueryTest {

    private class Rig(
        val query: LearningDashboardQuery,
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
        rig = Rig(query, api, store, log)
        return rig
    }

    private fun vocabulary(count: Int) = (1..count).map { VocabularyRecord("term-$it", "意味-$it", "2026-07-$it") }

    @Test
    fun `android-query-learning-01 派生値は週の目標の進み・記録の達成率・月の率・推移の直近3件になる`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = {
            dashboardRecord(
                weeklyGoal = WeeklyGoalRecord(
                    goalEpisodes = 5,
                    completedThisWeek = 7,
                    history = listOf(WeeklyHistoryRecord("2026-W30", goal = 5, completed = 4)),
                ),
                monthlyActivity = listOf(MonthlyActivityRecord("2026-06", 8), MonthlyActivityRecord("2026-07", 40)),
                quizTrend = (1..5).map { QuizTrendRecord("2026-07-0$it", it / 10.0) },
            )
        }

        val view = checkNotNull(rig.query.load())

        val goal = checkNotNull(view.weeklyGoal)
        assertEquals(1f, goal.fraction, 0f)
        assertEquals(7, goal.completedThisWeek)
        assertEquals(5, goal.goalEpisodes)
        assertEquals(listOf(0.8), goal.history.map { it.rate })
        assertEquals(listOf("2026-W30"), goal.history.map { it.week })
        assertEquals(listOf(8 / 31.0, 1.0), view.monthlyActivity.map { it.rate })
        assertEquals(listOf(8, 40), view.monthlyActivity.map { it.activeDays })
        assertEquals(listOf("2026-07-03", "2026-07-04", "2026-07-05"), view.recentQuizTrend.map { it.gradedAt })
        assertEquals(4, view.streakDays)
        assertEquals(12, view.totalEpisodes)
        assertEquals(31, view.vocabularyAcquired)
        assertEquals("toeic_900", view.currentDifficulty)
        assertEquals(2, view.quizzedEpisodes)
        assertEquals(0.75, view.averageCorrectRate)
        assertSame(view, rig.query.view.value)
    }

    @Test
    fun `android-rule-learning-06 新しく解錠された実績は解錠済みから既読を除いた順で解錠済みは応答の順のまま`() = runTest {
        val rig = rig(seen = setOf("streak_7"))
        rig.api.onFetchDashboard = {
            dashboardRecord(
                achievements = listOf(
                    AchievementRecord("completed_10", "2026-07-30"),
                    AchievementRecord("streak_7", "2026-07-29"),
                    AchievementRecord("unknown_id", "2026-07-31"),
                ),
            )
        }

        val view = checkNotNull(rig.query.load())

        assertEquals(listOf("completed_10", "unknown_id"), view.newlyUnlockedAchievements.map { it.id })
        assertEquals(listOf("completed_10", "streak_7", "unknown_id"), view.unlockedAchievements.map { it.id })
        assertEquals(listOf("2026-07-30", "2026-07-29", "2026-07-31"), view.unlockedAchievements.map { it.unlockedAt })
    }

    @Test
    fun `android-dec-95 語彙は件数と先頭5件を持ち語彙の読みの失敗は0件と空になりダッシュボードは成功する`() = runTest {
        val rig = rig()
        rig.api.onFetchVocabularySummary = { VocabularySummary(7, vocabulary(7)) }

        val view = checkNotNull(rig.query.load())

        assertEquals(7, view.vocabularyCount)
        assertEquals((1..5).map { "term-$it" }, view.recentVocabulary.map { it.term })

        rig.api.onFetchVocabularySummary = { throw ApiException.HttpError(500) }

        val failed = checkNotNull(rig.query.load())

        assertEquals(0, failed.vocabularyCount)
        assertEquals(emptyList<String>(), failed.recentVocabulary.map { it.term })
        assertEquals(4, failed.streakDays)
    }

    @Test
    fun `android-deriv-23 単語テストの有無はhasVocabularyTestの値で読みの失敗はfalse`() = runTest {
        val rig = rig()
        rig.api.onHasVocabularyTest = { true }
        assertTrue(checkNotNull(rig.query.load()).hasVocabularyTest)

        rig.api.onHasVocabularyTest = { false }
        assertFalse(checkNotNull(rig.query.load()).hasVocabularyTest)

        rig.api.onHasVocabularyTest = { throw ApiException.NetworkError(RuntimeException("offline")) }
        val failed = checkNotNull(rig.query.load())
        assertFalse(failed.hasVocabularyTest)
        assertEquals(4, failed.streakDays)
    }

    @Test
    fun `android-dec-98 ダッシュボードの失敗はnullを返しviewを直前のまま語彙と単語テストを読まない`() = runTest {
        val rig = rig()
        val first = checkNotNull(rig.query.load())
        rig.log.clear()
        rig.api.onFetchDashboard = { throw ApiException.ServerError(500) }

        val second = rig.query.load()

        assertNull(second)
        assertSame(first, rig.query.view.value)
        assertEquals(listOf("fetchDashboard"), rig.log)
    }

    @Test
    fun `android-check-07 android-deriv-23 query単独のloadは読むだけで端末への書込を1件も記録しない`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = { dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29"))) }

        rig.query.load()

        assertEquals(listOf("fetchDashboard", "fetchVocabularySummary", "hasVocabularyTest"), rig.log)
        assertEquals(emptySet<String>(), rig.store.seenAchievementIds.value)
    }

    @Test
    fun `android-dec-94 主体の印はload開始時の値でダッシュボードの読みの途中で主体が替わっても変わらない`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = {
            rig.subject = "u-2"
            dashboardRecord()
        }

        val view = checkNotNull(rig.query.load())

        // readAs は不透明なので、command に渡した結果（旧主体 u-1 と照合され書かれない）で観測する
        val commands = LearningCommands(
            PreferencesSync(NoopPreferencesApi, rig.store, { rig.subject }, backgroundScope),
        )
        commands.markAchievementsSeen(setOf("streak_7"), view.readAs)
        assertEquals(emptySet<String>(), rig.store.seenAchievementIds.value)

        rig.subject = "u-1"
        commands.markAchievementsSeen(setOf("streak_7"), view.readAs)
        assertEquals(setOf("streak_7"), rig.store.seenAchievementIds.value)
    }

    @Test
    fun `android-dec-97 既読の保存の後も新しく解錠された実績を作り直さず次のloadで初めて空になる`() = runTest {
        val rig = rig()
        rig.api.onFetchDashboard = { dashboardRecord(achievements = listOf(AchievementRecord("streak_7", "2026-07-29"))) }
        val commands = LearningCommands(
            PreferencesSync(NoopPreferencesApi, rig.store, { rig.subject }, backgroundScope),
        )

        val first = checkNotNull(rig.query.load())
        commands.markAchievementsSeen(setOf("streak_7"), first.readAs)

        assertEquals(listOf("streak_7"), rig.query.view.value?.newlyUnlockedAchievements?.map { it.id })
        assertEquals(listOf("streak_7"), first.newlyUnlockedAchievements.map { it.id })

        val second = checkNotNull(rig.query.load())

        assertEquals(emptyList<String>(), second.newlyUnlockedAchievements.map { it.id })
        assertEquals(listOf("streak_7"), second.unlockedAchievements.map { it.id })
    }

    @Test
    fun `android-dec-97 初期のviewはnull`() = runTest {
        assertNull(rig().query.view.value)
    }

    private object NoopPreferencesApi : PreferencesApi {
        override suspend fun fetchPreferences(): ServerPreferences = ServerPreferences("toeic_600", 1.0, 3)
        override suspend fun updateDefaultDifficulty(code: String) {}
        override suspend fun updateDefaultPlaybackSpeed(speed: Double) {}
        override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {}
    }
}

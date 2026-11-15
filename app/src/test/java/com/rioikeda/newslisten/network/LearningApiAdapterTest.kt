package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.AchievementResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.MonthlyActivityResponse
import com.rioikeda.newslisten.model.QuizAnswerRequest
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.model.QuizGradeResult
import com.rioikeda.newslisten.model.QuizStatsResponse
import com.rioikeda.newslisten.model.QuizTrendPointResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.model.VocabularyListResponse
import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import com.rioikeda.newslisten.model.WeeklyGoalRecordResponse
import com.rioikeda.newslisten.model.WeeklyGoalResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `LearningApiAdapter` は `ApiClient` の DTO を port の部品へ写し、例外は捕まえず透過する（android-dec-95・android-check-02）。 */
class LearningApiAdapterTest {

    private class StubApiClient : BaseFakeApiClient() {
        var dashboard: LearningDashboardResponse? = null
        var streak: ListeningStreakResponse? = null
        var vocabulary: VocabularyListResponse = VocabularyListResponse(emptyList(), 0)
        var session: VocabularyTestSessionResponse = VocabularyTestSessionResponse(emptyList())
        var failure: Throwable? = null

        override suspend fun fetchLearningDashboard(): LearningDashboardResponse {
            failure?.let { throw it }
            return checkNotNull(dashboard)
        }

        override suspend fun fetchListeningStreak(): ListeningStreakResponse {
            failure?.let { throw it }
            return checkNotNull(streak)
        }

        override suspend fun fetchVocabulary(): VocabularyListResponse {
            failure?.let { throw it }
            return vocabulary
        }

        override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse {
            failure?.let { throw it }
            return session
        }

        val savedVocabularyCalls = mutableListOf<Pair<String, String>>()
        var savedVocabulary: VocabularyItemResponse? = null

        override suspend fun saveVocabulary(podcastId: String, term: String): VocabularyItemResponse {
            savedVocabularyCalls += podcastId to term
            failure?.let { throw it }
            return checkNotNull(savedVocabulary)
        }

        val submittedQuizCalls = mutableListOf<Pair<String, QuizAnswerRequest>>()
        var quizResponse: QuizAnswerResponse? = null

        override suspend fun submitQuizAnswers(id: String, request: QuizAnswerRequest): QuizAnswerResponse {
            submittedQuizCalls += id to request
            failure?.let { throw it }
            return checkNotNull(quizResponse)
        }
    }

    private fun response(
        weeklyGoal: WeeklyGoalResponse?,
        achievements: List<AchievementResponse>?,
    ) = LearningDashboardResponse(
        streak = ListeningStreakResponse(4, true, "2026-07-29"),
        totalEpisodes = 12,
        vocabularyAcquired = 31,
        quiz = QuizStatsResponse(
            quizzedEpisodes = 2,
            averageCorrectRate = 0.75,
            trend = listOf(QuizTrendPointResponse("2026-07-20", 0.5), QuizTrendPointResponse("2026-07-27", 1.0)),
        ),
        monthlyActivity = listOf(MonthlyActivityResponse("2026-07", 8)),
        currentDifficulty = "toeic_900",
        weeklyGoal = weeklyGoal,
        achievements = achievements,
    )

    @Test
    fun `android-dec-95 fetchDashboardは週の目標と実績が無い応答をnullと空listへ写す`() = runTest {
        val client = StubApiClient().apply { dashboard = response(weeklyGoal = null, achievements = null) }

        val record = LearningApiAdapter(client).fetchDashboard()

        assertNull(record.weeklyGoal)
        assertEquals(emptyList<Any>(), record.achievements)
        assertEquals(4, record.streak.days)
        assertTrue(record.streak.todayListened)
        assertEquals("2026-07-29", record.streak.lastListenedDay)
        assertEquals(12, record.totalEpisodes)
        assertEquals(31, record.vocabularyAcquired)
        assertEquals(2, record.quizzedEpisodes)
        assertEquals(0.75, record.averageCorrectRate)
        assertEquals(listOf("2026-07-20", "2026-07-27"), record.quizTrend.map { it.gradedAt })
        assertEquals(listOf(0.5, 1.0), record.quizTrend.map { it.correctRate })
        assertEquals(listOf("2026-07"), record.monthlyActivity.map { it.month })
        assertEquals(listOf(8), record.monthlyActivity.map { it.activeDays })
        assertEquals("toeic_900", record.currentDifficulty)
    }

    @Test
    fun `android-dec-95 fetchDashboardは週の目標の履歴と実績を同じ値と順で写す`() = runTest {
        val client = StubApiClient().apply {
            dashboard = response(
                weeklyGoal = WeeklyGoalResponse(
                    goalEpisodes = 5,
                    week = "2026-W31",
                    completedThisWeek = 7,
                    history = listOf(WeeklyGoalRecordResponse("2026-W30", 5, 4)),
                ),
                achievements = listOf(
                    AchievementResponse("streak_7", "2026-07-29"),
                    AchievementResponse("completed_10", "2026-07-30"),
                ),
            )
        }

        val record = LearningApiAdapter(client).fetchDashboard()

        val goal = checkNotNull(record.weeklyGoal)
        assertEquals(5, goal.goalEpisodes)
        assertEquals(7, goal.completedThisWeek)
        assertEquals(listOf("2026-W30"), goal.history.map { it.week })
        assertEquals(listOf(5), goal.history.map { it.goal })
        assertEquals(listOf(4), goal.history.map { it.completed })
        assertEquals(listOf("streak_7", "completed_10"), record.achievements.map { it.id })
        assertEquals(listOf("2026-07-29", "2026-07-30"), record.achievements.map { it.unlockedAt })
    }

    @Test
    fun `android-dec-95 fetchStreakは応答を同じ値のStreakRecordに写す`() = runTest {
        val client = StubApiClient().apply { streak = ListeningStreakResponse(3, false, null) }

        val record = LearningApiAdapter(client).fetchStreak()

        assertEquals(3, record.days)
        assertFalse(record.todayListened)
        assertNull(record.lastListenedDay)
    }

    @Test
    fun `android-dec-95 fetchVocabularySummaryは件数をそのままに語彙を絞らず全件写す`() = runTest {
        val client = StubApiClient().apply {
            vocabulary = VocabularyListResponse(
                vocabulary = (1..7).map {
                    VocabularyItemResponse("v-$it", "p-$it", "term-$it", "意味-$it", "example-$it", "2026-07-0$it")
                },
                count = 42,
            )
        }

        val summary = LearningApiAdapter(client).fetchVocabularySummary()

        assertEquals(42, summary.count)
        assertEquals((1..7).map { "p-$it" }, summary.items.map { it.episodeId })
        assertEquals((1..7).map { "term-$it" }, summary.items.map { it.term })
        assertEquals((1..7).map { "意味-$it" }, summary.items.map { it.meaning })
        assertEquals((1..7).map { "2026-07-0$it" }, summary.items.map { it.registeredAt })
    }

    @Test
    fun `android-dec-102 saveVocabularyはpodcastIdをepisodeIdへ写し例外を透過する`() = runTest {
        val client = StubApiClient().apply {
            savedVocabulary = VocabularyItemResponse("v", "p-1", "term", "意味", "ex", "2026-07-01")
        }

        val record = LearningApiAdapter(client).saveVocabulary("p-1", "term")

        assertEquals(listOf("p-1" to "term"), client.savedVocabularyCalls)
        assertEquals("p-1", record.episodeId)
        assertEquals("term", record.term)
        assertEquals("意味", record.meaning)
        assertEquals("2026-07-01", record.registeredAt)

        val failure = ApiException.HttpError(500)
        client.failure = failure
        try {
            LearningApiAdapter(client).saveVocabulary("p-1", "term")
            fail("ApiException が投げられるはず")
        } catch (e: ApiException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `android-deriv-22 submitQuizAnswersは回答をrequestに包み採点結果を率を変えずに写し例外を透過する`() = runTest {
        val client = StubApiClient().apply {
            quizResponse = QuizAnswerResponse(
                correctCount = 1,
                total = 2,
                correctRate = 0.3,
                results = listOf(
                    QuizGradeResult(questionIndex = 0, selectedIndex = 0, correctIndex = 0, isCorrect = true),
                    QuizGradeResult(questionIndex = 1, selectedIndex = 2, correctIndex = 1, isCorrect = false),
                ),
            )
        }

        val record = LearningApiAdapter(client).submitQuizAnswers("p-1", listOf(0, 1))

        assertEquals("p-1", client.submittedQuizCalls.single().first)
        assertEquals(listOf(0, 1), client.submittedQuizCalls.single().second.answers)
        assertEquals(1, record.correctCount)
        assertEquals(2, record.total)
        assertEquals(0.3, record.correctRate, 0.0) // 件数から再計算しない（0.5 にならない）
        assertEquals(listOf(0, 1), record.items.map { it.questionIndex })
        assertEquals(listOf(0, 2), record.items.map { it.selectedIndex })
        assertEquals(listOf(0, 1), record.items.map { it.correctIndex })
        assertEquals(listOf(true, false), record.items.map { it.isCorrect })

        val failure = ApiException.NotFound()
        client.failure = failure
        try {
            LearningApiAdapter(client).submitQuizAnswers("p-1", listOf(0))
            fail("ApiException が投げられるはず")
        } catch (e: ApiException.NotFound) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `android-deriv-23 hasVocabularyTestは出題が空でなければtrueで空ならfalse`() = runTest {
        val item = VocabularyTestItemResponse("v-1", "resilient", "回復力のある", "The system is resilient.", listOf("a", "b", "c"))
        val client = StubApiClient()

        client.session = VocabularyTestSessionResponse(listOf(item))
        assertTrue(LearningApiAdapter(client).hasVocabularyTest())

        client.session = VocabularyTestSessionResponse(emptyList())
        assertFalse(LearningApiAdapter(client).hasVocabularyTest())
    }

    @Test
    fun `android-dec-95 ApiClientの例外は4操作とも捕まえずに同じものを投げる`() = runTest {
        val failure = ApiException.NotFound()
        val adapter = LearningApiAdapter(StubApiClient().apply { this.failure = failure })

        val operations: List<suspend () -> Any?> = listOf(
            { adapter.fetchDashboard() },
            { adapter.fetchStreak() },
            { adapter.hasVocabularyTest() },
            { adapter.fetchVocabularySummary() },
        )
        for (operation in operations) {
            try {
                operation()
                fail("ApiException が投げられるはず")
            } catch (e: ApiException) {
                assertSame(failure, e)
            }
        }
    }
}

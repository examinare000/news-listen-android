package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.domain.QuizGrade
import com.rioikeda.newslisten.preferences.app.PreferencesApi
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.preferences.app.ServerPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [LearningCommands] は既読の保存を [PreferencesSync] に任せ（android-cmd-learning-04）、
 * クイズの送信・語彙の登録・登録済み語彙の読みを [LearningApi] 経由で中継する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningCommandsTest {
    private object NoopPreferencesApi : PreferencesApi {
        override suspend fun fetchPreferences(): ServerPreferences = ServerPreferences("toeic_600", 1.0, 3)
        override suspend fun updateDefaultDifficulty(code: String) {}
        override suspend fun updateDefaultPlaybackSpeed(speed: Double) {}
        override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {}
    }

    private class Rig(
        val commands: LearningCommands,
        val store: RecordingPreferencesStore,
        val log: List<String>,
        val api: FakeLearningApi,
    )

    private fun TestScope.rig(subject: () -> String? = { "u-1" }): Rig {
        val log = mutableListOf<String>()
        val store = RecordingPreferencesStore(log)
        val api = FakeLearningApi(log)
        val commands = LearningCommands(
            api = api,
            preferencesSync = PreferencesSync(NoopPreferencesApi, store, subject, backgroundScope),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return Rig(commands, store, log, api)
    }

    private fun gradeRecord(correct: Int = 2, total: Int = 3) = QuizGradeRecord(
        correctCount = correct,
        total = total,
        correctRate = correct.toDouble() / total,
        items = (0 until total).map { QuizItemRecord(it, selectedIndex = it, correctIndex = it, isCorrect = it < correct) },
    )

    private fun vocabulary(episodeId: String, term: String) =
        VocabularyRecord(episodeId = episodeId, term = term, meaning = "回復力のある", registeredAt = "2026-07-29T03:00:00+00:00")

    @Test
    fun `android-cmd-learning-04 同じ主体なら既読をstoreへ1回書き戻り値はUnit`() = runTest {
        val rig = rig { "u-1" }

        val result: Unit = rig.commands.markAchievementsSeen(setOf("streak_7"), ReadSubject("u-1"))

        assertEquals(Unit, result)
        assertEquals(listOf("markAchievementsSeen"), rig.log)
        assertEquals(setOf("streak_7"), rig.store.seenAchievementIds.value)
    }

    @Test
    fun `android-dec-56 違う主体なら書かずに捨て戻り値はUnitのまま`() = runTest {
        val rig = rig { "u-2" }

        val result: Unit = rig.commands.markAchievementsSeen(setOf("streak_7"), ReadSubject("u-1"))

        assertEquals(Unit, result)
        assertEquals(emptyList<String>(), rig.log)
        assertEquals(emptySet<String>(), rig.store.seenAchievementIds.value)
    }

    // --- クイズの送信（android-cmd-learning-01） ---

    @Test
    fun `android-cmd-learning-01 submitQuizは指定のepisodeIdで送信し採点結果をQuizGradeで返す`() = runTest {
        val rig = rig()
        rig.api.onSubmitQuizAnswers = { _, _ -> gradeRecord(correct = 2, total = 3) }

        val grade = rig.commands.submitQuiz("podcast-1", listOf(0, 1, 0))

        assertEquals(listOf("podcast-1" to listOf(0, 1, 0)), rig.api.submitQuizAnswersCalls)
        assertEquals(2, grade.correctCount)
        assertEquals(3, grade.total)
        assertEquals(2.0 / 3.0, grade.correctRate, 0.0)
        assertEquals(listOf(0, 1, 2), grade.items.map { it.questionIndex })
        assertEquals(listOf(true, true, false), grade.items.map { it.isCorrect })
    }

    @Test
    fun `android-cmd-learning-01 submitQuizは渡されたepisodeIdだけで送り再生中のepisodeに影響されない`() = runTest {
        val rig = rig()
        rig.api.onSubmitQuizAnswers = { _, _ -> gradeRecord(correct = 1, total = 1) }

        rig.commands.submitQuiz("p1", listOf(0))
        rig.commands.submitQuiz("p2", listOf(0))

        assertEquals(listOf("p1", "p2"), rig.api.submitQuizAnswersCalls.map { it.first })
    }

    @Test
    fun `android-cmd-learning-01 submitQuizはApiExceptionを握らず呼び手へ伝える`() = runTest {
        val rig = rig()
        rig.api.onSubmitQuizAnswers = { _, _ -> throw ApiException.HttpError(500) }
        try {
            rig.commands.submitQuiz("podcast-1", listOf(0))
            fail("例外をスロー予定")
        } catch (e: ApiException.HttpError) {
            assertEquals(500, e.code)
        }
        assertNull(rig.commands.notice.value)

        rig.api.onSubmitQuizAnswers = { _, _ -> throw ApiException.NotFound() }
        try {
            rig.commands.submitQuiz("podcast-1", listOf(0))
            fail("例外をスロー予定")
        } catch (_: ApiException.NotFound) {
            // 404 は NotFound のまま届く
        }
        assertNull(rig.commands.notice.value)
    }

    // --- 登録済み語彙の読み（android-query-learning-03） ---

    @Test
    fun `android-query-learning-03 登録語彙の初期取得成功で習得済み判定へ反映する`() = runTest {
        val rig = rig()
        rig.api.onFetchVocabularySummary = { VocabularySummary(1, listOf(vocabulary("p1", "Resilient"))) }

        rig.commands.loadVocabularyRegistrations()

        assertTrue(rig.commands.isVocabularyRegistered("p1", " resilient "))
        assertFalse(rig.commands.isVocabularyRegistered("p2", "resilient"))
    }

    @Test
    fun `android-query-learning-03 登録語彙の初期取得失敗は握り知らせを出さない`() = runTest {
        val rig = rig()
        rig.api.onFetchVocabularySummary = { throw ApiException.HttpError(500) }

        rig.commands.loadVocabularyRegistrations()

        assertTrue(rig.commands.registeredVocabularyKeys.value.isEmpty())
        assertNull(rig.commands.notice.value)
    }

    // --- 語彙の登録（android-cmd-learning-02） ---

    @Test
    fun `android-cmd-learning-02 語彙の習得登録は冪等で同じ語を二重送信しない`() = runTest {
        val rig = rig()

        rig.commands.registerVocabulary("p1", "Resilient")
        rig.commands.registerVocabulary("p1", " resilient ")

        assertEquals(listOf("p1" to "Resilient"), rig.api.saveVocabularyCalls)
        assertTrue(rig.commands.isVocabularyRegistered("p1", "resilient"))
    }

    @Test
    fun `android-cmd-learning-02 語彙の登録の失敗はApiExceptionを握りnoticeにVocabularyFailedを出し登録中を外す`() = runTest {
        val rig = rig()
        rig.api.onSaveVocabulary = { _, _ -> throw ApiException.HttpError(500) }

        rig.commands.registerVocabulary("p1", "term")

        assertEquals(LearningNotice.VocabularyFailed, rig.commands.notice.value)
        assertTrue(rig.commands.savingVocabularyKeys.value.isEmpty())
        assertFalse(rig.commands.isVocabularyRegistered("p1", "term"))

        rig.commands.dismissNotice()

        assertNull(rig.commands.notice.value)
    }

    @Test
    fun `android-cmd-learning-02 失敗の後の登録は登録済みでないので再び送る`() = runTest {
        val rig = rig()
        rig.api.onSaveVocabulary = { _, _ -> throw ApiException.HttpError(500) }
        rig.commands.registerVocabulary("p1", "term")
        rig.api.onSaveVocabulary = { id, term -> vocabulary(id, term) }

        rig.commands.registerVocabulary("p1", "term")

        assertEquals(2, rig.api.saveVocabularyCalls.size)
        assertTrue(rig.commands.isVocabularyRegistered("p1", "term"))
    }

    @Test
    fun `android-cmd-learning-02 成功はnoticeを変えない`() = runTest {
        val rig = rig()
        rig.api.onSaveVocabulary = { _, _ -> throw ApiException.HttpError(500) }
        rig.commands.registerVocabulary("p1", "bad")
        rig.api.onSaveVocabulary = { id, term -> vocabulary(id, term) }

        rig.commands.registerVocabulary("p1", "good")

        assertEquals(LearningNotice.VocabularyFailed, rig.commands.notice.value)
    }

    // --- 入口の戻り値（android-dep-08） ---

    @Test
    fun `android-dep-08 入口の戻り値はsubmitQuizがQuizGradeでregisterVocabularyがUnit`() = runTest {
        val rig = rig()
        rig.api.onSubmitQuizAnswers = { _, _ -> gradeRecord() }

        val grade: QuizGrade = rig.commands.submitQuiz("p1", listOf(0, 1, 2))
        val unit: Unit = rig.commands.registerVocabulary("p1", "term")

        assertEquals(3, grade.total)
        assertEquals(Unit, unit)
    }
}

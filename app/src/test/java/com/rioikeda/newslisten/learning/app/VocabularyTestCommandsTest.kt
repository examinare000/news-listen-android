package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.domain.RetestFeedback
import com.rioikeda.newslisten.learning.domain.VocabularyTestPhase
import com.rioikeda.newslisten.learning.domain.VocabularyTestProgress
import com.rioikeda.newslisten.learning.domain.VocabularyTestSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyTestCommandsTest {

    private fun commands(
        api: FakeVocabularyTestApi,
        scheduler: TestCoroutineScheduler,
        shuffle: (List<String>) -> List<String> = { it },
    ) = VocabularyTestCommands(
        api = api,
        dispatcher = StandardTestDispatcher(scheduler),
        shuffle = shuffle,
    )

    private fun items(count: Int) = (1..count).map(::vocabularyTestItem)

    @Test
    fun `android-cmd-learning-03 4操作の戻り値はUnit`() = runTest {
        val commands = commands(FakeVocabularyTestApi(items = items(1), failuresBeforeSubmitSuccess = 1), testScheduler)

        val started: Unit = commands.startVocabularyTest()
        val assessed: Unit = commands.assess(known = true)
        val retried: Unit = commands.retrySubmission()
        val answered: Unit = commands.answerRetest("意味-1")

        assertEquals(Unit, started)
        assertEquals(Unit, assessed)
        assertEquals(Unit, retried)
        assertEquals(Unit, answered)
    }

    @Test
    fun `android-cmd-learning-03 構築直後のviewはLoadingで取得も送信もしない`() = runTest {
        val api = FakeVocabularyTestApi(items = items(1))
        val commands = commands(api, testScheduler)

        assertEquals(VocabularyTestPhase.Loading, commands.vocabularyTest.value.phase)
        assertEquals(0, api.fetchCount)
        assertEquals(0, api.submittedResults.size)
    }

    @Test
    fun `android-cmd-learning-03 出題を最大10語へ制限して自己評価を開始する`() = runTest {
        val commands = commands(FakeVocabularyTestApi(items = items(12)), testScheduler)

        commands.startVocabularyTest()

        val view = commands.vocabularyTest.value
        assertEquals(VocabularyTestPhase.SelfAssessment, view.phase)
        assertEquals(VocabularyTestProgress(1, 10), view.progress)
        assertEquals("term-1", view.currentTerm)
        assertEquals("example-1", view.currentExample)
    }

    @Test
    fun `android-cmd-learning-03 まだと答えた語だけを固定シャッフルした再テストへ進める`() = runTest {
        var shuffleCount = 0
        val commands = commands(
            api = FakeVocabularyTestApi(items = items(2)),
            scheduler = testScheduler,
            shuffle = {
                shuffleCount++
                it.reversed()
            },
        )
        commands.startVocabularyTest()

        commands.assess(known = true)
        commands.assess(known = false)

        val view = commands.vocabularyTest.value
        assertEquals(VocabularyTestPhase.Retest, view.phase)
        assertEquals(VocabularyTestProgress(1, 1), view.progress)
        assertEquals("term-2", view.currentTerm)
        assertEquals("意味-2", view.correctMeaning)
        assertEquals(1, shuffleCount)
        assertEquals(listOf("誤答C-2", "誤答B-2", "誤答A-2", "意味-2"), view.choices)
        assertEquals(view.choices, commands.vocabularyTest.value.choices)
    }

    @Test
    fun `android-cmd-learning-03 全語を知ってると答えたら再テストせず送信する`() = runTest {
        val api = FakeVocabularyTestApi(items = items(2))
        val commands = commands(api, testScheduler)
        commands.startVocabularyTest()

        commands.assess(known = true)
        commands.assess(known = true)

        val view = commands.vocabularyTest.value
        assertEquals(VocabularyTestPhase.Result, view.phase)
        assertEquals(VocabularyTestSummary(2, 0, 0), view.summary)
        assertEquals(
            listOf(
                VocabularyTestAnswerRecord("v-1", true, null),
                VocabularyTestAnswerRecord("v-2", true, null),
            ),
            api.submittedResults.single(),
        )
    }

    @Test
    fun `android-cmd-learning-03 再テストは二重回答を無視して800ms後に一度だけ送信する`() = runTest {
        val api = FakeVocabularyTestApi(items = items(1))
        val commands = commands(api, testScheduler)
        commands.startVocabularyTest()
        commands.assess(known = false)

        launch { commands.answerRetest("意味-1") }
        runCurrent()
        assertTrue(commands.vocabularyTest.value.isAnswerLocked)
        assertEquals(RetestFeedback.Correct, commands.vocabularyTest.value.retestFeedback)

        commands.answerRetest("誤答")
        advanceTimeBy(800)
        runCurrent()

        assertEquals(1, api.submittedResults.size)
        assertEquals(true, api.submittedResults.single().single().retestCorrect)
        assertEquals(VocabularyTestPhase.Result, commands.vocabularyTest.value.phase)
    }

    @Test
    fun `android-cmd-learning-03 不正解では正解肢を保持してから次へ進む`() = runTest {
        val commands = commands(FakeVocabularyTestApi(items = items(2)), testScheduler)
        commands.startVocabularyTest()
        commands.assess(known = false)
        commands.assess(known = false)

        launch { commands.answerRetest("誤答") }
        runCurrent()

        assertEquals(RetestFeedback.Incorrect("意味-1"), commands.vocabularyTest.value.retestFeedback)
        advanceTimeBy(800)
        runCurrent()
        val view = commands.vocabularyTest.value
        assertEquals(VocabularyTestProgress(2, 2), view.progress)
        assertEquals("term-2", view.currentTerm)
        assertFalse(view.isAnswerLocked)
        assertNull(view.retestFeedback)
    }

    @Test
    fun `android-cmd-learning-03 送信失敗時は回答を保持し同じ内容を再送できる`() = runTest {
        val api = FakeVocabularyTestApi(items = items(1), failuresBeforeSubmitSuccess = 1)
        val commands = commands(api, testScheduler)
        commands.startVocabularyTest()

        commands.assess(known = true)

        assertEquals(VocabularyTestPhase.SubmitError, commands.vocabularyTest.value.phase)
        assertEquals(listOf(VocabularyTestAnswerRecord("v-1", true, null)), api.submittedResults.single())
        commands.retrySubmission()

        assertEquals(VocabularyTestPhase.Result, commands.vocabularyTest.value.phase)
        assertEquals(2, api.submittedResults.size)
        assertEquals(api.submittedResults[0], api.submittedResults[1])
    }

    @Test
    fun `android-cmd-learning-03 出題の取得失敗はLoadErrorでもう一度呼ぶと自己評価になる`() = runTest {
        val api = FakeVocabularyTestApi(items = items(1))
        var failures = 1
        api.onFetchVocabularyTestSession = {
            if (failures-- > 0) throw ApiException.HttpError(500)
            VocabularyTestSessionRecord(items(1))
        }
        val commands = commands(api, testScheduler)

        commands.startVocabularyTest()
        assertEquals(VocabularyTestPhase.LoadError, commands.vocabularyTest.value.phase)

        commands.startVocabularyTest()
        assertEquals(VocabularyTestPhase.SelfAssessment, commands.vocabularyTest.value.phase)
    }

    @Test
    fun `android-cmd-learning-03 出題が空ならEmpty`() = runTest {
        val api = FakeVocabularyTestApi(items = emptyList())
        val commands = commands(api, testScheduler)

        commands.startVocabularyTest()

        assertEquals(VocabularyTestPhase.Empty, commands.vocabularyTest.value.phase)
        assertEquals(0, api.submittedResults.size)
    }

    @Test
    fun `android-cmd-learning-03 受け付けない操作は送信せずviewを変えない`() = runTest {
        val api = FakeVocabularyTestApi(items = items(2))
        val commands = commands(api, testScheduler)

        val loading = commands.vocabularyTest.value
        commands.assess(known = true)
        assertEquals(loading, commands.vocabularyTest.value)

        commands.startVocabularyTest()
        val selfAssessment = commands.vocabularyTest.value
        val fetchCount = api.fetchCount
        commands.retrySubmission()

        assertEquals(selfAssessment, commands.vocabularyTest.value)
        assertEquals(0, api.submittedResults.size)
        assertEquals(fetchCount, api.fetchCount)
    }

    @Test
    fun `android-cmd-learning-03 もう一度呼ぶと前回の回答と結果を捨ててLoadingから始まる`() = runTest {
        val api = FakeVocabularyTestApi(items = items(2))
        val commands = commands(api, testScheduler)
        commands.startVocabularyTest()
        commands.assess(known = true)
        commands.assess(known = true)
        assertEquals(VocabularyTestPhase.Result, commands.vocabularyTest.value.phase)

        val gate = CompletableDeferred<Unit>()
        api.onFetchVocabularyTestSession = {
            gate.await()
            VocabularyTestSessionRecord(items(2))
        }
        launch { commands.startVocabularyTest() }
        runCurrent()

        assertEquals(VocabularyTestPhase.Loading, commands.vocabularyTest.value.phase)
        assertNull(commands.vocabularyTest.value.summary)

        gate.complete(Unit)
        runCurrent()

        assertEquals(VocabularyTestPhase.SelfAssessment, commands.vocabularyTest.value.phase)
        assertEquals(VocabularyTestProgress(1, 2), commands.vocabularyTest.value.progress)
    }
}

package com.rioikeda.newslisten.vocabulary

import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.model.VocabularyTestResultResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.VocabularyTestApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyTestViewModelTest {
    @Test
    fun `セッション取得時に最大10語へ制限して自己評価を開始する`() = runTest {
        val api = FakeVocabularyTestApi(items = (1..12).map(::item))
        val viewModel = newViewModel(api, testScheduler)

        viewModel.load()

        assertEquals(VocabularyTestPhase.SelfAssessment, viewModel.uiState.value.phase)
        assertEquals(10, viewModel.uiState.value.items.size)
        assertEquals("1 / 10", viewModel.uiState.value.progressText)
    }

    @Test
    fun `まだと答えた語だけを固定シャッフルした再テストへ進める`() = runTest {
        var shuffleCount = 0
        val viewModel = newViewModel(
            api = FakeVocabularyTestApi(items = listOf(item(1), item(2))),
            scheduler = testScheduler,
            shuffle = {
                shuffleCount++
                it.reversed()
            },
        )
        viewModel.load()

        viewModel.assess(known = true)
        viewModel.assess(known = false)

        val state = viewModel.uiState.value
        assertEquals(VocabularyTestPhase.Retest, state.phase)
        assertEquals(listOf("term-2"), state.retestItems.map { it.term })
        assertEquals(1, shuffleCount)
        assertEquals(state.currentChoices, viewModel.uiState.value.currentChoices)
    }

    @Test
    fun `全語を知ってると答えたら再テストせず送信する`() = runTest {
        val api = FakeVocabularyTestApi(items = listOf(item(1), item(2)))
        val viewModel = newViewModel(api, testScheduler)
        viewModel.load()

        viewModel.assess(known = true)
        viewModel.assess(known = true)

        assertEquals(VocabularyTestPhase.Result, viewModel.uiState.value.phase)
        assertEquals(2, viewModel.uiState.value.summary?.knownCount)
        assertEquals(0, viewModel.uiState.value.summary?.retestCount)
        assertEquals(
            listOf(
                VocabularyTestResultItemRequest("v-1", true, null),
                VocabularyTestResultItemRequest("v-2", true, null),
            ),
            api.submittedResults.single(),
        )
    }

    @Test
    fun `再テストは二重回答を無視して800ms後に一度だけ送信する`() = runTest {
        val api = FakeVocabularyTestApi(items = listOf(item(1)))
        val viewModel = newViewModel(api, testScheduler)
        viewModel.load()
        viewModel.assess(known = false)

        launch { viewModel.answerRetest("意味-1") }
        runCurrent()
        assertTrue(viewModel.uiState.value.isAnswerLocked)
        assertEquals(RetestFeedback.Correct, viewModel.uiState.value.retestFeedback)

        viewModel.answerRetest("誤答")
        advanceTimeBy(800)
        runCurrent()

        assertEquals(1, api.submittedResults.size)
        assertEquals(true, api.submittedResults.single().single().retestCorrect)
        assertEquals(VocabularyTestPhase.Result, viewModel.uiState.value.phase)
    }

    @Test
    fun `不正解では正解肢を保持してから次へ進む`() = runTest {
        val api = FakeVocabularyTestApi(items = listOf(item(1), item(2)))
        val viewModel = newViewModel(api, testScheduler)
        viewModel.load()
        viewModel.assess(known = false)
        viewModel.assess(known = false)

        launch { viewModel.answerRetest("誤答") }
        runCurrent()

        assertEquals(RetestFeedback.Incorrect("意味-1"), viewModel.uiState.value.retestFeedback)
        advanceTimeBy(800)
        runCurrent()
        assertEquals(1, viewModel.uiState.value.retestIndex)
    }

    @Test
    fun `送信失敗時は回答を保持し同じ内容を再送できる`() = runTest {
        val api = FakeVocabularyTestApi(
            items = listOf(item(1)),
            failuresBeforeSuccess = 1,
        )
        val viewModel = newViewModel(api, testScheduler)
        viewModel.load()

        viewModel.assess(known = true)

        assertEquals(VocabularyTestPhase.SubmitError, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.assessments.isEmpty())
        viewModel.retrySubmission()

        assertEquals(VocabularyTestPhase.Result, viewModel.uiState.value.phase)
        assertEquals(api.submittedResults[0], api.submittedResults[1])
    }

    private fun newViewModel(
        api: FakeVocabularyTestApi,
        scheduler: TestCoroutineScheduler,
        shuffle: (List<String>) -> List<String> = { it },
    ) = VocabularyTestViewModel(
        api = api,
        dispatcher = StandardTestDispatcher(scheduler),
        shuffle = shuffle,
    )

    private fun item(index: Int) = VocabularyTestItemResponse(
        vocabularyId = "v-$index",
        term = "term-$index",
        meaning = "意味-$index",
        example = "example-$index",
        distractors = listOf("誤答A-$index", "誤答B-$index", "誤答C-$index"),
    )
}

private class FakeVocabularyTestApi(
    private val items: List<VocabularyTestItemResponse>,
    private var failuresBeforeSuccess: Int = 0,
) : VocabularyTestApi {
    val submittedResults = mutableListOf<List<VocabularyTestResultItemRequest>>()

    override suspend fun fetchVocabularyTestSession() = VocabularyTestSessionResponse(items)

    override suspend fun submitVocabularyTestResults(
        results: List<VocabularyTestResultItemRequest>,
    ): VocabularyTestResultResponse {
        submittedResults += results
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw ApiException.HttpError(500)
        }
        return VocabularyTestResultResponse(results.size)
    }
}

package com.rioikeda.newslisten.vocabulary

import com.rioikeda.newslisten.learning.app.FakeVocabularyTestApi
import com.rioikeda.newslisten.learning.app.VocabularyTestCommands
import com.rioikeda.newslisten.learning.app.vocabularyTestItem
import com.rioikeda.newslisten.learning.domain.VocabularyTestPhase
import com.rioikeda.newslisten.learning.ui.VocabularyTestViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** `VocabularyTestViewModel` は `VocabularyTestCommands` への中継と `uiState` の受け渡しだけを持つ（android-dec-107）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyTestViewModelTest {

    private fun commands(api: FakeVocabularyTestApi, scheduler: TestCoroutineScheduler) =
        VocabularyTestCommands(api = api, dispatcher = StandardTestDispatcher(scheduler), shuffle = { it })

    @Test
    fun `android-dec-107 uiStateはCommandsのvocabularyTestをそのまま渡す`() = runTest {
        val commands = commands(FakeVocabularyTestApi(items = listOf(vocabularyTestItem(1))), testScheduler)
        val viewModel = VocabularyTestViewModel(commands)

        assertSame(commands.vocabularyTest, viewModel.uiState)
        assertEquals(VocabularyTestPhase.Loading, viewModel.uiState.value.phase)
    }

    @Test
    fun `android-dec-107 loadとassessはCommandsの対応する操作を呼ぶ`() = runTest {
        val api = FakeVocabularyTestApi(items = listOf(vocabularyTestItem(1)))
        val viewModel = VocabularyTestViewModel(commands(api, testScheduler))

        viewModel.load()
        assertEquals(1, api.fetchCount)
        assertEquals(VocabularyTestPhase.SelfAssessment, viewModel.uiState.value.phase)

        viewModel.assess(known = false)
        assertEquals(VocabularyTestPhase.Retest, viewModel.uiState.value.phase)
    }

    @Test
    fun `android-dec-107 answerRetestとretrySubmissionはCommandsの対応する操作を呼ぶ`() = runTest {
        val answerApi = FakeVocabularyTestApi(items = listOf(vocabularyTestItem(1)))
        val answerViewModel = VocabularyTestViewModel(commands(answerApi, testScheduler))
        answerViewModel.load()
        answerViewModel.assess(known = false)

        launch { answerViewModel.answerRetest("意味-1") }
        runCurrent()
        advanceTimeBy(800)
        runCurrent()

        assertEquals(1, answerApi.submittedResults.size)
        assertEquals(VocabularyTestPhase.Result, answerViewModel.uiState.value.phase)

        val retryApi = FakeVocabularyTestApi(items = listOf(vocabularyTestItem(1)), failuresBeforeSubmitSuccess = 1)
        val retryViewModel = VocabularyTestViewModel(commands(retryApi, testScheduler))
        retryViewModel.load()
        retryViewModel.assess(known = true)
        assertEquals(VocabularyTestPhase.SubmitError, retryViewModel.uiState.value.phase)

        retryViewModel.retrySubmission()

        assertEquals(VocabularyTestPhase.Result, retryViewModel.uiState.value.phase)
        assertEquals(2, retryApi.submittedResults.size)
    }
}

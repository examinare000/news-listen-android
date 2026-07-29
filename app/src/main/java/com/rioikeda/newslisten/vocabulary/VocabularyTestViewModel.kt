package com.rioikeda.newslisten.vocabulary

import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.VocabularyTestApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class VocabularyTestPhase {
    Loading,
    SelfAssessment,
    Retest,
    Submitting,
    Result,
    Empty,
    LoadError,
    SubmitError,
}

sealed interface RetestFeedback {
    data object Correct : RetestFeedback
    data class Incorrect(val correctMeaning: String) : RetestFeedback
}

data class VocabularyTestSummary(
    val knownCount: Int,
    val retestCount: Int,
    val retestCorrectCount: Int,
)

data class VocabularyTestUiState(
    val phase: VocabularyTestPhase = VocabularyTestPhase.Loading,
    val items: List<VocabularyTestItemResponse> = emptyList(),
    val assessmentIndex: Int = 0,
    val assessments: Map<String, Boolean> = emptyMap(),
    val retestItems: List<VocabularyTestItemResponse> = emptyList(),
    val retestIndex: Int = 0,
    val retestResults: Map<String, Boolean> = emptyMap(),
    val choicesById: Map<String, List<String>> = emptyMap(),
    val isAnswerLocked: Boolean = false,
    val retestFeedback: RetestFeedback? = null,
    val summary: VocabularyTestSummary? = null,
) {
    val currentAssessment: VocabularyTestItemResponse?
        get() = items.getOrNull(assessmentIndex)

    val currentRetest: VocabularyTestItemResponse?
        get() = retestItems.getOrNull(retestIndex)

    val currentChoices: List<String>
        get() = currentRetest?.let { choicesById[it.vocabularyId] }.orEmpty()

    val progressText: String
        get() = when (phase) {
            VocabularyTestPhase.SelfAssessment -> "${assessmentIndex + 1} / ${items.size}"
            VocabularyTestPhase.Retest -> "${retestIndex + 1} / ${retestItems.size}"
            else -> ""
        }
}

class VocabularyTestViewModel(
    private val api: VocabularyTestApi,
    private val dispatcher: CoroutineDispatcher,
    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },
) {
    private val _uiState = MutableStateFlow(VocabularyTestUiState())
    val uiState: StateFlow<VocabularyTestUiState> = _uiState.asStateFlow()

    suspend fun load(): Unit = withContext(dispatcher) {
        _uiState.value = VocabularyTestUiState()
        try {
            val items = api.fetchVocabularyTestSession().items.take(MAX_SESSION_WORDS)
            _uiState.value = VocabularyTestUiState(
                phase = if (items.isEmpty()) VocabularyTestPhase.Empty else VocabularyTestPhase.SelfAssessment,
                items = items,
            )
        } catch (_: ApiException) {
            _uiState.value = VocabularyTestUiState(phase = VocabularyTestPhase.LoadError)
        }
    }

    suspend fun assess(known: Boolean): Unit = withContext(dispatcher) {
        val state = _uiState.value
        if (state.phase != VocabularyTestPhase.SelfAssessment) return@withContext
        val item = state.currentAssessment ?: return@withContext
        val assessments = state.assessments + (item.vocabularyId to known)
        if (state.assessmentIndex < state.items.lastIndex) {
            _uiState.value = state.copy(
                assessmentIndex = state.assessmentIndex + 1,
                assessments = assessments,
            )
            return@withContext
        }

        val retestItems = state.items.filter { assessments[it.vocabularyId] == false }
        if (retestItems.isEmpty()) {
            _uiState.value = state.copy(assessments = assessments)
            submit()
            return@withContext
        }
        val choices = retestItems.associate { retestItem ->
            val candidates = (listOf(retestItem.meaning) + retestItem.distractors.take(3)).distinct()
            retestItem.vocabularyId to shuffle(candidates)
        }
        _uiState.value = state.copy(
            phase = VocabularyTestPhase.Retest,
            assessments = assessments,
            retestItems = retestItems,
            choicesById = choices,
        )
    }

    suspend fun answerRetest(choice: String): Unit = withContext(dispatcher) {
        val state = _uiState.value
        if (
            state.phase != VocabularyTestPhase.Retest ||
            state.isAnswerLocked
        ) {
            return@withContext
        }
        val item = state.currentRetest ?: return@withContext
        val isCorrect = choice == item.meaning
        val results = state.retestResults + (item.vocabularyId to isCorrect)
        _uiState.value = state.copy(
            retestResults = results,
            isAnswerLocked = true,
            retestFeedback = if (isCorrect) {
                RetestFeedback.Correct
            } else {
                RetestFeedback.Incorrect(item.meaning)
            },
        )
        delay(FEEDBACK_DURATION_MS)

        val updated = _uiState.value
        if (state.retestIndex < state.retestItems.lastIndex) {
            _uiState.value = updated.copy(
                retestIndex = state.retestIndex + 1,
                isAnswerLocked = false,
                retestFeedback = null,
            )
        } else {
            submit()
        }
    }

    suspend fun retrySubmission(): Unit = withContext(dispatcher) {
        if (_uiState.value.phase == VocabularyTestPhase.SubmitError) submit()
    }

    private suspend fun submit() {
        val state = _uiState.value
        val results = state.items.map { item ->
            val selfKnown = state.assessments[item.vocabularyId] == true
            VocabularyTestResultItemRequest(
                vocabularyId = item.vocabularyId,
                selfKnown = selfKnown,
                retestCorrect = if (selfKnown) null else state.retestResults[item.vocabularyId] ?: false,
            )
        }
        _uiState.value = state.copy(
            phase = VocabularyTestPhase.Submitting,
            isAnswerLocked = false,
            retestFeedback = null,
        )
        try {
            api.submitVocabularyTestResults(results)
            val retestResults = results.mapNotNull { it.retestCorrect }
            _uiState.value = _uiState.value.copy(
                phase = VocabularyTestPhase.Result,
                summary = VocabularyTestSummary(
                    knownCount = results.count { it.selfKnown },
                    retestCount = retestResults.size,
                    retestCorrectCount = retestResults.count { it },
                ),
            )
        } catch (_: ApiException) {
            _uiState.value = _uiState.value.copy(phase = VocabularyTestPhase.SubmitError)
        }
    }

    private companion object {
        const val MAX_SESSION_WORDS = 10
        const val FEEDBACK_DURATION_MS = 800L
    }
}

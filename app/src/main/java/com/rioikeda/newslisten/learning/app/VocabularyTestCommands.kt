package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.domain.VocabularyTestPhase
import com.rioikeda.newslisten.learning.domain.VocabularyTestSession
import com.rioikeda.newslisten.learning.domain.VocabularyTestWord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 単語テストの入口（android-cmd-learning-03・android-query-learning-04）。
 * [VocabularyTestSession] を進めて [VocabularyTestView] を公開し、800ms の待ち・送信・失敗の握りを持つ。
 *
 * 状態は instance ごとに 1 つ。開くたびに新しい instance を作る（android-dec-106）。
 * 4 操作は [dispatcher] の上で動く。
 */
class VocabularyTestCommands(
    private val api: VocabularyTestApi,
    private val dispatcher: CoroutineDispatcher,
    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },
) {
    private var session: VocabularyTestSession = VocabularyTestSession.loading()
    private val _vocabularyTest = MutableStateFlow(session.toView())
    val vocabularyTest: StateFlow<VocabularyTestView> = _vocabularyTest.asStateFlow()

    /** 前回の回答・結果を捨てて最初からやり直す。取得の失敗は [ApiException] を握って `LoadError` にする。 */
    suspend fun startVocabularyTest(): Unit = withContext(dispatcher) {
        publish(VocabularyTestSession.loading())
        try {
            val words = api.fetchVocabularyTestSession().items.map {
                VocabularyTestWord(it.vocabularyId, it.term, it.meaning, it.example, it.distractors)
            }
            publish(VocabularyTestSession.started(words))
        } catch (_: ApiException) {
            publish(VocabularyTestSession.loadFailed())
        }
    }

    suspend fun assess(known: Boolean): Unit = withContext(dispatcher) {
        val next = session.assess(known, shuffle)
        if (next === session) return@withContext
        publish(next)
        submitIfSubmitting()
    }

    suspend fun answerRetest(choice: String): Unit = withContext(dispatcher) {
        val answered = session.answerRetest(choice)
        if (answered === session) return@withContext
        publish(answered)
        delay(FEEDBACK_DURATION_MS)

        // 待つ間に状態が替わっていれば、前進せず何も公開しない。
        val advanced = session.advance()
        if (advanced === session) return@withContext
        publish(advanced)
        submitIfSubmitting()
    }

    suspend fun retrySubmission(): Unit = withContext(dispatcher) {
        val next = session.retrySubmission()
        if (next === session) return@withContext
        publish(next)
        submitIfSubmitting()
    }

    private suspend fun submitIfSubmitting() {
        if (session.phase != VocabularyTestPhase.Submitting) return
        val results = session.answers.map { VocabularyTestAnswerRecord(it.vocabularyId, it.selfKnown, it.retestCorrect) }
        try {
            api.submitVocabularyTestResults(results)
            publish(session.submitted())
        } catch (_: ApiException) {
            publish(session.submitFailed())
        }
    }

    private fun publish(next: VocabularyTestSession) {
        session = next
        _vocabularyTest.value = next.toView()
    }

    private fun VocabularyTestSession.toView() = VocabularyTestView(
        phase = phase,
        progress = progress,
        currentTerm = currentWord?.term,
        currentExample = currentWord?.example,
        correctMeaning = if (phase == VocabularyTestPhase.Retest) currentWord?.meaning else null,
        choices = currentChoices,
        retestFeedback = retestFeedback,
        isAnswerLocked = isAnswerLocked,
        summary = summary,
    )

    private companion object {
        const val FEEDBACK_DURATION_MS = 800L
    }
}

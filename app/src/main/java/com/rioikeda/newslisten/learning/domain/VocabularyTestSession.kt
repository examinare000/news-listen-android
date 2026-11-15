package com.rioikeda.newslisten.learning.domain

import java.util.Collections

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

/** 進み。`position` は 1 始まり。 */
data class VocabularyTestProgress(
    val position: Int,
    val total: Int,
)

/** 1 語の回答。`selfKnown` なら再テストをしないので `retestCorrect` は `null`。 */
data class VocabularyTestAnswer(
    val vocabularyId: String,
    val selfKnown: Boolean,
    val retestCorrect: Boolean?,
)

/** 出題の 1 語。`distractors` は渡された list と共有しない読み取り専用の写し。 */
class VocabularyTestWord(
    val vocabularyId: String,
    val term: String,
    val meaning: String,
    val example: String,
    distractors: List<String>,
) {
    val distractors: List<String> = Collections.unmodifiableList(ArrayList(distractors))

    override fun equals(other: Any?): Boolean =
        other is VocabularyTestWord &&
            vocabularyId == other.vocabularyId &&
            term == other.term &&
            meaning == other.meaning &&
            example == other.example &&
            distractors == other.distractors

    override fun hashCode(): Int {
        var result = vocabularyId.hashCode()
        result = 31 * result + term.hashCode()
        result = 31 * result + meaning.hashCode()
        result = 31 * result + example.hashCode()
        result = 31 * result + distractors.hashCode()
        return result
    }
}

/**
 * 単語テストの進行を表す不変の値。遷移はすべて新しい値を返し、受け付けない遷移は同じ instance を返す。
 * 待ち時間・送信・失敗の握りは持たない（application の責務）。
 */
class VocabularyTestSession private constructor(
    val phase: VocabularyTestPhase,
    private val words: List<VocabularyTestWord>,
    private val selfKnown: List<Boolean?>,
    private val assessmentIndex: Int = 0,
    private val retestWordIndices: List<Int> = emptyList(),
    private val retestChoices: List<List<String>> = emptyList(),
    private val retestResults: List<Boolean?> = emptyList(),
    private val retestIndex: Int = 0,
    val isAnswerLocked: Boolean = false,
    val retestFeedback: RetestFeedback? = null,
    val summary: VocabularyTestSummary? = null,
) {
    val currentWord: VocabularyTestWord?
        get() = when (phase) {
            VocabularyTestPhase.SelfAssessment -> words.getOrNull(assessmentIndex)
            VocabularyTestPhase.Retest -> retestWordIndices.getOrNull(retestIndex)?.let { words[it] }
            else -> null
        }

    val progress: VocabularyTestProgress?
        get() = when (phase) {
            VocabularyTestPhase.SelfAssessment -> VocabularyTestProgress(assessmentIndex + 1, words.size)
            VocabularyTestPhase.Retest -> VocabularyTestProgress(retestIndex + 1, retestWordIndices.size)
            else -> null
        }

    val currentChoices: List<String>
        get() = if (phase == VocabularyTestPhase.Retest) {
            Collections.unmodifiableList(ArrayList(retestChoices[retestIndex]))
        } else {
            emptyList()
        }

    /** 出題の順に 1 語 1 件。未回答は（自己申告なし・再テスト不正解）として数える。 */
    val answers: List<VocabularyTestAnswer>
        get() {
            val retestResultByWord = retestWordIndices.zip(retestResults).toMap()
            return words.indices.map { index ->
                val known = selfKnown[index] == true
                VocabularyTestAnswer(
                    vocabularyId = words[index].vocabularyId,
                    selfKnown = known,
                    retestCorrect = if (known) null else retestResultByWord[index] ?: false,
                )
            }
        }

    /** 「知らない」と答えた語を出題の順に再テストの語にする。選択肢はここで 1 回だけ確定する。 */
    fun assess(known: Boolean, shuffle: (List<String>) -> List<String>): VocabularyTestSession {
        if (phase != VocabularyTestPhase.SelfAssessment || currentWord == null) return this
        val assessed = selfKnown.toMutableList().also { it[assessmentIndex] = known }
        if (assessmentIndex < words.lastIndex) {
            return copy(selfKnown = assessed, assessmentIndex = assessmentIndex + 1)
        }
        val retestIndices = words.indices.filter { assessed[it] == false }
        if (retestIndices.isEmpty()) {
            return copy(phase = VocabularyTestPhase.Submitting, selfKnown = assessed)
        }
        val choices = retestIndices.map { index ->
            val word = words[index]
            shuffle((listOf(word.meaning) + word.distractors.take(MAX_DISTRACTORS)).distinct())
        }
        return copy(
            phase = VocabularyTestPhase.Retest,
            selfKnown = assessed,
            retestWordIndices = retestIndices,
            retestChoices = choices,
            retestResults = List(retestIndices.size) { null },
            retestIndex = 0,
        )
    }

    fun answerRetest(choice: String): VocabularyTestSession {
        if (phase != VocabularyTestPhase.Retest || isAnswerLocked) return this
        val word = currentWord ?: return this
        val isCorrect = choice == word.meaning
        return copy(
            retestResults = retestResults.toMutableList().also { it[retestIndex] = isCorrect },
            isAnswerLocked = true,
            retestFeedback = if (isCorrect) RetestFeedback.Correct else RetestFeedback.Incorrect(word.meaning),
        )
    }

    fun advance(): VocabularyTestSession {
        if (phase != VocabularyTestPhase.Retest || !isAnswerLocked) return this
        return if (retestIndex < retestWordIndices.lastIndex) {
            copy(retestIndex = retestIndex + 1, isAnswerLocked = false, retestFeedback = null)
        } else {
            copy(phase = VocabularyTestPhase.Submitting, isAnswerLocked = false, retestFeedback = null)
        }
    }

    fun retrySubmission(): VocabularyTestSession {
        if (phase != VocabularyTestPhase.SubmitError) return this
        return copy(phase = VocabularyTestPhase.Submitting)
    }

    fun submitted(): VocabularyTestSession {
        if (phase != VocabularyTestPhase.Submitting) return this
        val all = answers
        return copy(
            phase = VocabularyTestPhase.Result,
            summary = VocabularyTestSummary(
                knownCount = all.count { it.selfKnown },
                retestCount = all.count { it.retestCorrect != null },
                retestCorrectCount = all.count { it.retestCorrect == true },
            ),
        )
    }

    fun submitFailed(): VocabularyTestSession {
        if (phase != VocabularyTestPhase.Submitting) return this
        return copy(phase = VocabularyTestPhase.SubmitError)
    }

    private fun copy(
        phase: VocabularyTestPhase = this.phase,
        selfKnown: List<Boolean?> = this.selfKnown,
        assessmentIndex: Int = this.assessmentIndex,
        retestWordIndices: List<Int> = this.retestWordIndices,
        retestChoices: List<List<String>> = this.retestChoices,
        retestResults: List<Boolean?> = this.retestResults,
        retestIndex: Int = this.retestIndex,
        isAnswerLocked: Boolean = this.isAnswerLocked,
        retestFeedback: RetestFeedback? = this.retestFeedback,
        summary: VocabularyTestSummary? = this.summary,
    ) = VocabularyTestSession(
        phase, words, selfKnown, assessmentIndex, retestWordIndices, retestChoices,
        retestResults, retestIndex, isAnswerLocked, retestFeedback, summary,
    )

    companion object {
        private const val MAX_WORDS = 10
        private const val MAX_DISTRACTORS = 3

        fun loading() = VocabularyTestSession(VocabularyTestPhase.Loading, emptyList(), emptyList())

        /** 先頭 10 語までで始める。空なら `Empty`。 */
        fun started(words: List<VocabularyTestWord>): VocabularyTestSession {
            val taken = ArrayList(words.take(MAX_WORDS))
            val phase = if (taken.isEmpty()) VocabularyTestPhase.Empty else VocabularyTestPhase.SelfAssessment
            return VocabularyTestSession(phase, taken, List(taken.size) { null })
        }

        fun loadFailed() = VocabularyTestSession(VocabularyTestPhase.LoadError, emptyList(), emptyList())
    }
}

package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.designsystem.DSFeedbackVocabulary
import com.rioikeda.newslisten.learning.domain.QuizGrade
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizPresentationTest {
    private fun grade(rate: Double) = QuizGrade(correctCount = 1, total = 2, correctRate = rate, items = emptyList())

    @Test
    fun `正解率が50パーセント以上ならcorrectを返す`() {
        assertEquals(DSFeedbackVocabulary.CORRECT, quizFeedbackVocabulary(grade(0.5)))
        assertEquals(DSFeedbackVocabulary.CORRECT, quizFeedbackVocabulary(grade(1.0)))
    }

    @Test
    fun `正解率が50パーセント未満ならincorrectを返す`() {
        assertEquals(DSFeedbackVocabulary.INCORRECT, quizFeedbackVocabulary(grade(0.49)))
    }
}

package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.designsystem.DSFeedbackVocabulary
import org.junit.Assert.assertEquals
import org.junit.Test

class QuizPresentationTest {
    @Test
    fun `正解率が50パーセント以上ならcorrectを返す`() {
        assertEquals(DSFeedbackVocabulary.CORRECT, quizFeedbackVocabulary(0.5))
        assertEquals(DSFeedbackVocabulary.CORRECT, quizFeedbackVocabulary(1.0))
    }

    @Test
    fun `正解率が50パーセント未満ならincorrectを返す`() {
        assertEquals(DSFeedbackVocabulary.INCORRECT, quizFeedbackVocabulary(0.49))
    }
}

package com.rioikeda.newslisten.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizGradeTest {
    private fun item(index: Int) = QuizItemGrade(questionIndex = index, selectedIndex = 0, correctIndex = 1, isCorrect = false)

    @Test
    fun `android-rule-learning-03 正答率0点5ちょうどは合格で0点49は不合格`() {
        assertTrue(QuizGrade(1, 2, 0.5, emptyList()).passed)
        assertFalse(QuizGrade(49, 100, 0.49, emptyList()).passed)
        assertEquals(0.5, QuizGrade.PASS_THRESHOLD, 0.0)
    }

    @Test
    fun `android-check-06 itemsは構築後に渡したlistを書き換えても変わらず公開されたlistをcastして書き換えられない`() {
        val source = mutableListOf(item(0))
        val grade = QuizGrade(0, 1, 0.0, source)

        source += item(1)

        assertEquals(listOf(item(0)), grade.items)
        @Suppress("UNCHECKED_CAST")
        val exposed = grade.items as MutableList<QuizItemGrade>
        assertThrows(UnsupportedOperationException::class.java) { exposed.add(item(2)) }
    }

    @Test
    fun `correctRateは渡した値のまま再計算しない`() {
        assertEquals(0.3, QuizGrade(1, 2, 0.3, emptyList()).correctRate, 0.0)
    }

    @Test
    fun `itemForは設問番号の項目を返し無ければnull`() {
        val grade = QuizGrade(0, 2, 0.0, listOf(item(0), item(1)))

        assertEquals(1, grade.itemFor(1)?.questionIndex)
        assertNull(grade.itemFor(9))
    }
}

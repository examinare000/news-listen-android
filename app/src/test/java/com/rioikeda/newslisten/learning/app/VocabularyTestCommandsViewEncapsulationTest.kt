package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.learning.domain.RetestFeedback
import com.rioikeda.newslisten.learning.domain.VocabularyTestPhase
import com.rioikeda.newslisten.learning.domain.VocabularyTestProgress
import com.rioikeda.newslisten.learning.domain.VocabularyTestSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 画面が読む [VocabularyTestView] の値の不変と等価（android-check-06）。比較元は必ずリテラルで書く。
 * 生成に渡した list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class VocabularyTestCommandsViewEncapsulationTest {

    private fun view(
        phase: VocabularyTestPhase = VocabularyTestPhase.Retest,
        progress: VocabularyTestProgress? = VocabularyTestProgress(1, 2),
        currentTerm: String? = "term-1",
        currentExample: String? = "example-1",
        correctMeaning: String? = "意味-1",
        choices: List<String> = listOf("意味-1", "誤答A-1"),
        retestFeedback: RetestFeedback? = RetestFeedback.Incorrect("意味-1"),
        isAnswerLocked: Boolean = true,
        summary: VocabularyTestSummary? = VocabularyTestSummary(1, 2, 1),
    ) = VocabularyTestView(
        phase = phase,
        progress = progress,
        currentTerm = currentTerm,
        currentExample = currentExample,
        correctMeaning = correctMeaning,
        choices = choices,
        retestFeedback = retestFeedback,
        isAnswerLocked = isAnswerLocked,
        summary = summary,
    )

    @Test
    fun `android-check-06 構築後に渡したchoicesを書き換えても変わらない`() {
        val choices = mutableListOf("意味-1", "誤答A-1")

        val view = view(choices = choices)
        choices.clear()
        choices.add("other")

        assertEquals(listOf("意味-1", "誤答A-1"), view.choices)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `android-check-06 choicesをMutableListへcastして書き換えても次の読みは変わらない`() {
        val view = view()

        try {
            (view.choices as MutableList<String>).clear()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }

        assertEquals(listOf("意味-1", "誤答A-1"), view.choices)
    }

    @Test
    fun `android-check-06 9項目が等しいviewは等しくhashCodeも等しい`() {
        assertEquals(view(), view())
        assertEquals(view().hashCode(), view().hashCode())
    }

    @Test
    fun `android-check-06 1項目だけ違うviewは等しくない`() {
        val base = view()

        assertNotEquals(base, view(phase = VocabularyTestPhase.Result))
        assertNotEquals(base, view(progress = VocabularyTestProgress(2, 2)))
        assertNotEquals(base, view(currentTerm = "term-2"))
        assertNotEquals(base, view(currentExample = "example-2"))
        assertNotEquals(base, view(correctMeaning = "意味-2"))
        assertNotEquals(base, view(choices = listOf("意味-1")))
        assertNotEquals(base, view(retestFeedback = RetestFeedback.Correct))
        assertNotEquals(base, view(isAnswerLocked = false))
        assertNotEquals(base, view(summary = VocabularyTestSummary(0, 2, 1)))
    }
}

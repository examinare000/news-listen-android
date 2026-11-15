package com.rioikeda.newslisten.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 単語テストの値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 渡した list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class VocabularyTestSessionEncapsulationTest {

    private fun word(index: Int, distractors: List<String> = listOf("誤答A-$index", "誤答B-$index", "誤答C-$index")) =
        VocabularyTestWord("v-$index", "term-$index", "意味-$index", "example-$index", distractors)

    @Test
    fun `android-check-06 startedに渡したlistを書き換えても出題は変わらない`() {
        val words = mutableListOf(word(1))

        val session = VocabularyTestSession.started(words)
        words.clear()

        assertEquals("term-1", session.currentWord?.term)
        assertEquals(VocabularyTestProgress(1, 1), session.progress)
    }

    @Test
    fun `android-check-06 VocabularyTestWordに渡したdistractorsを書き換えても変わらない`() {
        val distractors = mutableListOf("誤答A-1", "誤答B-1", "誤答C-1")

        val word = word(1, distractors)
        distractors.clear()
        distractors.add("other")

        assertEquals(listOf("誤答A-1", "誤答B-1", "誤答C-1"), word.distractors)
    }

    @Test
    fun `android-check-06 answersとcurrentChoicesをMutableListへcastして書き換えても次の読みは変わらない`() {
        val session = VocabularyTestSession.started(listOf(word(1))).assess(false) { it }

        mutate(session.answers)
        mutate(session.currentChoices)
        mutate(session.currentWord!!.distractors)

        assertEquals(listOf(VocabularyTestAnswer("v-1", false, false)), session.answers)
        assertEquals(listOf("意味-1", "誤答A-1", "誤答B-1", "誤答C-1"), session.currentChoices)
        assertEquals(listOf("誤答A-1", "誤答B-1", "誤答C-1"), session.currentWord?.distractors)
    }

    /** 例外になれば不変、書き換えられたなら次の読みで差が出る。 */
    @Suppress("UNCHECKED_CAST")
    private fun mutate(list: List<*>) {
        try {
            (list as MutableList<Any?>).clear()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }
    }
}

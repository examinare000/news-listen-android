package com.rioikeda.newslisten.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VocabularyKeyTest {
    @Test
    fun `android-rule-learning-07 前後の空白と大文字小文字を除いて等価でepisodeが違えば別`() {
        val a = VocabularyKey.of("p1", " Resilient ")
        val b = VocabularyKey.of("p1", "resilient")

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(VocabularyKey.of("p1", "x"), VocabularyKey.of("p2", "x"))
        assertEquals("a", VocabularyKey.of("p1", " A ").term)
        assertEquals("p1", VocabularyKey.of("p1", " A ").episodeId)
    }
}

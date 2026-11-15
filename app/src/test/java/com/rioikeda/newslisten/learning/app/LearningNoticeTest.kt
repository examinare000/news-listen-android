package com.rioikeda.newslisten.learning.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningNoticeTest {
    @Test
    fun `android-dec-70 VocabularyFailedの文言は基点と同じ`() {
        assertEquals("語彙の登録に失敗しました", LearningNotice.VocabularyFailed.message)
    }
}

package com.rioikeda.newslisten.preferences

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [InMemoryPreferencesStore] の挙動検証。テスト用 Fake だが [PreferencesStore] 契約
 * （既定値・set→get・StateFlow 反映）そのものを固定する（フェーズ10 P10 Task3）。
 */
class InMemoryPreferencesStoreTest {

    @Test
    fun 既定値はDifficultyのDEFAULT_速度1_0_ArticleOpenModeのIN_APP_TimeFormatのABSOLUTEである() = runTest {
        val store = InMemoryPreferencesStore()

        assertEquals("toeic_600", store.defaultDifficulty.value)
        assertEquals(1.0, store.defaultPlaybackSpeed.value, 0.0)
        assertEquals(ArticleOpenMode.IN_APP, store.articleOpenMode.value)
        assertEquals(TimeFormat.ABSOLUTE, store.timeFormat.value)
    }

    @Test
    fun setDefaultDifficultyで値を更新するとStateFlowに反映される() = runTest {
        val store = InMemoryPreferencesStore()

        store.setDefaultDifficulty("toeic_900")

        assertEquals("toeic_900", store.defaultDifficulty.value)
    }

    @Test
    fun setDefaultPlaybackSpeedで値を更新するとStateFlowに反映される() = runTest {
        val store = InMemoryPreferencesStore()

        store.setDefaultPlaybackSpeed(1.5)

        assertEquals(1.5, store.defaultPlaybackSpeed.value, 0.0)
    }

    @Test
    fun setArticleOpenModeで値を更新するとStateFlowに反映される() = runTest {
        val store = InMemoryPreferencesStore()

        store.setArticleOpenMode(ArticleOpenMode.EXTERNAL)

        assertEquals(ArticleOpenMode.EXTERNAL, store.articleOpenMode.value)
    }

    @Test
    fun setTimeFormatで値を更新するとStateFlowに反映される() = runTest {
        val store = InMemoryPreferencesStore()

        store.setTimeFormat(TimeFormat.RELATIVE)

        assertEquals(TimeFormat.RELATIVE, store.timeFormat.value)
    }
}

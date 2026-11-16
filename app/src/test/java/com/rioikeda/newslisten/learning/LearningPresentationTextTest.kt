package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.learning.app.WeeklyGoalProgress
import com.rioikeda.newslisten.learning.ui.weeklyGoalProgressText
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningPresentationTextTest {
    @Test
    fun `週の目標の文言は今の表示と同じ`() {
        val progress = WeeklyGoalProgress(goalEpisodes = 5, completedThisWeek = 7, history = emptyList())

        assertEquals("今週 7/目標 5 本", weeklyGoalProgressText(progress))
    }

    @Test
    fun `android-rule-learning-05 週の目標の進みは目標0で0fそれ以外は0から1に収める`() {
        assertEquals(0f, WeeklyGoalProgress(0, 3, emptyList()).fraction, 0f)
        assertEquals(1f, WeeklyGoalProgress(5, 7, emptyList()).fraction, 0f)
        assertEquals(0.6f, WeeklyGoalProgress(5, 3, emptyList()).fraction, 1e-6f)
    }
}

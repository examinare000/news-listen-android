package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.learning.app.WeeklyGoalProgress
import com.rioikeda.newslisten.learning.ui.weeklyGoalProgressText
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningPresentationTextTest {
    @Test
    fun `週の目標の文言は今の表示と同じ`() {
        val progress = WeeklyGoalProgress(goalEpisodes = 5, completedThisWeek = 7, fraction = 1f, history = emptyList())

        assertEquals("今週 7/目標 5 本", weeklyGoalProgressText(progress))
    }
}

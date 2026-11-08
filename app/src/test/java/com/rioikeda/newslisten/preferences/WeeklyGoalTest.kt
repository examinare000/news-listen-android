package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 週の目標の値域の正本 [WeeklyGoal]（android-dec-79）。生成は `of` だけで、範囲外は null。 */
class WeeklyGoalTest {

    @Test
    fun `android-dec-79 ofは3と5と7と10だけ受理し他は null にする`() {
        // verifies: android-dec-79
        listOf(3, 5, 7, 10).forEach { n ->
            assertEquals(n, WeeklyGoal.of(n)!!.episodes)
        }
        listOf(0, 4, 9, 11, -1).forEach { n ->
            assertNull("$n は選べない週の目標", WeeklyGoal.of(n))
        }
    }

    @Test
    fun `android-dec-79 DEFAULTは3でallは3と5と7と10の順`() {
        // verifies: android-dec-79
        assertEquals(3, WeeklyGoal.DEFAULT.episodes)
        assertEquals(listOf(3, 5, 7, 10), WeeklyGoal.all.map { it.episodes })
    }

    @Test
    fun `android-dec-79 perDayは週の目標を7日で割った値`() {
        // verifies: android-dec-79
        assertEquals(1.0, WeeklyGoal.of(7)!!.perDay, 0.0)
        assertEquals(3 / 7.0, WeeklyGoal.DEFAULT.perDay, 0.0)
        assertNotNull(WeeklyGoal.of(10))
    }
}

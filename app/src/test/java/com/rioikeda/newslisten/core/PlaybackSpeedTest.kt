package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 再生速度の値域の正本 [PlaybackSpeed]（android-dec-79）。生成は `of` だけで、範囲外は null。 */
class PlaybackSpeedTest {

    @Test
    fun `android-rule-prefs-03 of は 8 段の値だけ受理する`() {
        // verifies: android-contract-17 / android-rule-prefs-03
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5).forEach { speed ->
            val parsed = PlaybackSpeed.of(speed)

            assertNotNull("$speed は選べる速度", parsed)
            assertEquals(speed, parsed!!.multiplier, 0.0)
        }
    }

    @Test
    fun `android-rule-prefs-03 of は値域外と NaN を null にする`() {
        // verifies: android-contract-17 / android-rule-prefs-03
        listOf(0.0, 3.0, 1.0001, 1.0000001, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { speed ->
                assertNull("$speed は選べない速度", PlaybackSpeed.of(speed))
            }
    }

    @Test
    fun `PlaybackSpeedのallは8段の昇順でDEFAULTは1倍`() {
        // verifies: android-dec-79
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5), PlaybackSpeed.all.map { it.multiplier })
        assertEquals(1.0, PlaybackSpeed.DEFAULT.multiplier, 0.0)
        assertTrue(PlaybackSpeed.DEFAULT in PlaybackSpeed.all)
        assertEquals(PlaybackSpeed.all, PlaybackSpeed.all.sortedBy { it.multiplier })
    }
}

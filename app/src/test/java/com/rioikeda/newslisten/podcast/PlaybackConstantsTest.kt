package com.rioikeda.newslisten.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 再生速度の値域（正本は `PlaybackConstants.speeds` の 1 本）を固定する。 */
class PlaybackConstantsTest {

    @Test
    fun `TA-R-PF3 isSelectableSpeed は 8 段の値だけ true`() {
        // verifies: CI-T15 / TA-R-PF3
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5).forEach { speed ->
            assertTrue("$speed は選べる速度", PlaybackConstants.isSelectableSpeed(speed))
        }
    }

    @Test
    fun `TA-R-PF3 isSelectableSpeed は値域外と NaN を false にする`() {
        // verifies: CI-T15 / TA-R-PF3
        listOf(0.0, 3.0, 1.0001, -1.0, Double.NaN).forEach { speed ->
            assertFalse("$speed は選べない速度", PlaybackConstants.isSelectableSpeed(speed))
        }
    }

    @Test
    fun `TA-R-PF3 speeds は 8 段の Double`() {
        // verifies: CI-T15 / TA-R-PF3
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5), PlaybackConstants.speeds)
    }
}

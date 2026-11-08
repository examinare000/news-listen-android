package com.rioikeda.newslisten.podcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** 再生速度の選択肢（`PlaybackConstants.speeds`）が値域の正本と同じ並びであることを固定する。 */
class PlaybackConstantsTest {

    @Test
    fun `android-rule-prefs-03 speeds は 8 段の Double`() {
        // verifies: android-contract-17 / android-rule-prefs-03
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5), PlaybackConstants.speeds)
    }
}

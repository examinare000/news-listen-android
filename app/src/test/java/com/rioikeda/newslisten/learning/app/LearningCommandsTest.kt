package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.preferences.app.PreferencesApi
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.preferences.app.ServerPreferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LearningCommands] は既読の保存を [PreferencesSync] に任せる（android-cmd-learning-04）。 */
class LearningCommandsTest {
    private object NoopPreferencesApi : PreferencesApi {
        override suspend fun fetchPreferences(): ServerPreferences = ServerPreferences("toeic_600", 1.0, 3)
        override suspend fun updateDefaultDifficulty(code: String) {}
        override suspend fun updateDefaultPlaybackSpeed(speed: Double) {}
        override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {}
    }

    private class Rig(val commands: LearningCommands, val store: RecordingPreferencesStore, val log: List<String>)

    private fun TestScope.rig(subject: () -> String?): Rig {
        val log = mutableListOf<String>()
        val store = RecordingPreferencesStore(log)
        val commands = LearningCommands(PreferencesSync(NoopPreferencesApi, store, subject, backgroundScope))
        return Rig(commands, store, log)
    }

    @Test
    fun `android-cmd-learning-04 同じ主体なら既読をstoreへ1回書き戻り値はUnit`() = runTest {
        val rig = rig { "u-1" }

        val result: Unit = rig.commands.markAchievementsSeen(setOf("streak_7"), ReadSubject("u-1"))

        assertEquals(Unit, result)
        assertEquals(listOf("markAchievementsSeen"), rig.log)
        assertEquals(setOf("streak_7"), rig.store.seenAchievementIds.value)
    }

    @Test
    fun `android-dec-56 違う主体なら書かずに捨て戻り値はUnitのまま`() = runTest {
        val rig = rig { "u-2" }

        val result: Unit = rig.commands.markAchievementsSeen(setOf("streak_7"), ReadSubject("u-1"))

        assertEquals(Unit, result)
        assertEquals(emptyList<String>(), rig.log)
        assertEquals(emptySet<String>(), rig.store.seenAchievementIds.value)
    }
}

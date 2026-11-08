package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.preferences.app.ServerPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `PreferencesApiAdapter` は `ApiClient` の設定系 4 操作へ 1 対 1 で委譲し、例外は捕まえず透過する。 */
class PreferencesApiAdapterTest {

    private fun response(weeklyGoalEpisodes: Int? = null) = PreferencesResponse(
        defaultDifficulty = "ielts_7",
        defaultPlaybackSpeed = 1.5,
        digestEnabled = true,
        digestArticleCount = 5,
        weeklyGoalEpisodes = weeklyGoalEpisodes,
    )

    private class RecordingApiClient : BaseFakeApiClient() {
        var fetchResponse: PreferencesResponse? = null
        var failure: Throwable? = null
        val updatePreferencesCalls = mutableListOf<Pair<String?, Double?>>()
        val weeklyGoalCalls = mutableListOf<Int>()

        override suspend fun fetchPreferences(): PreferencesResponse {
            failure?.let { throw it }
            return checkNotNull(fetchResponse)
        }

        override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse {
            updatePreferencesCalls += defaultDifficulty to defaultPlaybackSpeed
            failure?.let { throw it }
            return PreferencesResponse("toeic_600", 1.0, false, 0)
        }

        override suspend fun updateWeeklyGoalEpisodes(weeklyGoalEpisodes: Int): PreferencesResponse {
            weeklyGoalCalls += weeklyGoalEpisodes
            failure?.let { throw it }
            return PreferencesResponse("toeic_600", 1.0, false, 0, weeklyGoalEpisodes)
        }
    }

    @Test
    fun `fetchPreferencesはApiClientの応答を難易度と速度と週の目標のServerPreferencesへ写す`() = runTest {
        val client = RecordingApiClient().apply { fetchResponse = response(weeklyGoalEpisodes = 7) }

        val server = PreferencesApiAdapter(client).fetchPreferences()

        assertEquals(ServerPreferences(defaultDifficulty = "ielts_7", defaultPlaybackSpeed = 1.5, weeklyGoalEpisodes = 7), server)
    }

    @Test
    fun `fetchPreferencesは週の目標が無い旧サーバーの応答をnullのまま写す`() = runTest {
        val client = RecordingApiClient().apply { fetchResponse = response(weeklyGoalEpisodes = null) }

        val server = PreferencesApiAdapter(client).fetchPreferences()

        assertEquals(null, server.weeklyGoalEpisodes)
    }

    @Test
    fun `updateDefaultDifficultyは難易度だけを送る`() = runTest {
        val client = RecordingApiClient()

        PreferencesApiAdapter(client).updateDefaultDifficulty("toeic_900")

        assertEquals(listOf<Pair<String?, Double?>>("toeic_900" to null), client.updatePreferencesCalls)
    }

    @Test
    fun `updateDefaultPlaybackSpeedは速度だけを送る`() = runTest {
        val client = RecordingApiClient()

        PreferencesApiAdapter(client).updateDefaultPlaybackSpeed(1.25)

        assertEquals(listOf<Pair<String?, Double?>>(null to 1.25), client.updatePreferencesCalls)
    }

    @Test
    fun `updateWeeklyGoalEpisodesは週の目標を送る`() = runTest {
        val client = RecordingApiClient()

        PreferencesApiAdapter(client).updateWeeklyGoalEpisodes(10)

        assertEquals(listOf(10), client.weeklyGoalCalls)
        assertTrue(client.updatePreferencesCalls.isEmpty())
    }

    @Test
    fun `ApiExceptionは4操作のどれでも捕まえず同じインスタンスで透過する`() = runTest {
        val expected = ApiException.RateLimited(retryAfterSeconds = 10)
        val client = RecordingApiClient().apply { failure = expected }
        val adapter = PreferencesApiAdapter(client)
        val operations: List<suspend () -> Unit> = listOf(
            { adapter.fetchPreferences() },
            { adapter.updateDefaultDifficulty("toeic_900") },
            { adapter.updateDefaultPlaybackSpeed(1.5) },
            { adapter.updateWeeklyGoalEpisodes(7) },
        )

        operations.forEach { operation ->
            try {
                operation()
                fail("ApiException が透過しなかった")
            } catch (thrown: ApiException) {
                assertSame(expected, thrown)
            }
        }
    }
}

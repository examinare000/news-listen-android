package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OkHttpApiClientLearningTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpApiClient(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `学習ダッシュボードをGETして旧サーバー応答も復元する`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(OLD_DASHBOARD_JSON))

        val response = client.fetchLearningDashboard()

        assertEquals("GET", server.takeRequest().method)
        assertNull(response.weeklyGoal)
        assertNull(response.achievements)
    }

    @Test
    fun `週次目標だけをsnake_caseのPUTで更新する`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PREFERENCES_JSON))

        val response = client.updateWeeklyGoalEpisodes(7)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/settings/preferences", request.path)
        assertEquals("""{"weekly_goal_episodes":7}""", request.body.readUtf8())
        assertEquals(7, response.weeklyGoalEpisodes)
    }

    @Test
    fun `語彙をPOSTで登録する`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(VOCABULARY_ITEM_JSON))

        val response = client.saveVocabulary("pod-1", "resilient")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/vocabulary", request.path)
        assertEquals("""{"podcast_id":"pod-1","term":"resilient"}""", request.body.readUtf8())
        assertEquals("pod-1__resilient", response.vocabularyId)
    }

    @Test
    fun `登録語彙をGETする`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"vocabulary":[$VOCABULARY_ITEM_JSON],"count":1}"""),
        )

        val response = client.fetchVocabulary()

        assertEquals("/vocabulary", server.takeRequest().path)
        assertEquals(1, response.count)
    }

    @Test
    fun `語彙をDELETEで解除する`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"status":"deleted","vocabulary_id":"pod-1__resilient"}"""),
        )

        val response = client.deleteVocabulary("pod-1__resilient")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/vocabulary/pod-1__resilient", request.path)
        assertEquals("pod-1__resilient", response.vocabularyId)
    }

    @Test
    fun `単語テストセッションをGETする`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"items":[{
                  "vocabulary_id":"pod-1__resilient",
                  "term":"resilient",
                  "meaning":"回復力のある",
                  "example":"The system is resilient.",
                  "distractors":["壊れやすい","短期的な","不透明な"]
                }]}
                """.trimIndent(),
            ),
        )

        val response = client.fetchVocabularyTestSession()

        assertEquals("/vocabulary/test-session", server.takeRequest().path)
        assertEquals("resilient", response.items.single().term)
    }

    @Test
    fun `単語テスト結果をPOSTする`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"updated":1}"""))
        val results = listOf(
            VocabularyTestResultItemRequest(
                vocabularyId = "pod-1__resilient",
                selfKnown = false,
                retestCorrect = true,
            ),
        )

        val response = client.submitVocabularyTestResults(results)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/vocabulary/test-result", request.path)
        assertEquals(
            """[{"vocabulary_id":"pod-1__resilient","self_known":false,"retest_correct":true}]""",
            request.body.readUtf8(),
        )
        assertEquals(1, response.updated)
    }

    private companion object {
        const val VOCABULARY_ITEM_JSON =
            """{"vocabulary_id":"pod-1__resilient","podcast_id":"pod-1","term":"resilient","meaning":"回復力のある","example":"The system is resilient.","registered_at":"2026-07-29T03:00:00+00:00"}"""
        const val PREFERENCES_JSON =
            """{"default_difficulty":"toeic_600","default_playback_speed":1.0,"digest_enabled":false,"digest_article_count":3,"weekly_goal_episodes":7}"""
        const val OLD_DASHBOARD_JSON =
            """{"streak":{"current_streak_days":0,"today_listened":false,"last_listened_day":null},"total_episodes":0,"vocabulary_acquired":0,"quiz":{"quizzed_episodes":0,"average_correct_rate":null,"trend":[]},"monthly_activity":[],"current_difficulty":"toeic_600"}"""
    }
}

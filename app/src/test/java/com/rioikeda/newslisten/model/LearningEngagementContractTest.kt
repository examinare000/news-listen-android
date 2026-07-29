package com.rioikeda.newslisten.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningEngagementContractTest {
    @Test
    fun `学習ダッシュボードの週次目標と実績をsnake_caseから復元する`() {
        val dashboard = NewsListenJson.decodeFromString<LearningDashboardResponse>(
            """
            {
              "streak":{
                "current_streak_days":4,
                "today_listened":true,
                "last_listened_day":"2026-07-29"
              },
              "total_episodes":12,
              "vocabulary_acquired":31,
              "quiz":{
                "quizzed_episodes":2,
                "average_correct_rate":0.75,
                "trend":[{"graded_at":"2026-07-28T12:00:00Z","correct_rate":0.75}]
              },
              "monthly_activity":[{"month":"2026-07","active_days":8}],
              "current_difficulty":"toeic_900",
              "weekly_goal":{
                "goal_episodes":5,
                "week":"2026-W31",
                "completed_this_week":7,
                "history":[{"week":"2026-W30","goal":5,"completed":4}]
              },
              "achievements":[
                {"id":"first_episode_completed","unlocked_at":"2026-07-29"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("今週 7/目標 5 本", dashboard.weeklyGoal?.progressText)
        assertEquals(1f, dashboard.weeklyGoal?.progressFraction)
        assertEquals(4, dashboard.weeklyGoal?.history?.single()?.completed)
        assertEquals("first_episode_completed", dashboard.achievements?.single()?.id)
    }

    @Test
    fun `旧サーバー応答に週次目標と実績がなくても復元できる`() {
        val dashboard = NewsListenJson.decodeFromString<LearningDashboardResponse>(
            """
            {
              "streak":{
                "current_streak_days":0,
                "today_listened":false,
                "last_listened_day":null
              },
              "total_episodes":0,
              "vocabulary_acquired":0,
              "quiz":{
                "quizzed_episodes":0,
                "average_correct_rate":null,
                "trend":[]
              },
              "monthly_activity":[],
              "current_difficulty":"toeic_600"
            }
            """.trimIndent(),
        )

        assertNull(dashboard.weeklyGoal)
        assertNull(dashboard.achievements)
    }

    @Test
    fun `語彙APIの応答をバックエンド契約どおり復元する`() {
        val vocabulary = NewsListenJson.decodeFromString<VocabularyListResponse>(
            """
            {
              "vocabulary":[{
                "vocabulary_id":"pod-1__resilient",
                "podcast_id":"pod-1",
                "term":"resilient",
                "meaning":"回復力のある",
                "example":"The system is resilient.",
                "registered_at":"2026-07-29T03:00:00+00:00"
              }],
              "count":1
            }
            """.trimIndent(),
        )
        val session = NewsListenJson.decodeFromString<VocabularyTestSessionResponse>(
            """
            {
              "items":[{
                "vocabulary_id":"pod-1__resilient",
                "term":"resilient",
                "meaning":"回復力のある",
                "example":"The system is resilient.",
                "distractors":["壊れやすい","短期的な","不透明な"]
              }]
            }
            """.trimIndent(),
        )

        assertEquals(1, vocabulary.count)
        assertEquals("pod-1", vocabulary.vocabulary.single().podcastId)
        assertEquals(3, session.items.single().distractors.size)
    }

    @Test
    fun `単語テスト結果をsnake_caseで送信する`() {
        val encoded = NewsListenJson.encodeToString(
            listOf(
                VocabularyTestResultItemRequest(
                    vocabularyId = "pod-1__resilient",
                    selfKnown = false,
                    retestCorrect = true,
                ),
            ),
        )

        assertEquals(
            """[{"vocabulary_id":"pod-1__resilient","self_known":false,"retest_correct":true}]""",
            encoded,
        )
    }
}

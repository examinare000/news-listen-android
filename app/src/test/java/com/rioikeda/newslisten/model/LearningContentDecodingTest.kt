package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearningContentDecodingTest {
    @Test
    fun `語彙と公開クイズをPodcastレスポンスからデコードできる`() {
        val json = """
            {
              "id":"p1","type":"single","article_ids":["a1"],"difficulty":"toeic_900",
              "audio_url":"https://example.com/p1.mp3","japanese_intro_text":"概要",
              "duration_seconds":120,"status":"completed","created_at":"2026-07-29T00:00:00Z",
              "vocabulary":[{"term":"deploy","meaning_ja":"配備する","example":"We deploy daily."}],
              "quiz":[{"question":"What happens daily?","options":["Deploy","Sleep","Print","Cook"]}]
            }
        """.trimIndent()

        val podcast = NewsListenJson.decodeFromString(PodcastResponse.serializer(), json)

        assertEquals(VocabularyEntry("deploy", "配備する", "We deploy daily."), podcast.vocabulary?.single())
        assertEquals("What happens daily?", podcast.quiz?.single()?.question)
        assertEquals(4, podcast.quiz?.single()?.options?.size)
        assertFalse(json.contains("answer_index"))
    }

    @Test
    fun `学習コンテンツがない旧レスポンスはnullとしてデコードできる`() {
        val json = """
            {
              "id":"p1","type":"single","article_ids":[],"difficulty":"toeic_600",
              "audio_url":"https://example.com/p1.mp3","japanese_intro_text":"概要",
              "duration_seconds":120,"status":"completed","created_at":"2026-07-29T00:00:00Z"
            }
        """.trimIndent()

        val podcast = NewsListenJson.decodeFromString(PodcastResponse.serializer(), json)

        assertEquals(null, podcast.vocabulary)
        assertEquals(null, podcast.quiz)
    }

    @Test
    fun `クイズ回答はanswers配列としてエンコードされる`() {
        val encoded = NewsListenJson.encodeToString(
            QuizAnswerRequest.serializer(),
            QuizAnswerRequest(listOf(0, 3)),
        )

        assertEquals("""{"answers":[0,3]}""", encoded)
    }

    @Test
    fun `採点結果のsnake_caseフィールドをデコードできる`() {
        val response = NewsListenJson.decodeFromString(
            QuizAnswerResponse.serializer(),
            """
                {
                  "correct_count":1,"total":2,"correct_rate":0.5,
                  "results":[
                    {
                      "question_index":0,"selected_index":1,"correct_index":1,
                      "is_correct":true
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(1, response.correctCount)
        assertEquals(0.5, response.correctRate, 0.0)
        assertEquals(QuizGradeResult(0, 1, 1, true), response.results.single())
    }
}

package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ArticleResponse / FeedResponse のデコード検証。
 *
 * 正本: backend/api/schemas.py:32-44（ArticleResponse, FeedResponse）。
 * is_read はデフォルト false（schemas.py:39）。
 */
class ArticleAndFeedDecodingTest {

    @Test
    fun ArticleResponseを実スキーマ相当のJSONからデコードできる() {
        val json = """
            {
              "id": "a1",
              "title": "Sample headline",
              "url": "https://example.com/a1",
              "source": "example.com",
              "score": 0.87,
              "published_at": "2026-07-01T09:00:00+00:00",
              "is_read": true
            }
        """.trimIndent()

        val article = NewsListenJson.decodeFromString(ArticleResponse.serializer(), json)

        assertEquals("a1", article.id)
        assertEquals("Sample headline", article.title)
        assertEquals("https://example.com/a1", article.url)
        assertEquals("example.com", article.source)
        assertEquals(0.87, article.score, 0.0001)
        assertEquals("2026-07-01T09:00:00+00:00", article.publishedAt)
        assertTrue(article.isRead)
    }

    @Test
    fun ArticleResponseはis_read欠落時にfalseへフォールバックする() {
        val json = """
            {
              "id": "a2",
              "title": "No read flag",
              "url": "https://example.com/a2",
              "source": "example.com",
              "score": 0.5,
              "published_at": "2026-07-01T09:00:00+00:00"
            }
        """.trimIndent()

        val article = NewsListenJson.decodeFromString(ArticleResponse.serializer(), json)

        assertFalse(article.isRead)
    }

    @Test
    fun ArticleResponseは未知フィールドを無視して前方互換を保つ() {
        val json = """
            {
              "id": "a3",
              "title": "Future field",
              "url": "https://example.com/a3",
              "source": "example.com",
              "score": 0.1,
              "published_at": "2026-07-01T09:00:00+00:00",
              "is_read": false,
              "difficulty": "toeic_600"
            }
        """.trimIndent()

        val article = NewsListenJson.decodeFromString(ArticleResponse.serializer(), json)

        assertEquals("a3", article.id)
    }

    @Test
    fun FeedResponseは記事リストと日付をデコードできる() {
        val json = """
            {
              "articles": [
                {
                  "id": "a1",
                  "title": "T",
                  "url": "https://example.com/a1",
                  "source": "example.com",
                  "score": 0.9,
                  "published_at": "2026-07-01T09:00:00+00:00",
                  "is_read": false
                }
              ],
              "date": "2026-07-01"
            }
        """.trimIndent()

        val feed = NewsListenJson.decodeFromString(FeedResponse.serializer(), json)

        assertEquals(1, feed.articles.size)
        assertEquals("2026-07-01", feed.date)
    }
}

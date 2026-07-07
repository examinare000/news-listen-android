package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ActionResponse / StarRequest の検証。
 *
 * 正本: backend/api/schemas.py:161-167（ActionResponse）, :170-179（StarRequest）。
 * remaining は非消費経路・無制限時に None（旧クライアント互換のため常に optional・default None）。
 */
class ActionAndStarDecodingTest {

    @Test
    fun ActionResponseはremaining指定時にデコードできる() {
        val json = """
            {
              "status": "starred",
              "article_id": "a1",
              "remaining": 3
            }
        """.trimIndent()

        val action = NewsListenJson.decodeFromString(ActionResponse.serializer(), json)

        assertEquals("starred", action.status)
        assertEquals("a1", action.articleId)
        assertEquals(3, action.remaining)
    }

    @Test
    fun ActionResponseはremaining欠落時にnullへフォールバックする() {
        val json = """
            {
              "status": "dismissed",
              "article_id": "a2"
            }
        """.trimIndent()

        val action = NewsListenJson.decodeFromString(ActionResponse.serializer(), json)

        assertNull(action.remaining)
    }

    @Test
    fun StarRequestはdifficulty指定時にエンコードするとsnake_caseで出力される() {
        val request = StarRequest(difficulty = "toeic_900")

        val encoded = NewsListenJson.encodeToString(StarRequest.serializer(), request)

        assertEquals("""{"difficulty":"toeic_900"}""", encoded)
    }

    @Test
    fun StarRequestはdifficulty省略時にフィールド自体を省略する() {
        // WHY: NewsListenJson は encodeDefaults を有効化していないため、デフォルト値
        // (null) のフィールドはボディから省略される。旧クライアント（ボディなし送信）と
        // 同じ意味になり、schemas.py の Optional 前提と矛盾しない。
        val request = StarRequest()

        val encoded = NewsListenJson.encodeToString(StarRequest.serializer(), request)

        assertEquals("{}", encoded)
    }
}

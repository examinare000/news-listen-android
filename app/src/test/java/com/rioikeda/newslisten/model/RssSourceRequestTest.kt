package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RssSource の追加/更新リクエスト DTO の検証。
 *
 * 正本: backend/api/schemas.py:106-129（RssSourceRequest / RssSourceUpdateRequest）。
 */
class RssSourceRequestTest {

    @Test
    fun 追加リクエストはnameとurlのみをエンコードする() {
        val request = RssSourceCreateRequest(name = "NHK", url = "https://example.com/nhk.xml")

        val encoded = NewsListenJson.encodeToString(RssSourceCreateRequest.serializer(), request)

        assertEquals("""{"name":"NHK","url":"https://example.com/nhk.xml"}""", encoded)
    }

    @Test
    fun 更新リクエストはold_urlをsnake_caseで含めてエンコードする() {
        val request = RssSourceUpdateRequest(
            oldUrl = "https://example.com/old.xml",
            name = "NHK",
            url = "https://example.com/nhk.xml",
        )

        val encoded = NewsListenJson.encodeToString(RssSourceUpdateRequest.serializer(), request)

        assertEquals(
            """{"name":"NHK","url":"https://example.com/nhk.xml","old_url":"https://example.com/old.xml"}""",
            encoded,
        )
    }
}

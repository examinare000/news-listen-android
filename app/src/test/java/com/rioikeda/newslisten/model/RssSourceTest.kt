package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RssSource / RssSourcesResponse の検証。
 *
 * 正本: backend/api/schemas.py:131-134（RssSourcesResponse）、
 * backend/shared/models.py:117-119（RssSource: name/url の2フィールドのみ）。
 */
class RssSourceTest {

    @Test
    fun sources配列をRssSourceのリストとしてデコードできる() {
        val json = """
            {
              "sources": [
                {"name": "NHK", "url": "https://example.com/nhk.xml"},
                {"name": "BBC", "url": "https://example.com/bbc.xml"}
              ]
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(RssSourcesResponse.serializer(), json)

        assertEquals(2, response.sources.size)
        assertEquals("NHK", response.sources[0].name)
        assertEquals("https://example.com/nhk.xml", response.sources[0].url)
        assertEquals("BBC", response.sources[1].name)
    }

    @Test
    fun sourcesが空配列でもデコードできる() {
        val response = NewsListenJson.decodeFromString(
            RssSourcesResponse.serializer(),
            """{"sources": []}""",
        )

        assertEquals(0, response.sources.size)
    }
}

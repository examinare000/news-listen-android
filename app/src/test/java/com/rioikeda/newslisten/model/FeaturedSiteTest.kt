package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FeaturedSite / FeaturedSitesResponse の検証。
 *
 * 正本: backend/api/schemas.py:182-193（FeaturedSiteResponse / FeaturedSitesResponse）。
 */
class FeaturedSiteTest {

    @Test
    fun sites配列を全フィールドでデコードできる() {
        val json = """
            {
              "sites": [
                {
                  "id": "site-1",
                  "name": "NHK",
                  "url": "https://example.com/nhk.xml",
                  "thumbnail_url": "https://example.com/nhk.png",
                  "description": "公共放送",
                  "order": 1
                }
              ]
            }
        """.trimIndent()

        val response = NewsListenJson.decodeFromString(FeaturedSitesResponse.serializer(), json)

        val site = response.sites[0]
        assertEquals("site-1", site.id)
        assertEquals("NHK", site.name)
        assertEquals("https://example.com/nhk.xml", site.url)
        assertEquals("https://example.com/nhk.png", site.thumbnailUrl)
        assertEquals("公共放送", site.description)
        assertEquals(1, site.order)
    }

    @Test
    fun thumbnail_urlとdescriptionとorderが省略時はデフォルト値になる() {
        val json = """{"sites": [{"id": "site-1", "name": "NHK", "url": "https://example.com/nhk.xml"}]}"""

        val response = NewsListenJson.decodeFromString(FeaturedSitesResponse.serializer(), json)

        val site = response.sites[0]
        assertNull(site.thumbnailUrl)
        assertNull(site.description)
        assertEquals(0, site.order)
    }
}

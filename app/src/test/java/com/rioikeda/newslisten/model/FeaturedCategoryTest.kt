package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FeaturedCategory のカテゴリ処理ユーティリティのテスト。
 *
 * 正本: web/lib/featuredCategories.ts。
 */
class FeaturedCategoryTest {

    @Test
    fun 有効なカテゴリ値はそのままnormalize() {
        assertEquals("tech", FeaturedCategory.normalize("tech"))
        assertEquals("business", FeaturedCategory.normalize("business"))
        assertEquals("sports", FeaturedCategory.normalize("sports"))
        assertEquals("entertainment", FeaturedCategory.normalize("entertainment"))
        assertEquals("culture", FeaturedCategory.normalize("culture"))
    }

    @Test
    fun null値はtechにnormalize() {
        assertEquals("tech", FeaturedCategory.normalize(null))
    }

    @Test
    fun 未知値はtechにnormalize() {
        assertEquals("tech", FeaturedCategory.normalize("unknown"))
        assertEquals("tech", FeaturedCategory.normalize(""))
    }

    @Test
    fun displayOrderが固定順序である() {
        val expected = listOf("tech", "business", "sports", "entertainment", "culture")
        assertEquals(expected, FeaturedCategory.DISPLAY_ORDER)
    }

    @Test
    fun groupByCategoryInOrderがサイトをカテゴリでグループ化する() {
        val sites = listOf(
            FeaturedSite("site-1", "TechCrunch", "https://example.com/1", category = "tech"),
            FeaturedSite("site-2", "BusinessWeek", "https://example.com/2", category = "business"),
            FeaturedSite("site-3", "ESPN", "https://example.com/3", category = "sports"),
            FeaturedSite("site-4", "Variety", "https://example.com/4", category = "entertainment"),
            FeaturedSite("site-5", "ArtsNews", "https://example.com/5", category = "culture"),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)

        assertEquals(1, grouped["tech"]?.size)
        assertEquals("TechCrunch", grouped["tech"]?.get(0)?.name)
        assertEquals(1, grouped["business"]?.size)
        assertEquals("BusinessWeek", grouped["business"]?.get(0)?.name)
        assertEquals(1, grouped["sports"]?.size)
        assertEquals("ESPN", grouped["sports"]?.get(0)?.name)
        assertEquals(1, grouped["entertainment"]?.size)
        assertEquals("Variety", grouped["entertainment"]?.get(0)?.name)
        assertEquals(1, grouped["culture"]?.size)
        assertEquals("ArtsNews", grouped["culture"]?.get(0)?.name)
    }

    @Test
    fun groupByCategoryInOrderでnull_categoryはtechになる() {
        val sites = listOf(
            FeaturedSite("site-1", "NHK", "https://example.com/nhk", category = null),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)

        assertEquals(1, grouped["tech"]?.size)
        assertEquals("NHK", grouped["tech"]?.get(0)?.name)
    }

    @Test
    fun groupByCategoryInOrderで複数サイトが同じカテゴリにグループ化される() {
        val sites = listOf(
            FeaturedSite("site-1", "TechCrunch", "https://example.com/1", category = "tech"),
            FeaturedSite("site-2", "The Verge", "https://example.com/2", category = "tech"),
            FeaturedSite("site-3", "Hacker News", "https://example.com/3", category = "tech"),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)

        assertEquals(3, grouped["tech"]?.size)
        assertEquals("TechCrunch", grouped["tech"]?.get(0)?.name)
        assertEquals("The Verge", grouped["tech"]?.get(1)?.name)
        assertEquals("Hacker News", grouped["tech"]?.get(2)?.name)
    }

    @Test
    fun groupByCategoryInOrderでサーバ返却順が維持される() {
        val sites = listOf(
            FeaturedSite("site-1", "A", "https://example.com/a", category = "tech", order = 1),
            FeaturedSite("site-2", "B", "https://example.com/b", category = "tech", order = 2),
            FeaturedSite("site-3", "C", "https://example.com/c", category = "tech", order = 3),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)

        assertEquals("A", grouped["tech"]?.get(0)?.name)
        assertEquals("B", grouped["tech"]?.get(1)?.name)
        assertEquals("C", grouped["tech"]?.get(2)?.name)
    }

    @Test
    fun groupByCategoryInOrderで空カテゴリは空の配列で返される() {
        val sites = listOf(
            FeaturedSite("site-1", "TechCrunch", "https://example.com/1", category = "tech"),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)

        assertEquals(1, grouped["tech"]?.size)
        assertEquals(0, grouped["business"]?.size)
        assertEquals(0, grouped["sports"]?.size)
        assertEquals(0, grouped["entertainment"]?.size)
        assertEquals(0, grouped["culture"]?.size)
    }

    @Test
    fun getDisplayOrderCategoriesが空でないカテゴリのみを返す() {
        val sites = listOf(
            FeaturedSite("site-1", "TechCrunch", "https://example.com/1", category = "tech"),
            FeaturedSite("site-2", "ESPN", "https://example.com/2", category = "sports"),
        )

        val grouped = FeaturedCategory.groupByCategoryInOrder(sites)
        val categories = FeaturedCategory.getDisplayOrderCategories(grouped)

        assertEquals(listOf("tech", "sports"), categories)
    }
}

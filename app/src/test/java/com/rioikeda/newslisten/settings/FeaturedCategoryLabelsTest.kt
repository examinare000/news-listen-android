package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** カテゴリの表示名の resource は presentation が持つ（android-dec-116）。 */
class FeaturedCategoryLabelsTest {

    @Test
    fun 五つのカテゴリはそれぞれの表示名のresourceになる() {
        assertEquals(R.string.featured_category_tech, featuredCategoryLabel("tech"))
        assertEquals(R.string.featured_category_business, featuredCategoryLabel("business"))
        assertEquals(R.string.featured_category_sports, featuredCategoryLabel("sports"))
        assertEquals(R.string.featured_category_entertainment, featuredCategoryLabel("entertainment"))
        assertEquals(R.string.featured_category_culture, featuredCategoryLabel("culture"))
    }

    @Test
    fun 未知のカテゴリはtechの表示名になる() {
        assertEquals(R.string.featured_category_tech, featuredCategoryLabel("unknown"))
    }
}

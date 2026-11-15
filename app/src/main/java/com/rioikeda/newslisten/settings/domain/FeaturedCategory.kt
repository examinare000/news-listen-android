package com.rioikeda.newslisten.settings.domain

/**
 * おすすめサイトのカテゴリの正規化と表示順（backend の category: tech, business, sports, entertainment, culture）。
 * 表示名の resource は presentation が持つ。
 *
 * 正本: web/lib/featuredCategories.ts。
 */
object FeaturedCategory {

    val DISPLAY_ORDER = listOf("tech", "business", "sports", "entertainment", "culture")

    /** 未知・null のカテゴリは tech へ正規化する。 */
    fun normalize(category: String?): String {
        if (category == null || !DISPLAY_ORDER.contains(category)) {
            return "tech"
        }
        return category
    }

    /** カテゴリでグループ化する。各カテゴリの配列は返却順を維持し、全カテゴリのキーを持つ。 */
    fun groupByCategoryInOrder(items: List<FeaturedSiteEntry>): Map<String, List<FeaturedSiteEntry>> {
        val result = mutableMapOf<String, MutableList<FeaturedSiteEntry>>()
        for (category in DISPLAY_ORDER) {
            result[category] = mutableListOf()
        }
        for (item in items) {
            result[normalize(item.category)]?.add(item)
        }
        return result
    }

    /** 0 件でないカテゴリだけを表示順で返す。 */
    fun getDisplayOrderCategories(grouped: Map<String, List<FeaturedSiteEntry>>): List<String> {
        return DISPLAY_ORDER.filter { grouped[it]?.isNotEmpty() == true }
    }
}

package com.rioikeda.newslisten.model

import androidx.annotation.StringRes
import com.rioikeda.newslisten.R

/**
 * Featured sites のカテゴリ定義と処理ユーティリティ。
 * backend API の category フィールド（tech, business, sports, entertainment, culture）に対応。
 *
 * 正本: web/lib/featuredCategories.ts。
 */
object FeaturedCategory {

    val DISPLAY_ORDER = listOf("tech", "business", "sports", "entertainment", "culture")

    private val CATEGORY_LABELS = mapOf(
        "tech" to R.string.featured_category_tech,
        "business" to R.string.featured_category_business,
        "sports" to R.string.featured_category_sports,
        "entertainment" to R.string.featured_category_entertainment,
        "culture" to R.string.featured_category_culture,
    )

    /**
     * 与えられた category 値を検証し、無効な場合は 'tech' へ正規化する。
     * category が undefined / null / 未知値の場合、'tech' へ統一してフォールバック。
     */
    fun normalize(category: String?): String {
        if (category == null || !DISPLAY_ORDER.contains(category)) {
            return "tech"
        }
        return category
    }

    /**
     * カテゴリの日本語ラベルを取得する。
     */
    @StringRes
    fun getCategoryLabel(category: String): Int {
        return CATEGORY_LABELS[category] ?: CATEGORY_LABELS["tech"]!!
    }

    /**
     * FeaturedSource 配列をカテゴリでグループ化。
     * 各カテゴリの配列は表示順（DISPLAY_ORDER）に従い、返却順を維持。
     */
    fun groupByCategoryInOrder(items: List<FeaturedSite>): Map<String, List<FeaturedSite>> {
        val result = mutableMapOf<String, MutableList<FeaturedSite>>()
        for (category in DISPLAY_ORDER) {
            result[category] = mutableListOf()
        }

        for (item in items) {
            val normalized = normalize(item.category)
            result[normalized]?.add(item)
        }

        return result
    }

    /**
     * グループ化されたカテゴリマップから、0件でないカテゴリのみを表示順で返す。
     */
    fun getDisplayOrderCategories(grouped: Map<String, List<FeaturedSite>>): List<String> {
        return DISPLAY_ORDER.filter { grouped[it]?.isNotEmpty() == true }
    }
}

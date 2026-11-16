package com.rioikeda.newslisten.settings.ui

import androidx.annotation.StringRes
import com.rioikeda.newslisten.R

/** カテゴリの表示名の resource。未知のカテゴリは tech の表示名にする。 */
@StringRes
internal fun featuredCategoryLabel(category: String): Int = when (category) {
    "business" -> R.string.featured_category_business
    "sports" -> R.string.featured_category_sports
    "entertainment" -> R.string.featured_category_entertainment
    "culture" -> R.string.featured_category_culture
    else -> R.string.featured_category_tech
}

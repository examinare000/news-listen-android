package com.rioikeda.newslisten.settings.domain

/** システム提供のおすすめサイト 1 件。[category] は生の値のまま持ち、正規化は [FeaturedCategory] が行う。 */
data class FeaturedSiteEntry(
    val id: String,
    val name: String,
    val url: String,
    val description: String?,
    val category: String?,
)

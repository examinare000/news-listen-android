package com.rioikeda.newslisten.feed.domain

/** フィードの記事。不変の値で、通信の DTO を持ち込まない（android-dec-112）。 */
data class Article(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    val score: Double,
    val publishedAt: String,
    val isRead: Boolean,
)

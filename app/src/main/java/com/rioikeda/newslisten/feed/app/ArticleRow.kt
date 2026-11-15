package com.rioikeda.newslisten.feed.app

/** 画面が読む記事の 1 行。画面が使わない `isRead` は持たない。 */
data class ArticleRow(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    val score: Double,
    val publishedAt: String,
)

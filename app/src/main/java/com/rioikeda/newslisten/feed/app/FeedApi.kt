package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.feed.domain.Article

/**
 * 記事の port。取得（読み）と Star・Dismiss の送信（書き）を持つ。
 * 実装は失敗を `ApiException` のまま呼び手へ伝える。DTO・JSON は出さない。
 */
interface FeedApi {
    suspend fun fetchFeed(): List<Article>

    suspend fun star(articleId: String, difficulty: String?)

    suspend fun dismiss(articleId: String)
}

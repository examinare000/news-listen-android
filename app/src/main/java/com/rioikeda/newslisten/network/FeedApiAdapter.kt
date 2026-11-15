package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.feed.app.FeedApi
import com.rioikeda.newslisten.feed.domain.Article
import com.rioikeda.newslisten.model.StarRequest

/** [ApiClient] の記事の読み書きを包み、DTO を [Article] に変える。例外は呼び手へそのまま伝える。 */
internal class FeedApiAdapter(private val apiClient: ApiClient) : FeedApi {
    // iOS に filter UI は無いため "all"（backend の既定と等価）を渡す。
    override suspend fun fetchFeed(): List<Article> =
        apiClient.fetchFeed(filter = "all").articles.map {
            Article(it.id, it.title, it.url, it.source, it.score, it.publishedAt, it.isRead)
        }

    override suspend fun star(articleId: String, difficulty: String?) {
        apiClient.starArticle(articleId, StarRequest(difficulty = difficulty))
    }

    override suspend fun dismiss(articleId: String) {
        apiClient.dismissArticle(articleId)
    }
}

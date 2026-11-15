package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.feed.domain.Article

/**
 * 呼出を記録する [FeedApi] の fake。応答と失敗は差し替えられる。
 * 記録は応答の lambda を呼ぶ前に行うので、失敗した呼出も残る。
 */
class FakeFeedApi : FeedApi {
    var onFetchFeed: suspend () -> List<Article> = { emptyList() }
    var onStar: suspend (String, String?) -> Unit = { _, _ -> }
    var onDismiss: suspend (String) -> Unit = {}

    var fetchFeedCallCount: Int = 0
        private set

    val starCalls: MutableList<Pair<String, String?>> = mutableListOf()
    val dismissCalls: MutableList<String> = mutableListOf()

    /** 呼出の順の記録。`fetchFeed`・`star:<id>`・`dismiss:<id>`。 */
    val calls: MutableList<String> = mutableListOf()

    override suspend fun fetchFeed(): List<Article> {
        fetchFeedCallCount++
        calls += "fetchFeed"
        return onFetchFeed()
    }

    override suspend fun star(articleId: String, difficulty: String?) {
        starCalls += articleId to difficulty
        calls += "star:$articleId"
        onStar(articleId, difficulty)
    }

    override suspend fun dismiss(articleId: String) {
        dismissCalls += articleId
        calls += "dismiss:$articleId"
        onDismiss(articleId)
    }
}

/** id `n`・`title-n`・`https://example.com/n` の記事。 */
fun feedArticle(id: String) = Article(
    id = id,
    title = "title-$id",
    url = "https://example.com/$id",
    source = "source",
    score = 0.9,
    publishedAt = "2026-07-01T00:00:00Z",
    isRead = false,
)

/** [feedArticle] と同じ値の読み取りモデル。 */
fun feedRow(id: String) = ArticleRow(
    id = id,
    title = "title-$id",
    url = "https://example.com/$id",
    source = "source",
    score = 0.9,
    publishedAt = "2026-07-01T00:00:00Z",
)

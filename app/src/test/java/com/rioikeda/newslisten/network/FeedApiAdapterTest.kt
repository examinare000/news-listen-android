package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.feed.domain.Article
import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ArticleResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.StarRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** `FeedApiAdapter` は `ApiClient` の DTO を `Article` に写し、例外は捕まえず透過する（android-dec-112）。 */
class FeedApiAdapterTest {

    private class StubApiClient : BaseFakeApiClient() {
        var feed = FeedResponse(articles = emptyList(), date = "2026-07-01")
        var failure: Throwable? = null
        val fetchFilters = mutableListOf<String>()
        val starred = mutableListOf<Pair<String, StarRequest>>()
        val dismissed = mutableListOf<String>()

        override suspend fun fetchFeed(filter: String): FeedResponse {
            fetchFilters += filter
            failure?.let { throw it }
            return feed
        }

        override suspend fun starArticle(id: String, request: StarRequest): ActionResponse {
            starred += id to request
            failure?.let { throw it }
            return ActionResponse(status = "ok", articleId = id)
        }

        override suspend fun dismissArticle(id: String): ActionResponse {
            dismissed += id
            failure?.let { throw it }
            return ActionResponse(status = "ok", articleId = id)
        }
    }

    @Test
    fun `android-dec-112 取得はfilterをallで1回呼び記事の7項目をArticleへ順序を保って写す`() = runTest {
        val client = StubApiClient().apply {
            feed = FeedResponse(
                articles = listOf(
                    ArticleResponse("1", "title-1", "https://example.com/1", "source-1", 0.9, "2026-07-01T00:00:00Z", isRead = true),
                    ArticleResponse("2", "title-2", "https://example.com/2", "source-2", 0.5, "2026-07-02T00:00:00Z", isRead = false),
                ),
                date = "2026-07-01",
            )
        }

        val articles = FeedApiAdapter(client).fetchFeed()

        assertEquals(
            listOf(
                Article("1", "title-1", "https://example.com/1", "source-1", 0.9, "2026-07-01T00:00:00Z", isRead = true),
                Article("2", "title-2", "https://example.com/2", "source-2", 0.5, "2026-07-02T00:00:00Z", isRead = false),
            ),
            articles,
        )
        assertEquals(listOf("all"), client.fetchFilters)
    }

    @Test
    fun `android-dec-112 starは難易度をStarRequestに入れて呼びnullも渡す`() = runTest {
        val client = StubApiClient()
        val adapter = FeedApiAdapter(client)

        adapter.star("1", "toeic_900")
        adapter.star("2", null)

        assertEquals(
            listOf("1" to StarRequest(difficulty = "toeic_900"), "2" to StarRequest(difficulty = null)),
            client.starred,
        )
    }

    @Test
    fun `android-dec-112 dismissはdismissArticleを1回呼ぶ`() = runTest {
        val client = StubApiClient()

        FeedApiAdapter(client).dismiss("1")

        assertEquals(listOf("1"), client.dismissed)
    }

    @Test
    fun `android-dec-112 3操作とも例外は捕まえずに同じinstanceを投げる`() = runTest {
        val failure = ApiException.HttpError(503)
        val client = StubApiClient().apply { this.failure = failure }
        val adapter = FeedApiAdapter(client)

        val operations: List<suspend () -> Unit> = listOf(
            { adapter.fetchFeed() },
            { adapter.star("1", null) },
            { adapter.dismiss("1") },
        )
        for (operation in operations) {
            try {
                operation()
                fail("例外が透過されるはず")
            } catch (e: ApiException) {
                assertSame(failure, e)
            }
        }
    }
}

package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.feed.domain.PendingCuration
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [FeedQueries] の挙動。保留を作る操作は同じ [FeedState] を共有する [FeedCommands] で行う。 */
class FeedQueriesTest {

    private class Harness(scheduler: TestCoroutineScheduler) {
        val api = FakeFeedApi()
        val state = FeedState(StandardTestDispatcher(scheduler))
        val commands = FeedCommands(api, state)
        val queries = FeedQueries(api, state)
    }

    @Test
    fun fetch成功でarticlesが更新される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }

        h.queries.loadFeed()

        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
        assertNull(h.queries.notice.value)
    }

    @Test
    fun fetch失敗でNetworkErrorならOfflineを知らせ前の一覧を残す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }
        h.queries.loadFeed()
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }

        h.queries.loadFeed()

        assertEquals(FeedNotice.Offline, h.queries.notice.value)
        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
    }

    @Test
    fun fetch失敗でHttpErrorならFetchFailedを知らせ前の一覧を残す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }
        h.queries.loadFeed()
        h.api.onFetchFeed = { throw ApiException.HttpError(500) }

        h.queries.loadFeed()

        assertEquals(FeedNotice.FetchFailed, h.queries.notice.value)
        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
    }

    @Test
    fun fetch失敗でRateLimitedなら生成上限でなくFetchRateLimitedを知らせる() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }
        h.queries.loadFeed()
        h.api.onFetchFeed = { throw ApiException.RateLimited(retryAfterSeconds = null) }

        h.queries.loadFeed()

        assertEquals(FeedNotice.FetchRateLimited, h.queries.notice.value)
        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
    }

    @Test
    fun refreshの取得失敗も種類ごとの知らせになる() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.DecodingError(RuntimeException("bad json")) }

        h.queries.refresh()

        assertEquals(FeedNotice.FetchFailed, h.queries.notice.value)
    }

    @Test
    fun refreshは一覧を更新し知らせを消す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.refresh()
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }

        h.queries.refresh()

        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
        assertNull(h.queries.notice.value)
    }

    @Test
    fun `android-check-07 loadFeedとrefreshは保留があってもstarもdismissも送らず保留を残す`() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) }
        h.queries.loadFeed()
        h.commands.star("1")
        h.api.calls.clear()

        h.queries.loadFeed()
        h.queries.refresh()

        assertEquals(emptyList<Pair<String, String?>>(), h.api.starCalls)
        assertEquals(emptyList<String>(), h.api.dismissCalls)
        assertEquals(listOf("fetchFeed", "fetchFeed"), h.api.calls)
        val pending = h.queries.pendingAction.value
        assertEquals("1", pending?.article?.id)
        assertEquals(PendingCuration.Kind.STAR, pending?.kind)
    }

    @Test
    fun loadFeedは開始時に知らせを消し取得が失敗すれば再び入れる() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.loadFeed()
        assertEquals(FeedNotice.Offline, h.queries.notice.value)
        var noticeDuringFetch: FeedNotice? = FeedNotice.Offline
        h.api.onFetchFeed = {
            noticeDuringFetch = h.queries.notice.value
            throw ApiException.HttpError(500)
        }

        h.queries.loadFeed()

        assertNull("取得の最中に知らせが残っている", noticeDuringFetch)
        assertEquals(FeedNotice.FetchFailed, h.queries.notice.value)
    }

    @Test
    fun refreshは開始時には知らせを消さず失敗した取得でも消さない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.refresh()
        var noticeDuringFetch: FeedNotice? = null
        h.api.onFetchFeed = {
            noticeDuringFetch = h.queries.notice.value
            throw ApiException.NetworkError(IOException("offline again"))
        }

        h.queries.refresh()

        assertEquals(FeedNotice.Offline, noticeDuringFetch)
        assertEquals(FeedNotice.Offline, h.queries.notice.value)
    }

    @Test
    fun refreshは取得の最中は知らせを残し成功したときに消す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.refresh()
        var noticeDuringFetch: FeedNotice? = null
        h.api.onFetchFeed = {
            noticeDuringFetch = h.queries.notice.value
            listOf(feedArticle("1"))
        }

        h.queries.refresh()

        assertEquals(FeedNotice.Offline, noticeDuringFetch)
        assertNull(h.queries.notice.value)
    }
}

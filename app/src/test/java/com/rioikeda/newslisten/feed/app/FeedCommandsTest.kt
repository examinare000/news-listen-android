package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.feed.BulkActionResult
import com.rioikeda.newslisten.feed.domain.PendingCuration
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeedCommands] の挙動。一覧は同じ [FeedState] を共有する [FeedQueries] で読み込み・観測する
 * （android-dec-108）。保留の確定は port への送信として `FakeFeedApi` の記録で確かめる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedCommandsTest {

    private class Harness(scheduler: TestCoroutineScheduler) {
        val api = FakeFeedApi()
        val state = FeedState(StandardTestDispatcher(scheduler))
        val commands = FeedCommands(api, state)
        val queries = FeedQueries(api, state)

        /** 一覧を読み込み、読み込みの呼出の記録は消す。 */
        suspend fun load(vararg ids: String) {
            api.onFetchFeed = { ids.map { feedArticle(it) } }
            queries.loadFeed()
            api.calls.clear()
        }

        fun articleIds(): List<String> = queries.articles.value.map { it.id }
    }

    @Test
    fun starで一覧から即時除去され保留にセットされる() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")

        h.commands.star("1")

        assertEquals(listOf(feedRow("2")), h.queries.articles.value)
        val pending = h.queries.pendingAction.value
        assertEquals("1", pending?.article?.id)
        assertEquals(0, pending?.index)
        assertEquals(PendingCuration.Kind.STAR, pending?.kind)
        // 保留中はまだ送信されない（確定は commitPending か次の操作まで遅延）。
        assertTrue(h.api.starCalls.isEmpty())
    }

    @Test
    fun dismissで一覧から即時除去され保留にセットされる() = runTest {
        val h = Harness(testScheduler)
        h.load("1")

        h.commands.dismiss("1")

        assertTrue(h.queries.articles.value.isEmpty())
        assertEquals(PendingCuration.Kind.DISMISS, h.queries.pendingAction.value?.kind)
        assertTrue(h.api.dismissCalls.isEmpty())
    }

    @Test
    fun 連続stageで前の保留がcommitされる() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")

        h.commands.star("1")
        h.commands.dismiss("2")

        assertEquals(listOf("1" to null), h.api.starCalls)
        assertEquals(PendingCuration.Kind.DISMISS, h.queries.pendingAction.value?.kind)
        assertEquals("2", h.queries.pendingAction.value?.article?.id)
    }

    @Test
    fun undoLastで保留を破棄し元の位置に記事を復元する() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2", "3")
        h.commands.dismiss("2")

        h.commands.undoLast()

        assertEquals(listOf(feedRow("1"), feedRow("2"), feedRow("3")), h.queries.articles.value)
        assertNull(h.queries.pendingAction.value)
        assertTrue(h.api.dismissCalls.isEmpty())
    }

    @Test
    fun commitPendingは再入防止で1回のみ送信する() = runTest {
        val h = Harness(testScheduler)
        h.load("1")
        h.commands.star("1")

        h.commands.commitPending()
        h.commands.commitPending()

        assertEquals(1, h.api.starCalls.size)
        assertNull(h.queries.pendingAction.value)
    }

    @Test
    fun `Starはstage時（確定ジェスチャ直後）にfeedbackEventsへSWIPE_CONFIRMEDを出す`() = runTest {
        val h = Harness(testScheduler)
        h.load("1")
        val events = mutableListOf<FeedFeedbackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.queries.feedbackEvents.collect { events += it }
        }

        h.commands.star("1")
        assertEquals(listOf(FeedFeedbackEvent.SWIPE_CONFIRMED), events)

        // 確定の送信では再び出さない。
        h.commands.commitPending()
        assertEquals(listOf(FeedFeedbackEvent.SWIPE_CONFIRMED), events)
    }

    @Test
    fun Dismissと一覧に無いStarはfeedbackEventsを出さない() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        val events = mutableListOf<FeedFeedbackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.queries.feedbackEvents.collect { events += it }
        }

        h.commands.dismiss("1")
        h.commands.star("x")

        assertTrue("事象が出た: $events", events.isEmpty())
    }

    @Test
    fun star明示指定でdifficultyが送られる() = runTest {
        val h = Harness(testScheduler)
        h.load("1")

        h.commands.star("1", "toeic_900")
        h.commands.commitPending()

        assertEquals(listOf("1" to "toeic_900"), h.api.starCalls)
    }

    @Test
    fun 対象が一覧に無いstarは新しい保留を積まず直前の保留だけ確定する() = runTest {
        val h = Harness(testScheduler)
        h.load("1")
        h.commands.dismiss("1")

        h.commands.star("x")

        assertEquals(listOf("1"), h.api.dismissCalls)
        assertNull(h.queries.pendingAction.value)
        assertTrue(h.api.starCalls.isEmpty())
    }

    @Test
    fun commit失敗時は記事を元の位置に戻しNetworkErrorならOfflineを知らせる() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.api.onDismiss = { throw ApiException.NetworkError(IOException("offline")) }
        h.commands.dismiss("1")

        h.commands.commitPending()

        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
        assertEquals(FeedNotice.Offline, h.queries.notice.value)
    }

    @Test
    fun commit失敗時はHttpErrorならActionFailedを知らせる() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.api.onDismiss = { throw ApiException.HttpError(500) }
        h.commands.dismiss("1")

        h.commands.commitPending()

        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
        assertEquals(FeedNotice.ActionFailed, h.queries.notice.value)
    }

    @Test
    fun commitが429なら記事を戻しGenerationLimitを知らせる() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.api.onStar = { _, _ -> throw ApiException.RateLimited(retryAfterSeconds = 90) }
        h.commands.star("1")

        h.commands.commitPending()

        assertEquals(listOf(feedRow("1"), feedRow("2")), h.queries.articles.value)
        assertEquals(FeedNotice.GenerationLimit(90), h.queries.notice.value)
    }

    @Test
    fun 前の保留の確定が失敗しても新しい保留は残る() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.api.onStar = { _, _ -> throw ApiException.HttpError(500) }
        h.commands.star("1")

        h.commands.dismiss("2")

        assertEquals("2", h.queries.pendingAction.value?.article?.id)
        assertEquals(FeedNotice.ActionFailed, h.queries.notice.value)
        assertEquals(listOf("1"), h.articleIds())
    }

    @Test
    fun dismissNoticeは知らせを消す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.loadFeed()
        assertEquals(FeedNotice.Offline, h.queries.notice.value)

        h.commands.dismissNotice()

        assertNull(h.queries.notice.value)
    }

    // --- bulkStar ---

    @Test
    fun bulkStarは全件完走し成功分を一覧から除去し集計する() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2", "3")
        h.api.onStar = { id, _ -> if (id == "2") error("boom") }

        val result = h.commands.bulkStar(setOf("1", "2", "3"))

        assertEquals(listOf(feedRow("2")), h.queries.articles.value)
        assertEquals(BulkActionResult(successCount = 2, failureCount = 1), result)
        assertEquals(3, h.api.starCalls.size)
    }

    @Test
    fun bulkStarはdifficultyを送らない() = runTest {
        val h = Harness(testScheduler)
        h.load("1")

        h.commands.bulkStar(setOf("1"))

        assertEquals(listOf("1" to null), h.api.starCalls)
    }

    @Test
    fun bulkStarは429のretryAfterを集約し生成上限メッセージを表示する() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.api.onStar = { id, _ -> if (id == "1") throw ApiException.RateLimited(retryAfterSeconds = 125) }

        val result = h.commands.bulkStar(setOf("1", "2"))

        assertEquals(BulkActionResult(1, 1, limit = FeedNotice.GenerationLimit(125)), result)
        assertEquals(FeedNotice.GenerationLimit(125), h.queries.notice.value)
    }

    @Test
    fun bulkStarは現在の一覧に無いidを除外する() = runTest {
        val h = Harness(testScheduler)
        h.load("1")

        val result = h.commands.bulkStar(setOf("1", "stale"))

        assertEquals(listOf("1" to null), h.api.starCalls)
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), result)
    }

    @Test
    fun bulkStarは対象idが全て無効ならAPIを呼ばない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) }
        h.queries.loadFeed()

        val result = h.commands.bulkStar(setOf("stale"))

        assertTrue(h.api.starCalls.isEmpty())
        assertEquals(BulkActionResult(successCount = 0, failureCount = 0), result)
        assertEquals(FeedNotice.Offline, h.queries.notice.value)
    }

    @Test
    fun bulkStarは保留を変えない() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2")
        h.commands.dismiss("1")

        h.commands.bulkStar(setOf("2"))

        assertEquals("1", h.queries.pendingAction.value?.article?.id)
        assertTrue(h.api.dismissCalls.isEmpty())
    }

    @Test
    fun android_check_06_bulkStarに渡した集合を呼出の後で書き換えても結果が変わらない() = runTest {
        // FeedState の dispatcher を runTest のものと別の instance にし、withContext に入る時点で
        // 一度止まるようにする。止まっている間に集合を書き換えても、入る前に写していれば結果は変わらない。
        val h = Harness(testScheduler)
        h.load("1", "2")
        val ids = mutableSetOf("1", "2")

        val deferred = async(start = CoroutineStart.UNDISPATCHED) { h.commands.bulkStar(ids) }
        ids.clear()
        advanceUntilIdle()

        assertEquals(BulkActionResult(successCount = 2, failureCount = 0), deferred.await())
        assertEquals(2, h.api.starCalls.size)
    }

    // --- 戻り値の型 ---

    @Test
    fun 入口の戻り値は型で固定されている() = runTest {
        val h = Harness(testScheduler)
        h.load("1", "2", "3")

        val unitStar: Unit = h.commands.star("1")
        val unitDismiss: Unit = h.commands.dismiss("2")
        val unitUndo: Unit = h.commands.undoLast()
        val unitCommit: Unit = h.commands.commitPending()
        val unitNotice: Unit = h.commands.dismissNotice()
        val result: BulkActionResult = h.commands.bulkStar(setOf("3"))

        assertEquals(Unit, unitStar)
        assertEquals(Unit, unitDismiss)
        assertEquals(Unit, unitUndo)
        assertEquals(Unit, unitCommit)
        assertEquals(Unit, unitNotice)
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), result)
    }
}

package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.feed.app.BulkActionResult
import com.rioikeda.newslisten.feed.app.FakeFeedApi
import com.rioikeda.newslisten.feed.app.FeedCommands
import com.rioikeda.newslisten.feed.app.FeedNotice
import com.rioikeda.newslisten.feed.app.FeedQueries
import com.rioikeda.newslisten.feed.app.FeedState
import com.rioikeda.newslisten.feed.app.feedArticle
import com.rioikeda.newslisten.feed.app.feedRow
import com.rioikeda.newslisten.feed.ui.FeedViewModel
import com.rioikeda.newslisten.preferences.domain.ArticleOpenMode
import com.rioikeda.newslisten.preferences.domain.TimeFormat
import com.rioikeda.newslisten.preferences.app.PreferencesView
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeedViewModel] の挙動。画面の状態と中継だけを持つ（android-dec-09・android-dec-109）。
 * port は直接渡さず、[FakeFeedApi] から組んだ [FeedCommands]・[FeedQueries] と
 * `MutableStateFlow<PreferencesView>` で構築する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift のミラー。
 */
class FeedViewModelTest {

    private val offlineMessage = "オフラインです。接続を確認してから、もう一度お試しください"
    private val fetchFailedMessage = "記事を取得できませんでした。通信状況を確かめて、もう一度お試しください"
    private val actionFailedMessage = "操作できませんでした。もう一度お試しください"
    private val fetchRateLimitedMessage = "リクエストが多すぎます。しばらくしてからお試しください。"

    private fun preferences(
        articleOpenMode: ArticleOpenMode = ArticleOpenMode.DEFAULT,
        timeFormat: TimeFormat = TimeFormat.DEFAULT,
    ) = PreferencesView(
        defaultDifficulty = Difficulty.TOEIC_600,
        defaultPlaybackSpeed = PlaybackSpeed.DEFAULT,
        weeklyGoal = WeeklyGoal.DEFAULT,
        articleOpenMode = articleOpenMode,
        timeFormat = timeFormat,
        sfxEnabled = true,
        hapticsEnabled = true,
        seenAchievementIds = emptySet(),
    )

    private class Built(val viewModel: FeedViewModel, val api: FakeFeedApi, val queries: FeedQueries)

    private fun TestScope.build(
        api: FakeFeedApi = FakeFeedApi(),
        preferences: MutableStateFlow<PreferencesView>,
    ): Built {
        val state = FeedState(StandardTestDispatcher(testScheduler))
        val commands = FeedCommands(api, state)
        val queries = FeedQueries(api, state)
        return Built(FeedViewModel(commands, queries, preferences), api, queries)
    }

    private fun TestScope.build(api: FakeFeedApi = FakeFeedApi()): Built =
        build(api, MutableStateFlow(preferences()))

    private fun FakeFeedApi.serving(vararg ids: String) {
        onFetchFeed = { ids.map { feedArticle(it) } }
    }

    // --- loadFeed ---

    @Test
    fun `android-dec-07 fetch失敗でerrorMessageに反映される`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) } }
        val viewModel = build(api).viewModel

        viewModel.loadFeed()

        assertEquals(offlineMessage, viewModel.errorMessage.value)
        assertTrue(viewModel.articles.value.isEmpty())
    }

    @Test
    fun `android-dec-07 fetch失敗でHttpErrorならerrorMessageは取得失敗の文面になる`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.HttpError(500) } }
        val viewModel = build(api).viewModel

        viewModel.loadFeed()

        assertEquals(fetchFailedMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 refresh失敗でNetworkErrorならerrorMessageはオフラインの文面になる`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) } }
        val viewModel = build(api).viewModel

        viewModel.refresh()

        assertEquals(offlineMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 refresh失敗でDecodingErrorならerrorMessageは取得失敗の文面になる`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.DecodingError(RuntimeException("bad")) } }
        val viewModel = build(api).viewModel

        viewModel.refresh()

        assertEquals(fetchFailedMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 取得のRateLimitedは変えない固定の文面を出す`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.RateLimited(retryAfterSeconds = null) } }
        val viewModel = build(api).viewModel

        viewModel.loadFeed()

        assertEquals(fetchRateLimitedMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 commit失敗時は記事を元の位置に戻しerrorMessageに反映する`() = runTest {
        val api = FakeFeedApi().apply {
            serving("1", "2")
            onStar = { _, _ -> throw ApiException.NetworkError(IOException("offline")) }
        }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        val first = viewModel.articles.value.first()

        viewModel.star(first)
        viewModel.commitPending()

        assertEquals(listOf(feedRow("1"), feedRow("2")), viewModel.articles.value)
        assertEquals(offlineMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 commit失敗時はHttpErrorなら操作失敗の文面を出す`() = runTest {
        val api = FakeFeedApi().apply {
            serving("1", "2")
            onDismiss = { throw ApiException.HttpError(500) }
        }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        val first = viewModel.articles.value.first()

        viewModel.dismiss(first)
        viewModel.commitPending()

        assertEquals(listOf(feedRow("1"), feedRow("2")), viewModel.articles.value)
        assertEquals(actionFailedMessage, viewModel.errorMessage.value)
    }

    @Test
    fun commitが429なら記事を戻し生成上限メッセージを表示する() = runTest {
        val api = FakeFeedApi().apply {
            serving("1")
            onStar = { _, _ -> throw ApiException.RateLimited(retryAfterSeconds = 90) }
        }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()

        viewModel.star(viewModel.articles.value.first())
        viewModel.commitPending()

        assertEquals(listOf(feedRow("1")), viewModel.articles.value)
        assertEquals("本日の生成上限に達しました（約2分後に可能）", viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-07 clearErrorMessageはエラーメッセージをnullにクリアする`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { throw ApiException.NetworkError(IOException("offline")) } }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        assertEquals(offlineMessage, viewModel.errorMessage.value)

        viewModel.clearErrorMessage()

        assertNull(viewModel.errorMessage.value)
    }

    // --- 保留の確定の順序（android-check-07） ---

    @Test
    fun `android-check-07 loadFeedは取得前に保留中アクションをcommitする`() = runTest {
        val api = FakeFeedApi().apply { serving("1") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        viewModel.dismiss(viewModel.articles.value.first())
        api.calls.clear()

        viewModel.loadFeed()

        assertEquals(listOf("dismiss:1", "fetchFeed"), api.calls)
        assertEquals(listOf("1"), api.dismissCalls)
        assertEquals(listOf(feedRow("1")), viewModel.articles.value)
        assertNull(viewModel.pendingAction.value)
    }

    @Test
    fun `android-check-07 refreshは取得前に保留中アクションをcommitする`() = runTest {
        val api = FakeFeedApi().apply { serving("1") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        viewModel.dismiss(viewModel.articles.value.first())
        api.calls.clear()

        viewModel.refresh()

        assertEquals(listOf("dismiss:1", "fetchFeed"), api.calls)
        assertEquals(listOf(feedRow("1")), viewModel.articles.value)
        assertNull(viewModel.pendingAction.value)
    }

    // --- 中継（android-dec-109） ---

    @Test
    fun `android-dec-109 articlesとpendingActionとfeedbackEventsはFeedQueriesをそのまま渡す`() = runTest {
        val built = build()

        assertSame(built.queries.articles, built.viewModel.articles)
        assertSame(built.queries.pendingAction, built.viewModel.pendingAction)
        assertSame(built.queries.feedbackEvents, built.viewModel.feedbackEvents)
    }

    @Test
    fun `android-dec-109 loadFeedとrefreshは読み込み中のフラグを取得の間だけ立てる`() = runTest {
        val api = FakeFeedApi().apply { serving("1") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        viewModel.dismiss(viewModel.articles.value.first())

        var loadingDuringDismiss: Boolean? = null
        var loadingDuringFetch: Boolean? = null
        api.onDismiss = { loadingDuringDismiss = viewModel.isLoading.value }
        api.onFetchFeed = {
            loadingDuringFetch = viewModel.isLoading.value
            listOf(feedArticle("1"))
        }
        viewModel.loadFeed()

        assertEquals("isLoading は確定の後に立つ", false, loadingDuringDismiss)
        assertEquals(true, loadingDuringFetch)
        assertFalse(viewModel.isLoading.value)

        viewModel.dismiss(viewModel.articles.value.first())
        var refreshingDuringDismiss: Boolean? = null
        var refreshingDuringFetch: Boolean? = null
        api.onDismiss = { refreshingDuringDismiss = viewModel.isRefreshing.value }
        api.onFetchFeed = {
            refreshingDuringFetch = viewModel.isRefreshing.value
            listOf(feedArticle("1"))
        }
        viewModel.refresh()

        assertEquals("isRefreshing は確定の前に立つ", true, refreshingDuringDismiss)
        assertEquals(true, refreshingDuringFetch)
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun `android-dec-109 starとdismissとundoLastは行のidでcommandsへ中継する`() = runTest {
        val api = FakeFeedApi().apply { serving("1", "2", "3") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()

        viewModel.star(feedRow("1"), "toeic_900")
        viewModel.dismiss(feedRow("2"))
        assertEquals(listOf("1" to "toeic_900"), api.starCalls)
        assertEquals("2", viewModel.pendingAction.value?.article?.id)

        viewModel.undoLast()
        assertNull(viewModel.pendingAction.value)
        assertEquals(listOf("2", "3"), viewModel.articles.value.map { it.id })

        viewModel.dismiss(feedRow("3"))
        viewModel.commitPending()
        assertEquals(listOf("3"), api.dismissCalls)
    }

    @Test
    fun `android-dec-109 bulkStarの戻りをbulkActionResultに置きclearBulkActionResultで消す`() = runTest {
        val api = FakeFeedApi().apply { serving("1") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        assertNull(viewModel.bulkActionResult.value)

        viewModel.bulkStar(setOf("1"))
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), viewModel.bulkActionResult.value)

        viewModel.clearBulkActionResult()
        assertNull(viewModel.bulkActionResult.value)
    }

    @Test
    fun clearBulkActionResultは結果をnullにクリアする() = runTest {
        val api = FakeFeedApi().apply { serving("1") }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()
        viewModel.bulkStar(setOf("1"))
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), viewModel.bulkActionResult.value)

        viewModel.clearBulkActionResult()

        assertNull(viewModel.bulkActionResult.value)
    }

    @Test
    fun bulkStarは429のretryAfterを集約し生成上限メッセージを表示する() = runTest {
        val api = FakeFeedApi().apply {
            serving("1", "2")
            onStar = { id, _ -> if (id == "1") throw ApiException.RateLimited(retryAfterSeconds = 125) }
        }
        val viewModel = build(api).viewModel
        viewModel.loadFeed()

        viewModel.bulkStar(setOf("1", "2"))

        assertEquals(
            BulkActionResult(1, 1, limit = FeedNotice.GenerationLimit(125)),
            viewModel.bulkActionResult.value,
        )
        assertEquals("本日の生成上限に達しました（約3分後に可能）", viewModel.errorMessage.value)
    }

    @Test
    fun refreshはisRefreshingをfalseにした後articlesを更新する() = runTest {
        val api = FakeFeedApi().apply { serving("1", "2") }
        val viewModel = build(api).viewModel
        assertFalse(viewModel.isRefreshing.value)

        viewModel.refresh()

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(listOf(feedRow("1"), feedRow("2")), viewModel.articles.value)
        assertNull(viewModel.errorMessage.value)
    }

    // --- 生成上限メッセージ（companion は変えない） ---

    @Test
    fun 生成上限メッセージは秒数なしなら時刻案内なし() {
        assertEquals("本日の生成上限に達しました", FeedViewModel.generationLimitMessage(null))
        assertEquals("本日の生成上限に達しました", FeedViewModel.generationLimitMessage(0))
    }

    @Test
    fun 生成上限メッセージは60秒未満ならまもなく() {
        assertEquals("本日の生成上限に達しました（まもなくに可能）", FeedViewModel.generationLimitMessage(59))
    }

    @Test
    fun 生成上限メッセージは境界値60秒で約1分後() {
        assertEquals("本日の生成上限に達しました（約1分後に可能）", FeedViewModel.generationLimitMessage(60))
    }

    @Test
    fun 生成上限メッセージは境界値61秒で約2分後に切り上げ() {
        assertEquals("本日の生成上限に達しました（約2分後に可能）", FeedViewModel.generationLimitMessage(61))
    }

    @Test
    fun 生成上限メッセージは120秒で約2分後() {
        assertEquals("本日の生成上限に達しました（約2分後に可能）", FeedViewModel.generationLimitMessage(120))
    }

    @Test
    fun 生成上限メッセージは60分ちょうどなら約60分後にせず約1時間後() {
        assertEquals("本日の生成上限に達しました（約1時間後に可能）", FeedViewModel.generationLimitMessage(3600))
    }

    // --- articleOpenMode / timeFormat（PreferencesView の部分の読み） ---

    @Test
    fun `android-dec-109 articleOpenModeは注入したPreferencesViewの現在値を公開する`() = runTest {
        val viewModel = build(preferences = MutableStateFlow(preferences(articleOpenMode = ArticleOpenMode.EXTERNAL))).viewModel

        assertEquals(ArticleOpenMode.EXTERNAL, viewModel.articleOpenMode.value)
    }

    @Test
    fun `android-dec-109 articleOpenModeはPreferencesViewの更新にも追随する`() = runTest {
        val source = MutableStateFlow(preferences(articleOpenMode = ArticleOpenMode.IN_APP))
        val viewModel = build(preferences = source).viewModel
        assertEquals(ArticleOpenMode.IN_APP, viewModel.articleOpenMode.value)

        source.value = preferences(articleOpenMode = ArticleOpenMode.EXTERNAL)

        assertEquals(ArticleOpenMode.EXTERNAL, viewModel.articleOpenMode.value)
    }

    @Test
    fun `android-dec-109 timeFormatは注入したPreferencesViewの現在値を公開する`() = runTest {
        val viewModel = build(preferences = MutableStateFlow(preferences(timeFormat = TimeFormat.RELATIVE))).viewModel

        assertEquals(TimeFormat.RELATIVE, viewModel.timeFormat.value)
    }

    @Test
    fun `android-dec-109 timeFormatはPreferencesViewの更新にも追随する`() = runTest {
        val source = MutableStateFlow(preferences(timeFormat = TimeFormat.ABSOLUTE))
        val viewModel = build(preferences = source).viewModel
        assertEquals(TimeFormat.ABSOLUTE, viewModel.timeFormat.value)

        source.value = preferences(timeFormat = TimeFormat.RELATIVE)

        assertEquals(TimeFormat.RELATIVE, viewModel.timeFormat.value)
    }
}

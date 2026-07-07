package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.model.ActionResponse
import com.rioikeda.newslisten.model.ArticleResponse
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeedViewModel] の挙動検証（フェーズ4 データ層）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift のミラー。
 */
class FeedViewModelTest {

    private fun article(id: String = "1"): ArticleResponse = ArticleResponse(
        id = id,
        title = "title-$id",
        url = "https://example.com/$id",
        source = "source",
        score = 0.9,
        publishedAt = "2026-07-01T00:00:00Z",
    )

    private fun TestScope.newViewModel(
        apiClient: FakeFeedApiClient,
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
    ): FeedViewModel =
        FeedViewModel(apiClient, StandardTestDispatcher(testScheduler), preferencesStore)

    // --- loadFeed（FeedViewModel.swift:45-58） ---

    @Test
    fun fetch成功でarticlesが更新される() = runTest {
        val articles = listOf(article("1"), article("2"))
        val apiClient = FakeFeedApiClient(onFetchFeed = { FeedResponse(articles = articles, date = "2026-07-01") })
        val viewModel = newViewModel(apiClient)

        viewModel.loadFeed()

        assertEquals(articles, viewModel.articles.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun fetch失敗でerrorMessageに反映される() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakeFeedApiClient(onFetchFeed = { throw exception })
        val viewModel = newViewModel(apiClient)

        viewModel.loadFeed()

        assertEquals(exception.message, viewModel.errorMessage.value)
        assertTrue(viewModel.articles.value.isEmpty())
    }

    // --- stage（star/dismiss。FeedViewModel.swift:76-93） ---

    @Test
    fun starで一覧から即時除去され保留にセットされる() = runTest {
        val target = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target, article("2")), date = "2026-07-01") },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.star(target)

        assertEquals(listOf(article("2")), viewModel.articles.value)
        val pending = viewModel.pendingAction.value
        assertEquals(target, pending?.article)
        assertEquals(0, pending?.index)
        assertEquals(PendingArticleAction.Kind.STAR, pending?.kind)
        // 保留中はまだサーバに送信されない（commitPending/次の stage まで遅延）。
        assertTrue(apiClient.starCalls.isEmpty())
    }

    @Test
    fun dismissで一覧から即時除去され保留にセットされる() = runTest {
        val target = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target), date = "2026-07-01") },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.dismiss(target)

        assertTrue(viewModel.articles.value.isEmpty())
        assertEquals(PendingArticleAction.Kind.DISMISS, viewModel.pendingAction.value?.kind)
        assertTrue(apiClient.dismissCalls.isEmpty())
    }

    @Test
    fun 連続stageで前の保留がcommitされる() = runTest {
        val first = article("1")
        val second = article("2")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(first, second), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.star(first)
        viewModel.dismiss(second)

        // 直前の star(first) はここで確定送信されている。
        assertEquals(listOf("1" to StarRequest(difficulty = null)), apiClient.starCalls)
        // 直近の保留は dismiss(second) のみ。
        assertEquals(PendingArticleAction.Kind.DISMISS, viewModel.pendingAction.value?.kind)
        assertEquals(second, viewModel.pendingAction.value?.article)
    }

    // --- undoLast（FeedViewModel.swift:96-101） ---

    @Test
    fun undoLastで保留を破棄し元の位置に記事を復元する() = runTest {
        val first = article("1")
        val second = article("2")
        val third = article("3")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(first, second, third), date = "2026-07-01") },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.dismiss(second) // index 1 から除去

        viewModel.undoLast()

        assertEquals(listOf(first, second, third), viewModel.articles.value)
        assertNull(viewModel.pendingAction.value)
        // 取り消しはサーバ未送信のため副作用が無い。
        assertTrue(apiClient.dismissCalls.isEmpty())
    }

    // --- commitPending（FeedViewModel.swift:105-110） ---

    @Test
    fun commitPendingは再入防止で1回のみ送信する() = runTest {
        val target = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()
        viewModel.star(target)

        viewModel.commitPending()
        viewModel.commitPending() // 2回目は pendingAction が既に null のため何もしない

        assertEquals(1, apiClient.starCalls.size)
        assertNull(viewModel.pendingAction.value)
    }

    @Test
    fun loadFeedは取得前に保留中アクションをcommitする() = runTest {
        val target = article("1")
        // サーバがまだ dismiss を反映していない状態を模し、2回目の fetch でも target を返す
        // （issue #111: 取得前に commit しないとサーバ未反映の記事が再出現し楽観削除と id 重複する）。
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target), date = "2026-07-01") },
            onDismissArticle = { id -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()
        viewModel.dismiss(target)
        assertTrue(apiClient.dismissCalls.isEmpty())

        viewModel.loadFeed()

        // 取得前に保留（dismiss target）が確定送信されている。
        assertEquals(listOf("1"), apiClient.dismissCalls)
        // 2回目の fetch 結果（サーバ未反映で target を含む）がそのまま articles に反映される
        // （id 重複防止自体はサーバ側の反映に依存するが、commit 順序はここで保証する）。
        assertEquals(listOf(target), viewModel.articles.value)
        assertNull(viewModel.pendingAction.value)
    }

    // --- star の difficulty（FeedViewModel.swift:60-68・117。articles.py:142 でサーバ解決） ---

    @Test
    fun star明示指定でdifficultyが送られる() = runTest {
        val target = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.star(target, difficulty = "toeic_900")
        viewModel.commitPending()

        assertEquals(listOf("1" to StarRequest(difficulty = "toeic_900")), apiClient.starCalls)
    }

    // --- commit 失敗時（FeedViewModel.swift:126-130） ---

    @Test
    fun commit失敗時は記事を元の位置に戻しerrorMessageに反映する() = runTest {
        val first = article("1")
        val second = article("2")
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(first, second), date = "2026-07-01") },
            onDismissArticle = { throw exception },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.dismiss(first) // index 0
        viewModel.commitPending()

        assertEquals(listOf(first, second), viewModel.articles.value)
        assertEquals(exception.message, viewModel.errorMessage.value)
    }

    // --- 429（FeedViewModel.swift:121-125・134-149） ---

    @Test
    fun commitが429なら記事を戻し生成上限メッセージを表示する() = runTest {
        val target = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(target), date = "2026-07-01") },
            onStarArticle = { _, _ -> throw ApiException.RateLimited(retryAfterSeconds = 90) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.star(target)
        viewModel.commitPending()

        assertEquals(listOf(target), viewModel.articles.value)
        assertEquals("本日の生成上限に達しました（約2分後に可能）", viewModel.errorMessage.value)
    }

    @Test
    fun 生成上限メッセージは秒数なしなら時刻案内なし() {
        assertEquals(
            "本日の生成上限に達しました",
            FeedViewModel.generationLimitMessage(null),
        )
        assertEquals(
            "本日の生成上限に達しました",
            FeedViewModel.generationLimitMessage(0),
        )
    }

    @Test
    fun 生成上限メッセージは60秒未満ならまもなく() {
        assertEquals(
            "本日の生成上限に達しました（まもなくに可能）",
            FeedViewModel.generationLimitMessage(59),
        )
    }

    @Test
    fun 生成上限メッセージは境界値60秒で約1分後() {
        assertEquals(
            "本日の生成上限に達しました（約1分後に可能）",
            FeedViewModel.generationLimitMessage(60),
        )
    }

    @Test
    fun 生成上限メッセージは境界値61秒で約2分後に切り上げ() {
        assertEquals(
            "本日の生成上限に達しました（約2分後に可能）",
            FeedViewModel.generationLimitMessage(61),
        )
    }

    @Test
    fun 生成上限メッセージは120秒で約2分後() {
        assertEquals(
            "本日の生成上限に達しました（約2分後に可能）",
            FeedViewModel.generationLimitMessage(120),
        )
    }

    @Test
    fun 生成上限メッセージは60分ちょうどなら約60分後にせず約1時間後() {
        assertEquals(
            "本日の生成上限に達しました（約1時間後に可能）",
            FeedViewModel.generationLimitMessage(3600),
        )
    }

    // --- bulkStar（FeedViewModel.swift:166-223） ---

    @Test
    fun bulkStarは全件完走し成功分を一覧から除去し集計する() = runTest {
        val a = article("1")
        val b = article("2")
        val c = article("3")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(a, b, c), date = "2026-07-01") },
            onStarArticle = { id, _ ->
                if (id == "2") error("boom") else ActionResponse(status = "ok", articleId = id)
            },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.bulkStar(setOf("1", "2", "3"))

        // 部分失敗（id=2）があっても全件完走し、成功分（1・3）のみ一覧から除去される。
        assertEquals(listOf(b), viewModel.articles.value)
        assertEquals(BulkActionResult(successCount = 2, failureCount = 1), viewModel.bulkActionResult.value)
        assertEquals(3, apiClient.starCalls.size)
    }

    @Test
    fun bulkStarはdifficultyを送らない() = runTest {
        val a = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(a), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.bulkStar(setOf("1"))

        assertEquals(listOf("1" to StarRequest(difficulty = null)), apiClient.starCalls)
    }

    @Test
    fun bulkStarは429のretryAfterを集約し生成上限メッセージを表示する() = runTest {
        val a = article("1")
        val b = article("2")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(a, b), date = "2026-07-01") },
            onStarArticle = { id, _ ->
                if (id == "1") {
                    throw ApiException.RateLimited(retryAfterSeconds = 125)
                } else {
                    ActionResponse(status = "ok", articleId = id)
                }
            },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.bulkStar(setOf("1", "2"))

        assertEquals(BulkActionResult(successCount = 1, failureCount = 1), viewModel.bulkActionResult.value)
        assertEquals("本日の生成上限に達しました（約3分後に可能）", viewModel.errorMessage.value)
    }

    @Test
    fun bulkStarは現在の一覧に無いidを除外する() = runTest {
        val a = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(a), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        // "stale" は現在の一覧に存在しない（選択後にリフレッシュ等で消えたケース）。
        viewModel.bulkStar(setOf("1", "stale"))

        assertEquals(listOf("1" to StarRequest(difficulty = null)), apiClient.starCalls)
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), viewModel.bulkActionResult.value)
    }

    @Test
    fun bulkStarは対象idが全て無効ならAPIを呼ばない() = runTest {
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = emptyList(), date = "2026-07-01") },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()

        viewModel.bulkStar(setOf("stale"))

        assertTrue(apiClient.starCalls.isEmpty())
        assertEquals(BulkActionResult(successCount = 0, failureCount = 0), viewModel.bulkActionResult.value)
    }

    // --- clearBulkActionResult / clearErrorMessage（UI 再表示対応） ---

    @Test
    fun clearBulkActionResultは結果をnullにクリアする() = runTest {
        val a = article("1")
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = listOf(a), date = "2026-07-01") },
            onStarArticle = { id, _ -> ActionResponse(status = "ok", articleId = id) },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()
        viewModel.bulkStar(setOf("1"))
        assertEquals(BulkActionResult(successCount = 1, failureCount = 0), viewModel.bulkActionResult.value)

        viewModel.clearBulkActionResult()

        assertNull(viewModel.bulkActionResult.value)
    }

    @Test
    fun clearErrorMessageはエラーメッセージをnullにクリアする() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { throw exception },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()
        assertEquals(exception.message, viewModel.errorMessage.value)

        viewModel.clearErrorMessage()

        assertNull(viewModel.errorMessage.value)
    }

    // --- isRefreshing / refresh（プルリフレッシュ用の分離） ---

    @Test
    fun refreshはisRefreshingをfalseにした後articlesを更新する() = runTest {
        val articles = listOf(article("1"), article("2"))
        val apiClient = FakeFeedApiClient(
            onFetchFeed = { FeedResponse(articles = articles, date = "2026-07-01") },
        )
        val viewModel = newViewModel(apiClient)
        viewModel.loadFeed()
        assertTrue(viewModel.isRefreshing.value == false) // 初期状態

        viewModel.refresh()

        assertFalse(viewModel.isRefreshing.value)
        assertEquals(articles, viewModel.articles.value)
        assertNull(viewModel.errorMessage.value)
    }

    // --- articleOpenMode / timeFormat の再公開（フェーズ10 P10 Task4） ---

    @Test
    fun articleOpenModeは注入したPreferencesStoreの現在値を公開する() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialArticleOpenMode = ArticleOpenMode.EXTERNAL)
        val apiClient = FakeFeedApiClient(onFetchFeed = { FeedResponse(articles = emptyList(), date = "2026-07-01") })
        val viewModel = newViewModel(apiClient, preferencesStore)

        assertEquals(ArticleOpenMode.EXTERNAL, viewModel.articleOpenMode.value)
    }

    @Test
    fun articleOpenModeはPreferencesStoreの更新にも追随する() = runTest {
        val preferencesStore = InMemoryPreferencesStore()
        val apiClient = FakeFeedApiClient(onFetchFeed = { FeedResponse(articles = emptyList(), date = "2026-07-01") })
        val viewModel = newViewModel(apiClient, preferencesStore)

        preferencesStore.setArticleOpenMode(ArticleOpenMode.EXTERNAL)

        assertEquals(ArticleOpenMode.EXTERNAL, viewModel.articleOpenMode.value)
    }

    @Test
    fun timeFormatは注入したPreferencesStoreの現在値を公開する() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialTimeFormat = TimeFormat.RELATIVE)
        val apiClient = FakeFeedApiClient(onFetchFeed = { FeedResponse(articles = emptyList(), date = "2026-07-01") })
        val viewModel = newViewModel(apiClient, preferencesStore)

        assertEquals(TimeFormat.RELATIVE, viewModel.timeFormat.value)
    }

    @Test
    fun timeFormatはPreferencesStoreの更新にも追随する() = runTest {
        val preferencesStore = InMemoryPreferencesStore()
        val apiClient = FakeFeedApiClient(onFetchFeed = { FeedResponse(articles = emptyList(), date = "2026-07-01") })
        val viewModel = newViewModel(apiClient, preferencesStore)

        preferencesStore.setTimeFormat(TimeFormat.RELATIVE)

        assertEquals(TimeFormat.RELATIVE, viewModel.timeFormat.value)
    }
}

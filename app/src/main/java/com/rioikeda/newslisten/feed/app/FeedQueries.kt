package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.feed.domain.Article
import com.rioikeda.newslisten.feed.domain.PendingCuration
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 記事の読み取りと取得（android-query-catalog-02）。[loadFeed]・[refresh] は保留を確定しない
 * （android-check-07）。確定は入口の中継（`FeedViewModel`）が先に [FeedCommands.commitPending] を呼ぶ。
 */
class FeedQueries(
    private val api: FeedApi,
    private val state: FeedState,
) {
    /** 画面が読む一覧。読むたびに写すので、返した list を書き換えても次の読みは変わらない（android-check-06）。 */
    val articles: StateFlow<List<ArticleRow>> =
        derived(state.articles.map(::rowsOf)) { rowsOf(state.articles.value) }

    val pendingAction: StateFlow<PendingCuration?> = state.pending.asStateFlow()

    val notice: StateFlow<FeedNotice?> = state.notice.asStateFlow()

    val feedbackEvents: SharedFlow<FeedFeedbackEvent> = state.feedbackEvents.asSharedFlow()

    /** フィードを取得して一覧を置き換える。開始時に知らせを消し、失敗は知らせに入れる。 */
    suspend fun loadFeed(): Unit = withContext(state.dispatcher) {
        state.notice.value = null
        try {
            state.articles.value = api.fetchFeed()
        } catch (e: ApiException) {
            state.notice.value = fetchNotice(e)
        }
    }

    /** プルリフレッシュ用。成功したときだけ知らせを消す（開始時には消さない）。 */
    suspend fun refresh(): Unit = withContext(state.dispatcher) {
        try {
            state.articles.value = api.fetchFeed()
            state.notice.value = null
        } catch (e: ApiException) {
            state.notice.value = fetchNotice(e)
        }
    }

    private fun fetchNotice(e: ApiException): FeedNotice = when (e) {
        is ApiException.NetworkError -> FeedNotice.Offline
        is ApiException.RateLimited -> FeedNotice.FetchRateLimited
        else -> FeedNotice.FetchFailed
    }

    private fun rowsOf(articles: List<Article>): List<ArticleRow> =
        articles.map { ArticleRow(it.id, it.title, it.url, it.source, it.score, it.publishedAt) }

    /** 読んだ時点で [read] を計算する [StateFlow]。値は保存しない。`collect` は最初の値と変わった値を出す。 */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private fun <T> derived(flow: Flow<T>, read: () -> T): StateFlow<T> = object : StateFlow<T> {
        override val value: T get() = read()
        override val replayCache: List<T> get() = listOf(read())
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            flow.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
}

package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.feed.app.ArticleRow
import com.rioikeda.newslisten.feed.app.FeedCommands
import com.rioikeda.newslisten.feed.app.FeedFeedbackEvent
import com.rioikeda.newslisten.feed.app.FeedNotice
import com.rioikeda.newslisten.feed.app.FeedQueries
import com.rioikeda.newslisten.feed.domain.PendingCuration
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.app.PreferencesView
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Feed タブの画面の状態と中継（android-dec-09・android-dec-109）。一覧・保留・通信は [FeedCommands]・[FeedQueries] が持ち、
 * ここは読み込み中のフラグ、失敗の文面（android-dec-07）、一括 Star の結果と、画面からの呼び出しの中継だけを持つ。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift のミラー。
 *
 * androidx.lifecycle.ViewModel は継承しないプレーンな Kotlin クラス。
 */
class FeedViewModel(
    private val commands: FeedCommands,
    private val queries: FeedQueries,
    private val preferences: StateFlow<PreferencesView>,
) {
    val articles: StateFlow<List<ArticleRow>> = queries.articles

    /** 直近の Star/Dismiss（取り消し可能な保留中操作）。Undo トースト表示用。 */
    val pendingAction: StateFlow<PendingCuration?> = queries.pendingAction

    /** Star の確定ジェスチャ直後の事象。画面が集めて音に写す。 */
    val feedbackEvents: SharedFlow<FeedFeedbackEvent> = queries.feedbackEvents

    /** 記事タップ時の遷移先設定（[ArticleOpener] へそのまま渡す）。 */
    val articleOpenMode: StateFlow<ArticleOpenMode> =
        derived(preferences.map { it.articleOpenMode }) { preferences.value.articleOpenMode }

    /** 記事の日付表記設定（FeedScreen の日付表示切り替えに使う）。 */
    val timeFormat: StateFlow<TimeFormat> =
        derived(preferences.map { it.timeFormat }) { preferences.value.timeFormat }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    /** プルリフレッシュ実行中かどうか（初回ロード [isLoading] と独立）。iOS .refreshable ジェスチャ状態のミラー。 */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** [FeedQueries.notice] の種類から選んだ失敗の文面（android-dec-07）。 */
    val errorMessage: StateFlow<String?> =
        derived(queries.notice.map(::messageOf)) { messageOf(queries.notice.value) }

    private val _bulkActionResult = MutableStateFlow<BulkActionResult?>(null)

    /** 直近の一括 Star 操作の成功/失敗集計（トースト表示用）。 */
    val bulkActionResult: StateFlow<BulkActionResult?> = _bulkActionResult.asStateFlow()

    /**
     * フィードを取得する（初回ロード用）。保留中の Star/Dismiss は一覧を置き換える前に確定させる
     * （issue #111。しないとサーバ未反映の記事がリフレッシュで再出現し、楽観削除と id 重複する）。
     */
    suspend fun loadFeed() {
        commands.commitPending()
        _isLoading.value = true
        queries.loadFeed()
        _isLoading.value = false
    }

    /** フィードをリフレッシュする（プルリフレッシュ用）。[isRefreshing] が上部スピナーを制御する。 */
    suspend fun refresh() {
        _isRefreshing.value = true
        commands.commitPending()
        queries.refresh()
        _isRefreshing.value = false
    }

    /** 記事を Star する。`difficulty` が `null` ならサーバが既定の難易度で解決する。 */
    suspend fun star(article: ArticleRow, difficulty: String? = null) = commands.star(article.id, difficulty)

    suspend fun dismiss(article: ArticleRow) = commands.dismiss(article.id)

    fun undoLast() = commands.undoLast()

    suspend fun commitPending() = commands.commitPending()

    /** 選択中の記事を一括 Star し、結果を [bulkActionResult] に置く。 */
    suspend fun bulkStar(ids: Collection<String>) {
        _bulkActionResult.value = commands.bulkStar(ids)
    }

    /** 一括 Star 操作の結果をクリアする（UI で表示済み後に呼ぶ）。 */
    fun clearBulkActionResult() {
        _bulkActionResult.value = null
    }

    /** 失敗の文面をクリアする（UI で表示済み後に呼ぶ）。 */
    fun clearErrorMessage() {
        commands.dismissNotice()
    }

    private fun messageOf(notice: FeedNotice?): String? = when (notice) {
        null -> null
        FeedNotice.Offline -> OFFLINE_MESSAGE
        FeedNotice.FetchFailed -> FETCH_FAILED_MESSAGE
        FeedNotice.FetchRateLimited -> FETCH_RATE_LIMITED_MESSAGE
        FeedNotice.ActionFailed -> ACTION_FAILED_MESSAGE
        is FeedNotice.GenerationLimit -> generationLimitMessage(notice.retryAfterSeconds)
    }

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

    companion object {
        private const val OFFLINE_MESSAGE = "オフラインです。接続を確認してから、もう一度お試しください"
        private const val FETCH_FAILED_MESSAGE = "記事を取得できませんでした。通信状況を確かめて、もう一度お試しください"
        private const val ACTION_FAILED_MESSAGE = "操作できませんでした。もう一度お試しください"

        // 取得の 429 は android-dec-07 の対象外で、現行の固定の文面を保つ。
        private const val FETCH_RATE_LIMITED_MESSAGE = "リクエストが多すぎます。しばらくしてからお試しください。"

        /**
         * 生成上限メッセージ（次回可能時刻があれば併記・issue #82）。
         *
         * 正本: FeedViewModel.swift:134-149。web（lib/format.ts）と揃えるため derived minutes で
         * 分岐し「約60分後」を避ける（60分ちょうどは「約1時間後」表記になる）。
         */
        fun generationLimitMessage(seconds: Int?): String {
            if (seconds == null || seconds <= 0) {
                return "本日の生成上限に達しました"
            }
            val minutes = (seconds + 59) / 60 // 切り上げ
            val whenText = if (seconds < 60) {
                "まもなく"
            } else if (minutes < 60) {
                "約${minutes}分後"
            } else {
                "約${(seconds + 3599) / 3600}時間後"
            }
            return "本日の生成上限に達しました（${whenText}に可能）"
        }
    }
}

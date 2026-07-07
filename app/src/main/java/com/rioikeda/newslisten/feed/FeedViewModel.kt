package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.model.ArticleResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Feed タブの状態とロジックを担う ViewModel（フェーズ4 データ層）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift のミラー。
 *
 * [AuthViewModel]（com.rioikeda.newslisten.auth）と同様、androidx.lifecycle.ViewModel は
 * 継承しないプレーンな Kotlin クラスとして実装し、Dispatcher をコンストラクタ注入する
 * （viewModelScope 直書きだと Dispatcher をテストで差し替えられず TDD が回せないため）。
 */
class FeedViewModel(
    private val apiClient: ApiClient,
    private val dispatcher: CoroutineDispatcher,
    /**
     * 記事の開き方・日付表記設定の値の正本（フェーズ10 P10 Task4）。FeedViewModel は独自コピーを
     * 持たず、このストアの StateFlow（[articleOpenMode]・[timeFormat]）をそのまま公開する
     * （「どちらが最新か」の二重管理を避ける設計。[com.rioikeda.newslisten.auth.AuthViewModel] と
     * 同じ方針。詳細は [PreferencesStore] のコメント参照）。
     */
    private val preferencesStore: PreferencesStore,
) {
    private val _articles = MutableStateFlow<List<ArticleResponse>>(emptyList())
    val articles: StateFlow<List<ArticleResponse>> = _articles.asStateFlow()

    /** 記事タップ時の遷移先設定（[ArticleOpener] へそのまま渡す）。 */
    val articleOpenMode: StateFlow<ArticleOpenMode> = preferencesStore.articleOpenMode

    /** 記事の日付表記設定（FeedScreen の日付表示切り替えに使う）。 */
    val timeFormat: StateFlow<TimeFormat> = preferencesStore.timeFormat

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    /** プルリフレッシュ実行中かどうか（初回ロード _isLoading と独立）。iOS .refreshable ジェスチャ状態のミラー。 */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _pendingAction = MutableStateFlow<PendingArticleAction?>(null)

    /** 直近の Star/Dismiss（取り消し可能な保留中操作）。Undo トースト表示用に種別・記事を公開する。 */
    val pendingAction: StateFlow<PendingArticleAction?> = _pendingAction.asStateFlow()

    private val _bulkActionResult = MutableStateFlow<BulkActionResult?>(null)

    /** 直近の一括 Star 操作の成功/失敗集計（トースト表示用）。 */
    val bulkActionResult: StateFlow<BulkActionResult?> = _bulkActionResult.asStateFlow()

    /**
     * フィードを取得して [articles] を更新する（初回ロード用）。失敗時は [errorMessage] に反映する。
     * [isLoading] を使い、中央の読み込み表示を制御する。
     *
     * 正本: FeedViewModel.swift:45-58。iOS に filter UI は無いため "all"（backend 既定と等価）を渡す。
     */
    suspend fun loadFeed(): Unit = withContext(dispatcher) {
        // 保留中の Star/Dismiss は一覧を置き換える前に確定させる（issue #111）。
        // これをしないとサーバ未反映の記事がリフレッシュで再出現し、楽観削除と id 重複する。
        commitPendingInternal()
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val response = apiClient.fetchFeed(filter = "all")
            _articles.value = response.articles
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isLoading.value = false
    }

    /**
     * フィードをリフレッシュする（プルリフレッシュ用）。[isRefreshing] を使い、上部スピナーを制御する。
     * 初回ロード中は中央の読み込み表示のみ、プルリフレッシュ時のみ上部スピナーを表示する。
     */
    suspend fun refresh(): Unit = withContext(dispatcher) {
        _isRefreshing.value = true
        try {
            commitPendingInternal()
            val response = apiClient.fetchFeed(filter = "all")
            _articles.value = response.articles
            _errorMessage.value = null
        } catch (e: ApiException) {
            _errorMessage.value = e.message
        }
        _isRefreshing.value = false
    }

    /**
     * 記事を Star する（楽観的に一覧から除去し、確定は commitPending まで遅延）。
     * star は難易度を送らない（サーバが prefs.default_difficulty で解決する。articles.py:142）。
     *
     * @param difficulty 記事単位の明示難易度指定（コンテキストメニュー相当）。`null` ならサーバ解決に委ねる。
     * 正本: FeedViewModel.swift:60-68。
     */
    suspend fun star(article: ArticleResponse, difficulty: String? = null): Unit =
        withContext(dispatcher) { stage(article, PendingArticleAction.Kind.STAR, difficulty) }

    /**
     * 記事を Dismiss する（楽観的に一覧から除去し、確定は commitPending まで遅延）。
     * 正本: FeedViewModel.swift:70-74。
     */
    suspend fun dismiss(article: ArticleResponse): Unit =
        withContext(dispatcher) { stage(article, PendingArticleAction.Kind.DISMISS) }

    /**
     * 操作を保留に積む（取り消しは直近1件）。楽観削除を先に行い、直前の保留はその後に確定送信する
     * （連続スワイプ時に新しい操作の反映が前操作の通信完了を待たないようにするため）。
     *
     * 正本: FeedViewModel.swift:80-93。
     */
    private suspend fun stage(article: ArticleResponse, kind: PendingArticleAction.Kind, difficulty: String? = null) {
        val previous = _pendingAction.value
        val index = _articles.value.indexOfFirst { it.id == article.id }
        if (index >= 0) {
            _articles.value = _articles.value.toMutableList().apply { removeAt(index) }
            _pendingAction.value = PendingArticleAction(article, index, kind, difficulty)
        } else {
            // 対象がリフレッシュ等で一覧から消えている。新規 staging はせず、直前の保留のみ確定する。
            _pendingAction.value = null
        }
        if (previous != null) {
            commit(previous)
        }
    }

    /**
     * 直近の Star/Dismiss を取り消し、記事を元の位置へ戻す（サーバ未送信のため副作用なし）。
     * 正本: FeedViewModel.swift:96-101。
     */
    fun undoLast() {
        val pending = _pendingAction.value ?: return
        restoreArticle(pending)
        _pendingAction.value = null
    }

    /**
     * 保留中の操作をサーバへ確定送信する。失敗時は記事を戻し [errorMessage] に反映する。
     * 取り消し猶予の経過・別操作・画面離脱・バックグラウンド遷移のタイミングで呼ぶ。
     * 正本: FeedViewModel.swift:105-110。
     */
    suspend fun commitPending(): Unit = withContext(dispatcher) { commitPendingInternal() }

    /** [commitPending] の本体。既に dispatcher コンテキスト内（loadFeed 等）から直接呼べるよう分離。 */
    private suspend fun commitPendingInternal() {
        val pending = _pendingAction.value ?: return
        // 再入防止のため先に保留を解除してから送信する（タイマー・別操作の同時到来でも 1 回のみ）。
        // WHY: 直列 Dispatcher（AppContainer が limitedParallelism(1) を注入）を前提に、
        // 読み取り→null 代入→commit 送信が単一スレッドで順序付けられることを保証。
        _pendingAction.value = null
        commit(pending)
    }

    /**
     * 指定の保留操作をサーバへ送信する。失敗時は記事を元の位置へ戻し [errorMessage] に反映する。
     * 正本: FeedViewModel.swift:112-131。
     */
    private suspend fun commit(pending: PendingArticleAction) {
        try {
            when (pending.kind) {
                PendingArticleAction.Kind.STAR ->
                    apiClient.starArticle(pending.article.id, StarRequest(pending.difficulty))
                PendingArticleAction.Kind.DISMISS ->
                    apiClient.dismissArticle(pending.article.id)
            }
        } catch (e: ApiException.RateLimited) {
            // 生成上限到達（issue #82）。記事を戻し、次回可能時刻を添えて案内する。
            restoreArticle(pending)
            _errorMessage.value = generationLimitMessage(e.retryAfterSeconds)
        } catch (e: ApiException) {
            restoreArticle(pending)
            _errorMessage.value = e.message
        }
    }

    /** 保留操作の対象記事を、楽観削除前のインデックス（クランプ済み）に戻す。 */
    private fun restoreArticle(pending: PendingArticleAction) {
        val index = minOf(pending.index, _articles.value.size)
        _articles.value = _articles.value.toMutableList().apply { add(index, pending.article) }
    }

    /**
     * 一括 Star 操作の結果をクリアする（UI で表示済み後、次回の操作開始時に呼ぶ）。
     * 同一の結果が連続で発火するのを防ぐため、null に戻す。
     */
    fun clearBulkActionResult() {
        _bulkActionResult.value = null
    }

    /**
     * エラーメッセージをクリアする（UI で表示済み後、次回の操作開始時に呼ぶ）。
     * 同一の文言が連続で発火するのを防ぐため、nil に戻す。
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 選択中の記事を一括 Star する（部分失敗に強い）。成功分は一覧から削除し、
     * 結果を [bulkActionResult] に保存する。バルクは難易度を送らない（サーバ解決に委ねる）。
     *
     * 正本: FeedViewModel.swift:166-223。
     *
     * @param ids 一括 Star 対象の記事 ID 群。現在表示中の記事に含まれないものは除外する
     *   （選択中にリフレッシュ等で一覧から消えた記事による成功数の水増しを防ぐため）。
     */
    suspend fun bulkStar(ids: Collection<String>): Unit = withContext(dispatcher) {
        val currentIds = _articles.value.map { it.id }.toSet()
        val targetIds = ids.filter { it in currentIds }
        if (targetIds.isEmpty()) {
            _bulkActionResult.value = BulkActionResult(successCount = 0, failureCount = 0)
            return@withContext
        }

        var successCount = 0
        var failureCount = 0
        var limitRetryAfter: Int? = null
        var sawRateLimit = false

        coroutineScope {
            // 各記事を並行数制限なしで Star する。例外は runCatching で握り潰し、
            // awaitAll を直接使わない（最初の例外で残りが打ち切られ部分失敗集計が壊れるのを防ぐ）。
            val deferredResults = targetIds.map { id ->
                async { id to runCatching { apiClient.starArticle(id, StarRequest()) } }
            }
            for (deferred in deferredResults) {
                val (id, result) = deferred.await()
                result.fold(
                    onSuccess = {
                        successCount++
                        _articles.value = _articles.value.filterNot { it.id == id }
                    },
                    onFailure = { error ->
                        failureCount++
                        if (error is ApiException.RateLimited) {
                            sawRateLimit = true
                            limitRetryAfter = error.retryAfterSeconds
                        }
                    },
                )
            }
        }

        // 生成上限に当たっていれば上限メッセージを優先表示する。
        if (sawRateLimit) {
            _errorMessage.value = generationLimitMessage(limitRetryAfter)
        }
        _bulkActionResult.value = BulkActionResult(successCount, failureCount)
    }

    companion object {
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

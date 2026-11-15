package com.rioikeda.newslisten.feed.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.feed.BulkActionResult
import com.rioikeda.newslisten.feed.domain.PendingCuration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 記事の Star・Dismiss と保留の確定（android-cmd-catalog-01・02）。保留は [FeedState] に持ち、
 * 失敗は [FeedNotice] の種類として [FeedState] の知らせの枠に入れる。
 *
 * 状態を読む・書く操作は [FeedState.dispatcher]（直列）の上で動き、読み取り→書込→送信の順序を保つ。
 */
class FeedCommands(
    private val api: FeedApi,
    private val state: FeedState,
) {
    /**
     * 記事を Star する。楽観的に一覧から除き、確定は [commitPending] か次の操作まで遅延する。
     * Star の確定ジェスチャ直後に [FeedFeedbackEvent.SWIPE_CONFIRMED] を出す（サーバ commit 時ではない）。
     */
    suspend fun star(articleId: String, difficulty: String? = null): Unit =
        withContext(state.dispatcher) { stage(articleId, PendingCuration.Kind.STAR, difficulty) }

    /** 記事を Dismiss する。楽観的に一覧から除き、確定は [commitPending] か次の操作まで遅延する。 */
    suspend fun dismiss(articleId: String): Unit =
        withContext(state.dispatcher) { stage(articleId, PendingCuration.Kind.DISMISS, null) }

    /** 直近の保留を取り消し、記事を元の位置へ戻す。未送信なので port は呼ばない。 */
    fun undoLast() {
        val pending = state.pending.value ?: return
        state.articles.value = pending.restoreInto(state.articles.value)
        state.pending.value = null
    }

    /** 保留中の操作を送信して確定する。失敗したら記事を戻して知らせを入れる。 */
    suspend fun commitPending(): Unit = withContext(state.dispatcher) { commitPendingInternal() }

    /**
     * 選択中の記事を一括 Star する（部分失敗に強い）。難易度は送らない。保留は変えない。
     * 429 に当たれば、最後に見た次回可能時刻を知らせと結果の両方に入れる。
     */
    suspend fun bulkStar(ids: Collection<String>): BulkActionResult {
        // 呼び手が渡した集合は、dispatcher に入る前に写す。入った後で呼び手が書き換えても結果を変えない。
        val requested = ids.toList()
        return withContext(state.dispatcher) {
            val currentIds = state.articles.value.map { it.id }.toSet()
            // 選択中にリフレッシュ等で一覧から消えた記事を除く（成功数の水増しを防ぐ）。
            val targetIds = requested.filter { it in currentIds }
            if (targetIds.isEmpty()) return@withContext BulkActionResult(successCount = 0, failureCount = 0)

            var successCount = 0
            var failureCount = 0
            var limit: FeedNotice.GenerationLimit? = null

            coroutineScope {
                // 例外は runCatching で握り、awaitAll を直接使わない
                // （最初の例外で残りが打ち切られ、部分失敗の集計が壊れるのを防ぐ）。
                val deferredResults = targetIds.map { id ->
                    async { id to runCatching { api.star(id, null) } }
                }
                for (deferred in deferredResults) {
                    val (id, result) = deferred.await()
                    result.fold(
                        onSuccess = {
                            successCount++
                            state.articles.value = state.articles.value.filterNot { it.id == id }
                        },
                        onFailure = { error ->
                            failureCount++
                            if (error is ApiException.RateLimited) {
                                limit = FeedNotice.GenerationLimit(error.retryAfterSeconds)
                            }
                        },
                    )
                }
            }

            limit?.let { state.notice.value = it }
            BulkActionResult(successCount, failureCount, limit)
        }
    }

    /** 知らせを消す。 */
    fun dismissNotice() {
        state.notice.value = null
    }

    // 楽観削除を先に行い、直前の保留はその後に確定する（連続スワイプで新しい操作の反映が前操作の通信完了を待たないため）。
    private suspend fun stage(articleId: String, kind: PendingCuration.Kind, difficulty: String?) {
        val previous = state.pending.value
        val staged = PendingCuration.stage(state.articles.value, articleId, kind, difficulty)
        if (staged != null) {
            state.articles.value = staged.first
            state.pending.value = staged.second
            if (kind == PendingCuration.Kind.STAR) state.feedbackEvents.tryEmit(FeedFeedbackEvent.SWIPE_CONFIRMED)
        } else {
            // 対象がリフレッシュ等で一覧から消えている。新しい保留は積まず、直前の保留だけ確定する。
            state.pending.value = null
        }
        if (previous != null) commit(previous)
    }

    private suspend fun commitPendingInternal() {
        val pending = state.pending.value ?: return
        // 再入しても送信が 1 回になるよう、先に保留を外してから送る。
        state.pending.value = null
        commit(pending)
    }

    private suspend fun commit(pending: PendingCuration) {
        try {
            when (pending.kind) {
                PendingCuration.Kind.STAR -> api.star(pending.article.id, pending.difficulty)
                PendingCuration.Kind.DISMISS -> api.dismiss(pending.article.id)
            }
        } catch (e: ApiException) {
            state.articles.value = pending.restoreInto(state.articles.value)
            state.notice.value = when (e) {
                is ApiException.RateLimited -> FeedNotice.GenerationLimit(e.retryAfterSeconds)
                is ApiException.NetworkError -> FeedNotice.Offline
                else -> FeedNotice.ActionFailed
            }
        }
    }
}

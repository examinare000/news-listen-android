package com.rioikeda.newslisten.architecture.fixture.wiring

import android.content.Context
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.SessionStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

// 配線の検査の fixture。どの所有者も、書込を呼ばない query `ping`（または `peek`・`count`）を 1 つ持つ。

/** 構築子で渡す WRITE の hook。 */
class HookedOwner(private val onSave: () -> Unit) {
    fun ping(): Int = 1
}

/** port を構築子で受ける。 */
class PortOwner(private val sessions: SessionStore) {
    fun ping(): Int = 1
}

/** 公開 callback（`var`＋工場の外の既定値 `{}`）。runner が組み立て直後に代入する。 */
class PublicCallbackOwner {
    var onChanged: () -> Unit = {}

    fun ping(): Int = 1
}

/** setter の無い `val` の関数型の field。hook 表が `SETTER` にしても、代入できない。 */
class ReadonlyCallbackOwner(private val onDone: () -> Unit) {
    fun ping(): Int = 1
}

/** ApiClient 系の port の field を持ち、`fetchFeed` を 1 回読むだけの query。command は持たない。 */
class FeedReader(private val api: ApiClient) {
    suspend fun peek(): Int {
        api.fetchFeed("all")
        return 1
    }
}

class DispatcherOwner(private val dispatcher: CoroutineDispatcher) {
    fun ping(): Int = 1
}

/** `PLATFORM`（`android.*`）の field。値に依らず失敗する。 */
class PlatformOwner(private val context: Context?) {
    fun ping(): Int = 1
}

class ScopeOwner(private val scope: CoroutineScope) {
    fun ping(): Int = 1
}

/**
 * 組み立て直後に null の `Job?`・プロセス内の同期の型（`AtomicBoolean`・完全修飾名の `AtomicLong`）・
 * 引数を取る suspend の WRITE hook と READ hook・nullable な宣言の port を持つ。正しい probe を落としてはならない。
 */
class MixedInfraOwner(
    private val api: ApiClient,
    private val onItem: suspend (String) -> Unit,
    private val reorder: (List<String>) -> List<String>,
    private val optionalApi: ApiClient?,
) {
    private var job: Job? = null
    private val flag = AtomicBoolean(false)
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    fun count(): Int = reorder(listOf("a")).size
}

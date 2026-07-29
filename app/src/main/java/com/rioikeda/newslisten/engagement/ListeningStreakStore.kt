package com.rioikeda.newslisten.engagement

import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 画面をまたいで共有する聴取ストリーク状態。 */
interface ListeningStreakStore {
    val listeningStreak: StateFlow<ListeningStreakResponse?>
    val loadFailed: StateFlow<Boolean>
    suspend fun refresh()
}

/**
 * API を正本としてストリークを一箇所に保持する。
 *
 * [onStreakIncreased] は以前に取得済みの値から増えた場合だけ呼ぶ。初回ロードを達成通知として
 * 誤発火させないため、比較元が null の場合は通知しない。
 *
 * 要件4: onStreakIncreased を後から設定可能にし、AppScaffold で feedback をキャプチャ後に
 * セットする設計。Application scope では feedback を作成できないため。
 */
class ApiListeningStreakStore(
    private val fetchListeningStreak: suspend () -> ListeningStreakResponse,
    private val dispatcher: CoroutineDispatcher,
) : ListeningStreakStore {
    constructor(
        apiClient: ApiClient,
        dispatcher: CoroutineDispatcher,
    ) : this(apiClient::fetchListeningStreak, dispatcher)

    /** ストリーク増加時のコールバック。AppScaffold で feedback をキャプチャ後にセット。 */
    var onStreakIncreased: () -> Unit = {}
    private val _listeningStreak = MutableStateFlow<ListeningStreakResponse?>(null)
    override val listeningStreak: StateFlow<ListeningStreakResponse?> = _listeningStreak.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    override val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    override suspend fun refresh(): Unit = withContext(dispatcher) {
        val previous = _listeningStreak.value
        try {
            val refreshed = fetchListeningStreak()
            _listeningStreak.value = refreshed
            _loadFailed.value = false
            if (previous != null && refreshed.currentStreakDays > previous.currentStreakDays) {
                onStreakIncreased()
            }
        } catch (e: ApiException.HttpError) {
            _listeningStreak.value = null
            _loadFailed.value = e.code != 404
        } catch (e: ApiException) {
            _listeningStreak.value = null
            _loadFailed.value = true
        }
    }
}

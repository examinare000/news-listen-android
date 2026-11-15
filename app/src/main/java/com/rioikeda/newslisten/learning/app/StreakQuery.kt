package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 画面をまたいで共有する聴取ストリークの読み（android-query-learning-02・05）。API を正本として一箇所に保持する。
 *
 * 増加の事象は以前に取得済みの値から日数が増えた場合だけ出す。初回の取得を達成と誤認させないため、
 * 比較元が無い場合は出さない。収集者がいない間の事象は保持しない（replay なし）。
 */
class StreakQuery(
    private val api: LearningApi,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _streak = MutableStateFlow<StreakBadge?>(null)
    val streak: StateFlow<StreakBadge?> = _streak.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    // refresh() が収集者の受け取りを待たないよう tryEmit で出す。同じ語彙の連続は音の側が間引くので、古い方を捨てる。
    private val _feedbackEvents = MutableSharedFlow<LearningFeedbackEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val feedbackEvents: SharedFlow<LearningFeedbackEvent> = _feedbackEvents.asSharedFlow()

    suspend fun refresh(): Unit = withContext(dispatcher) {
        val previous = _streak.value
        try {
            val record = api.fetchStreak()
            _streak.value = StreakBadge(
                days = record.days,
                todayListened = record.todayListened,
                lastListenedDay = record.lastListenedDay,
                visible = record.days > 0 && record.lastListenedDay != null,
            )
            _loadFailed.value = false
            if (previous != null && record.days > previous.days) {
                _feedbackEvents.tryEmit(LearningFeedbackEvent.STREAK_INCREASED)
            }
        } catch (_: ApiException.NotFound) {
            _streak.value = null
            _loadFailed.value = false
        } catch (_: ApiException) {
            _streak.value = null
            _loadFailed.value = true
        }
    }
}

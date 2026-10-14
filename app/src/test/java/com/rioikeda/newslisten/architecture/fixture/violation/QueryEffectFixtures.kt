package com.rioikeda.newslisten.architecture.fixture.violation

import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

// 負の fixture: 本番と同じ選別から実行時の検査まで通して、それぞれ「表に無い書込」になる形。
// 正の fixture: 書込だけを除いた同じ形。読み取りだけなので 0 件になる。

/** helper 経由で、同じ値の書込を呼ぶ query。before/after の差分では見えない。 */
class HelperRewritesSameValue(private val preferences: PreferencesStore) {
    suspend fun load() {
        rewrite()
    }

    private suspend fun rewrite() {
        preferences.markAchievementsSeen(setOf("a", "b"))
    }
}

/** getter の中で保存する。 */
class GetterSavesSession(private val sessions: SessionStore) {
    val items: List<String>
        get() {
            sessions.save("token")
            return emptyList()
        }
}

/** 返した Flow の収集時に公開 callback を発火する。 */
class FlowCollectFiresCallback {
    var onChanged: () -> Unit = {}

    fun observe(): Flow<Int> = flowOf(1).onEach { onChanged() }
}

class HelperReadsOnly(private val preferences: PreferencesStore) {
    suspend fun load(): Int = peek()

    private suspend fun peek(): Int = preferences.seenAchievementIds.value.size
}

class GetterReadsOnly(private val preferences: PreferencesStore) {
    val items: Set<String>
        get() = preferences.seenAchievementIds.value
}

class FlowCollectReadsOnly {
    var onChanged: () -> Unit = {}

    fun observe(): Flow<Int> = flowOf(1).map { it + 1 }
}

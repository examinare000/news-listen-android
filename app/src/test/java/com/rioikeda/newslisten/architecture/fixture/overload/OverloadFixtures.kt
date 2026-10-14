package com.rioikeda.newslisten.architecture.fixture.overload

import com.rioikeda.newslisten.network.SessionStore
import com.rioikeda.newslisten.preferences.PreferencesStore

// 同じ名前で signature が 3 つ違う overload。`load()` は port を呼ばず、残りの 2 つは別々の port の書込を 1 回呼ぶ。
class OverloadedLoader(
    private val sessions: SessionStore,
    private val preferences: PreferencesStore,
) {
    fun load(): Int = 7

    fun load(n: Int) {
        sessions.save("token-$n")
    }

    suspend fun load(s: String) {
        preferences.markAchievementsSeen(setOf(s))
    }
}

package com.rioikeda.newslisten.architecture.fixture.failure

import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 例外の受け口（呼出の coroutine・所有者の内部 scope・記録 fake が積む失敗・組立の lambda）の fixture。

/** 呼出の coroutine の中で例外を投げる。 */
class ThrowingSuspendQuery {
    suspend fun load() {
        throw IllegalStateException("boom")
    }
}

/** 内部 scope の `launch` の中で、canned の尽きた `fetchFeed` の例外が未捕捉になる。 */
class ThrowingInScopeQuery(
    private val api: ApiClient,
    private val preferences: PreferencesStore,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun observe() {
        scope.launch {
            api.fetchFeed("all")
            preferences.markAchievementsSeen(setOf("a"))
        }
    }
}

/** port を呼ばずに、内部 scope の `launch` の中で直接例外を投げる。 */
class ThrowingDirectlyInScopeQuery(
    private val api: ApiClient,
    private val preferences: PreferencesStore,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun observe() {
        scope.launch {
            throw IllegalArgumentException("direct")
        }
    }
}

/** canned の尽きの例外を production が握りつぶす。 */
class SwallowingQuery(private val api: ApiClient) {
    suspend fun load() {
        try {
            api.fetchFeed("all")
        } catch (e: Exception) {
            // 握りつぶす（記録 fake が積んだ失敗は残る）
        }
    }
}

/** 正の対照: `ThrowingInScopeQuery` から書込を除いた形。canned が足りれば失敗しない。 */
class ReadingInScopeQuery(
    private val api: ApiClient,
    private val preferences: PreferencesStore,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun observe() {
        scope.launch {
            api.fetchFeed("all")
        }
    }
}

/** scenario B で、注入された例外をそのまま外へ投げる（正当な振る舞い）。 */
class InjectedFailurePropagatingQuery(private val api: ApiClient) {
    suspend fun load() {
        api.fetchFeed("all")
    }
}

/** warm-up で呼ぶ command `prime()` が canned を尽かし、`load()` は握りつぶす。 */
class PrimingSwallowingQuery(private val api: ApiClient) {
    suspend fun load() {
        try {
            api.fetchFeed("all")
        } catch (e: Exception) {
            // 握りつぶす
        }
    }

    suspend fun prime() {
        api.fetchFeed("all")
    }
}

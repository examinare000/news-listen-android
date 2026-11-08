package com.rioikeda.newslisten.preferences.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.domain.PreferenceItem
import com.rioikeda.newslisten.preferences.domain.PreferenceRejected
import com.rioikeda.newslisten.preferences.domain.PreferenceSyncResult
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [PreferencesSync] の挙動検証（android-cmd-prefs-01〜04・android-query-prefs-01）。
 *
 * サーバー往復は手書きの [PreferencesApi] fake、store は [InMemoryPreferencesStore] を包んだ fake で、
 * 送信・書込の保留や失敗を差し込めるようにする。scope は `backgroundScope`（`stateIn` の収集が `runTest` を止めないため）。
 */
class PreferencesSyncTest {

    /** 手書きの [PreferencesApi]。各操作の中身を差し替えられ、呼ばれた引数を記録する。 */
    private class FakeApi : PreferencesApi {
        var onFetch: suspend () -> ServerPreferences = { ServerPreferences("toeic_600", 1.0, 3) }
        var onUpdateDifficulty: suspend (String) -> Unit = {}
        var onUpdateSpeed: suspend (Double) -> Unit = {}
        var onUpdateGoal: suspend (Int) -> Unit = {}

        val difficultyCalls = mutableListOf<String>()
        val speedCalls = mutableListOf<Double>()
        val goalCalls = mutableListOf<Int>()
        var fetchCalls = 0

        val updateCallCount: Int get() = difficultyCalls.size + speedCalls.size + goalCalls.size

        override suspend fun fetchPreferences(): ServerPreferences {
            fetchCalls++
            return onFetch()
        }

        override suspend fun updateDefaultDifficulty(code: String) {
            difficultyCalls += code
            onUpdateDifficulty(code)
        }

        override suspend fun updateDefaultPlaybackSpeed(speed: Double) {
            speedCalls += speed
            onUpdateSpeed(speed)
        }

        override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {
            goalCalls += episodes
            onUpdateGoal(episodes)
        }
    }

    /** 書込の直前に保留・失敗を差し込める store。読みと端末設定は [InMemoryPreferencesStore] のまま。 */
    private class ProbeStore(val inner: InMemoryPreferencesStore = InMemoryPreferencesStore()) : PreferencesStore by inner {
        var writeFailure: IOException? = null
        var beforeDifficultyWrite: suspend () -> Unit = {}

        override suspend fun setDefaultDifficulty(difficulty: Difficulty) {
            beforeDifficultyWrite()
            writeFailure?.let { throw it }
            inner.setDefaultDifficulty(difficulty)
        }

        override suspend fun setDefaultPlaybackSpeed(speed: Double) {
            writeFailure?.let { throw it }
            inner.setDefaultPlaybackSpeed(speed)
        }

        override suspend fun setWeeklyGoalEpisodes(episodes: Int) {
            writeFailure?.let { throw it }
            inner.setWeeklyGoalEpisodes(episodes)
        }
    }

    /** 主体を可変にした [PreferencesSync] の組。 */
    private class Rig(val sync: PreferencesSync, val api: FakeApi, val store: ProbeStore) {
        var subject: String? = "u-1"
    }

    private fun TestScope.newRig(
        store: ProbeStore = ProbeStore(),
        api: FakeApi = FakeApi(),
    ): Rig {
        lateinit var rig: Rig
        val sync = PreferencesSync(api, store, { rig.subject }, backgroundScope)
        rig = Rig(sync, api, store)
        return rig
    }

    /** 3 つの主体依存の値を、既定値と違う有効な値にした store。 */
    private fun nonDefaultStore() = ProbeStore(
        InMemoryPreferencesStore(
            initialDefaultDifficulty = Difficulty.IELTS_7,
            initialDefaultPlaybackSpeed = PlaybackSpeed.of(2.0)!!,
            initialWeeklyGoalEpisodes = WeeklyGoal.of(7)!!,
        ),
    )

    private fun assertStoreIsNonDefault(store: PreferencesStore) {
        assertEquals(Difficulty.IELTS_7, store.defaultDifficulty.value)
        assertEquals(2.0, store.defaultPlaybackSpeed.value.multiplier, 0.0)
        assertEquals(7, store.weeklyGoalEpisodes.value.episodes)
    }

    private fun assertStoreIsDefault(store: PreferencesStore) {
        assertEquals(Difficulty.DEFAULT, store.defaultDifficulty.value)
        assertEquals(PlaybackSpeed.DEFAULT, store.defaultPlaybackSpeed.value)
        assertEquals(WeeklyGoal.DEFAULT, store.weeklyGoalEpisodes.value)
    }

    /** 10 variant（android-dec-72）。各 variant 名を字句で書く。 */
    private fun allApiExceptions(): List<ApiException> = listOf(
        ApiException.Unauthorized(),
        ApiException.RateLimited(retryAfterSeconds = 30),
        ApiException.InvalidInput("bad"),
        ApiException.UnprocessableInput("unprocessable"),
        ApiException.NotFound("missing"),
        ApiException.Conflict("conflict"),
        ApiException.ServerError(503, "down"),
        ApiException.HttpError(403, "forbidden"),
        ApiException.DecodingError(RuntimeException("bad json")),
        ApiException.NetworkError(IOException("offline")),
    )

    // --- 3 command: 検査 → 送信 → 成功時だけ保存（android-cmd-prefs-01） ---

    @Test
    fun `android-cmd-prefs-01 setDefaultDifficultyは有効な難易度を送り成功したらstoreへ書いてAppliedを返す`() = runTest {
        // verifies: android-cmd-prefs-01
        val rig = newRig()

        val result = rig.sync.setDefaultDifficulty("toeic_900")

        assertEquals(PreferenceSyncResult.Applied, result)
        assertEquals(listOf("toeic_900"), rig.api.difficultyCalls)
        assertEquals(Difficulty.TOEIC_900, rig.store.defaultDifficulty.value)
    }

    @Test
    fun `android-cmd-prefs-01 setDefaultPlaybackSpeedは有効な速度を送り成功したらstoreへ書いてAppliedを返す`() = runTest {
        // verifies: android-cmd-prefs-01
        val rig = newRig()

        val result = rig.sync.setDefaultPlaybackSpeed(1.5)

        assertEquals(PreferenceSyncResult.Applied, result)
        assertEquals(listOf(1.5), rig.api.speedCalls)
        assertEquals(1.5, rig.store.defaultPlaybackSpeed.value.multiplier, 0.0)
    }

    @Test
    fun `android-cmd-prefs-01 setWeeklyGoalは有効な週の目標を送り成功したらstoreへ書いてAppliedを返す`() = runTest {
        // verifies: android-cmd-prefs-01
        val rig = newRig()

        val result = rig.sync.setWeeklyGoal(7)

        assertEquals(PreferenceSyncResult.Applied, result)
        assertEquals(listOf(7), rig.api.goalCalls)
        assertEquals(7, rig.store.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `android-cmd-prefs-01 値域外の入力はRejectedを返しAPIを呼ばずstoreを変えない`() = runTest {
        // verifies: android-cmd-prefs-01
        val rig = newRig(store = nonDefaultStore())

        val difficulty = rig.sync.setDefaultDifficulty("toeic_800")
        val speed = rig.sync.setDefaultPlaybackSpeed(3.0)
        val nanSpeed = rig.sync.setDefaultPlaybackSpeed(Double.NaN)
        val goal = rig.sync.setWeeklyGoal(4)

        assertEquals(PreferenceSyncResult.Rejected(PreferenceRejected(PreferenceItem.DEFAULT_DIFFICULTY)), difficulty)
        assertEquals(PreferenceSyncResult.Rejected(PreferenceRejected(PreferenceItem.DEFAULT_PLAYBACK_SPEED)), speed)
        assertEquals(PreferenceSyncResult.Rejected(PreferenceRejected(PreferenceItem.DEFAULT_PLAYBACK_SPEED)), nanSpeed)
        assertEquals(PreferenceSyncResult.Rejected(PreferenceRejected(PreferenceItem.WEEKLY_GOAL_EPISODES)), goal)
        assertEquals(0, rig.api.updateCallCount)
        assertStoreIsNonDefault(rig.store)
    }

    @Test
    fun `android-rule-prefs-05 拒否された入力は連番を取らず先行する有効な送信をSupersededにしない`() = runTest {
        // verifies: android-rule-prefs-05
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { onUpdateSpeed = { gate.await() } }
        val rig = newRig(api = api)
        val first = async { rig.sync.setDefaultPlaybackSpeed(1.5) }
        runCurrent()

        val rejected = rig.sync.setDefaultPlaybackSpeed(3.0)
        gate.complete(Unit)

        assertTrue(rejected is PreferenceSyncResult.Rejected)
        assertEquals(PreferenceSyncResult.Applied, first.await())
        assertEquals(1.5, rig.store.defaultPlaybackSpeed.value.multiplier, 0.0)
    }

    @Test
    fun `android-dec-72 ApiExceptionの10 variantは3 commandのどれでもFailedに包まれ例外が出ずstoreは変わらない`() = runTest {
        // verifies: android-dec-72 / android-cmd-prefs-01
        val thrown = allApiExceptions()
        assertEquals(10, thrown.map { it::class }.toSet().size)

        thrown.forEach { exception ->
            val api = FakeApi().apply {
                onUpdateDifficulty = { throw exception }
                onUpdateSpeed = { throw exception }
                onUpdateGoal = { throw exception }
            }
            val rig = newRig(store = nonDefaultStore(), api = api)

            val results = listOf(
                rig.sync.setDefaultDifficulty("toeic_900"),
                rig.sync.setDefaultPlaybackSpeed(1.5),
                rig.sync.setWeeklyGoal(10),
            )

            results.forEach { result ->
                assertTrue("$exception は Failed", result is PreferenceSyncResult.Failed)
                assertSame(exception, (result as PreferenceSyncResult.Failed).cause)
            }
            assertStoreIsNonDefault(rig.store)
        }
    }

    @Test
    fun `android-dec-80 storeの書込が失敗したらSaveFailedを返し例外を出さない`() = runTest {
        // verifies: android-dec-80
        val store = nonDefaultStore().apply { writeFailure = IOException("disk full") }
        val rig = newRig(store = store)

        val results = listOf(
            rig.sync.setDefaultDifficulty("toeic_900"),
            rig.sync.setDefaultPlaybackSpeed(1.5),
            rig.sync.setWeeklyGoal(10),
        )

        results.forEach { assertEquals(PreferenceSyncResult.SaveFailed, it) }
        assertStoreIsNonDefault(store)
    }

    @Test
    fun `android-cmd-prefs-01 CancellationExceptionはFailedに包まず伝播する`() = runTest {
        // verifies: android-cmd-prefs-01
        val api = FakeApi().apply { onUpdateDifficulty = { throw CancellationException("cancelled") } }
        val rig = newRig(api = api)

        val thrown = runCatching { rig.sync.setDefaultDifficulty("toeic_900") }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    // --- 競合: 後から来た操作を採る（android-rule-prefs-05） ---

    @Test
    fun `android-rule-prefs-05 古い送信が後から成功で届いても書かずSupersededを返し最新の値が残る`() = runTest {
        // verifies: android-rule-prefs-05
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = FakeApi().apply {
            onUpdateGoal = { if (calls++ == 0) gate.await() }
        }
        val rig = newRig(api = api)
        val first = async { rig.sync.setWeeklyGoal(5) }
        runCurrent()
        val second = async { rig.sync.setWeeklyGoal(7) }
        runCurrent()
        assertEquals(PreferenceSyncResult.Applied, second.await())

        gate.complete(Unit)

        assertEquals(PreferenceSyncResult.Superseded, first.await())
        assertEquals(7, rig.store.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `android-rule-prefs-05 古い送信が後から失敗で届いてもFailedにせずSupersededを返し最新の値が残る`() = runTest {
        // verifies: android-rule-prefs-05
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = FakeApi().apply {
            onUpdateGoal = {
                if (calls++ == 0) {
                    gate.await()
                    throw ApiException.HttpError(500, "stale failure")
                }
            }
        }
        val rig = newRig(api = api)
        val first = async { rig.sync.setWeeklyGoal(5) }
        runCurrent()
        val second = async { rig.sync.setWeeklyGoal(7) }
        runCurrent()
        assertEquals(PreferenceSyncResult.Applied, second.await())

        gate.complete(Unit)

        assertEquals(PreferenceSyncResult.Superseded, first.await())
        assertEquals(7, rig.store.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `android-rule-prefs-05 連番は3 commandで共有され別項目の後発も先発をSupersededにする`() = runTest {
        // verifies: android-rule-prefs-05
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { onUpdateDifficulty = { gate.await() } }
        val rig = newRig(api = api)
        val first = async { rig.sync.setDefaultDifficulty("toeic_900") }
        runCurrent()
        assertEquals(PreferenceSyncResult.Applied, rig.sync.setWeeklyGoal(10))

        gate.complete(Unit)

        assertEquals(PreferenceSyncResult.Superseded, first.await())
        assertEquals(Difficulty.DEFAULT, rig.store.defaultDifficulty.value)
    }

    // --- 取り込み（android-cmd-prefs-03） ---

    private fun TestScope.restoredRig(server: ServerPreferences, store: ProbeStore = ProbeStore()): Rig =
        newRig(store = store, api = FakeApi().apply { onFetch = { server } })

    @Test
    fun 認証成功後preferences取得成功でStateFlowへ反映される() = runTest {
        // verifies: android-cmd-prefs-03 / android-query-prefs-01
        val rig = restoredRig(ServerPreferences("ielts_7", 1.5, null))

        rig.sync.syncFromServer()
        runCurrent()

        assertEquals(Difficulty.IELTS_7, rig.sync.preferences.value.defaultDifficulty)
        assertEquals(1.5, rig.sync.preferences.value.defaultPlaybackSpeed.multiplier, 0.0)
        assertFalse(rig.sync.preferencesSyncFailed.value)
    }

    @Test
    fun 認証成功後preferences取得成功で注入したPreferencesStore自体が更新される() = runTest {
        // verifies: android-cmd-prefs-03
        val rig = restoredRig(ServerPreferences("ielts_7", 1.5, null))

        rig.sync.syncFromServer()

        assertEquals(Difficulty.IELTS_7, rig.store.defaultDifficulty.value)
        assertEquals(1.5, rig.store.defaultPlaybackSpeed.value.multiplier, 0.0)
    }

    @Test
    fun 認証成功後preferences取得失敗でも既存値を保持しフラグを立てる() = runTest {
        // verifies: android-cmd-prefs-03
        val store = nonDefaultStore()
        val api = FakeApi().apply { onFetch = { throw ApiException.NetworkError(RuntimeException("offline")) } }
        val rig = newRig(store = store, api = api)

        rig.sync.syncFromServer()

        assertStoreIsNonDefault(store)
        assertTrue(rig.sync.preferencesSyncFailed.value)
    }

    @Test
    fun `android-dec-78 取得がIOExceptionで失敗しても例外を出さずフラグを立てstoreは変わらない`() = runTest {
        // verifies: android-dec-78
        val store = nonDefaultStore()
        val api = FakeApi().apply { onFetch = { throw IOException("socket closed") } }
        val rig = newRig(store = store, api = api)

        rig.sync.syncFromServer()

        assertTrue(rig.sync.preferencesSyncFailed.value)
        assertStoreIsNonDefault(store)
    }

    @Test
    fun `android-cmd-prefs-03 ApiExceptionの10 variantはどれも例外を出さずフラグを立てる`() = runTest {
        // verifies: android-cmd-prefs-03
        allApiExceptions().forEach { exception ->
            val api = FakeApi().apply { onFetch = { throw exception } }
            val rig = newRig(store = nonDefaultStore(), api = api)

            rig.sync.syncFromServer()

            assertTrue("$exception でフラグが立つ", rig.sync.preferencesSyncFailed.value)
            assertStoreIsNonDefault(rig.store)
        }
    }

    @Test
    fun `android-cmd-prefs-03 範囲外のサーバー値は難易度だけ既定値になり速度と週の目標は直前の値を保つ`() = runTest {
        // verifies: android-cmd-prefs-03
        val rig = restoredRig(ServerPreferences("toeic_800", 3.0, 9), store = nonDefaultStore())

        rig.sync.syncFromServer()

        assertEquals(Difficulty.DEFAULT, rig.store.defaultDifficulty.value)
        assertEquals(2.0, rig.store.defaultPlaybackSpeed.value.multiplier, 0.0)
        assertEquals(7, rig.store.weeklyGoalEpisodes.value.episodes)
        assertFalse(rig.sync.preferencesSyncFailed.value)
    }

    @Test
    fun `android-cmd-prefs-03 サーバーの有効な週の目標はstoreへ反映される`() = runTest {
        // verifies: android-cmd-prefs-03
        val rig = restoredRig(ServerPreferences("toeic_600", 1.0, 7))

        rig.sync.syncFromServer()

        assertEquals(7, rig.store.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `android-cmd-prefs-03 取り込みに成功すると先の失敗の印がfalseに戻る`() = runTest {
        // verifies: android-cmd-prefs-03
        var fail = true
        val api = FakeApi().apply {
            onFetch = { if (fail) throw ApiException.HttpError(500) else ServerPreferences("toeic_900", 1.0, 3) }
        }
        val rig = newRig(api = api)
        rig.sync.syncFromServer()
        assertTrue(rig.sync.preferencesSyncFailed.value)
        fail = false

        rig.sync.syncFromServer()

        assertFalse(rig.sync.preferencesSyncFailed.value)
        assertEquals(Difficulty.TOEIC_900, rig.store.defaultDifficulty.value)
    }

    @Test
    fun `android-dec-80 取り込みのstore書込がIOExceptionで失敗しても例外を出さずフラグを立てる`() = runTest {
        // verifies: android-dec-80 / android-cmd-prefs-03
        val store = ProbeStore().apply { writeFailure = IOException("disk full") }
        val rig = restoredRig(ServerPreferences("ielts_7", 1.5, 7), store = store)

        rig.sync.syncFromServer()

        assertTrue(rig.sync.preferencesSyncFailed.value)
    }

    @Test
    fun `android-cmd-prefs-03 取得の失敗の間に主体が変わっていたら失敗の印を立てない`() = runTest {
        // verifies: android-cmd-prefs-03
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply {
            onFetch = {
                gate.await()
                throw ApiException.HttpError(500)
            }
        }
        val rig = newRig(api = api)
        val sync = async { rig.sync.syncFromServer() }
        runCurrent()
        rig.subject = "u-2"

        gate.complete(Unit)
        sync.await()

        assertFalse(rig.sync.preferencesSyncFailed.value)
    }

    // --- 主体の離脱と書込の競合（android-dec-56 / android-dec-78） ---

    @Test
    fun `android-dec-56 後始末の後に届いた旧主体の同期と設定変更の結果はstoreに書かれない`() = runTest {
        // verifies: android-dec-56 / android-dec-78
        // 場面 1: 取り込み。サーバー値は既定値と違う。保留中に主体が離脱し、後始末の後に応答が届く。
        val fetchGate = CompletableDeferred<Unit>()
        val api = FakeApi().apply {
            onFetch = {
                fetchGate.await()
                ServerPreferences("ielts_7", 1.5, 7)
            }
        }
        val rig = newRig(api = api)
        rig.subject = "u-1"
        val sync = async { rig.sync.syncFromServer() }
        runCurrent()
        rig.subject = "u-2"
        rig.sync.clearSubjectScoped()

        fetchGate.complete(Unit)
        sync.await()

        assertStoreIsDefault(rig.store)
        assertFalse(rig.sync.preferencesSyncFailed.value)

        // 場面 2〜4: 3 command。送信を保留し、保留中に主体が離脱し、後始末の後に成功で届く。
        val difficultyGate = CompletableDeferred<Unit>()
        val speedGate = CompletableDeferred<Unit>()
        val goalGate = CompletableDeferred<Unit>()
        api.onUpdateDifficulty = { difficultyGate.await() }
        api.onUpdateSpeed = { speedGate.await() }
        api.onUpdateGoal = { goalGate.await() }

        rig.subject = "u-1"
        val difficulty = async { rig.sync.setDefaultDifficulty("ielts_7") }
        runCurrent()
        rig.subject = "u-2"
        rig.sync.clearSubjectScoped()
        difficultyGate.complete(Unit)
        assertEquals(PreferenceSyncResult.Superseded, difficulty.await())
        assertStoreIsDefault(rig.store)

        rig.subject = "u-1"
        val speed = async { rig.sync.setDefaultPlaybackSpeed(1.5) }
        runCurrent()
        rig.subject = "u-2"
        rig.sync.clearSubjectScoped()
        speedGate.complete(Unit)
        assertEquals(PreferenceSyncResult.Superseded, speed.await())
        assertStoreIsDefault(rig.store)

        rig.subject = "u-1"
        val goal = async { rig.sync.setWeeklyGoal(7) }
        runCurrent()
        rig.subject = "u-2"
        rig.sync.clearSubjectScoped()
        goalGate.complete(Unit)
        assertEquals(PreferenceSyncResult.Superseded, goal.await())
        assertStoreIsDefault(rig.store)
    }

    @Test
    fun `android-dec-56 書込の途中で始まった後始末は書込の完了を待ち最終のstoreは既定値になる`() = runTest {
        // verifies: android-dec-56 / android-dec-78
        val writeGate = CompletableDeferred<Unit>()
        val store = ProbeStore().apply { beforeDifficultyWrite = { writeGate.await() } }
        val api = FakeApi().apply { onFetch = { ServerPreferences("ielts_7", 1.5, 7) } }
        val rig = newRig(store = store, api = api)
        val sync = async { rig.sync.syncFromServer() }
        runCurrent()
        val cleanup = async { rig.sync.clearSubjectScoped() }
        runCurrent()

        writeGate.complete(Unit)
        sync.await()
        cleanup.await()

        assertStoreIsDefault(store)
    }

    @Test
    fun `android-cmd-prefs-04 clearSubjectScopedは主体依存の4項目を既定値へ戻し端末設定の4項目を残す`() = runTest {
        // verifies: android-cmd-prefs-04 / shared-dec-02
        val store = ProbeStore(
            InMemoryPreferencesStore(
                initialDefaultDifficulty = Difficulty.IELTS_7,
                initialDefaultPlaybackSpeed = PlaybackSpeed.of(2.0)!!,
                initialArticleOpenMode = ArticleOpenMode.EXTERNAL,
                initialTimeFormat = TimeFormat.RELATIVE,
                initialSfxEnabled = false,
                initialHapticsEnabled = false,
                initialWeeklyGoalEpisodes = WeeklyGoal.of(10)!!,
                initialSeenAchievementIds = setOf("streak_7"),
            ),
        )
        val rig = newRig(store = store)

        rig.sync.clearSubjectScoped()
        rig.sync.clearSubjectScoped()

        assertStoreIsDefault(store)
        assertEquals(emptySet<String>(), store.seenAchievementIds.value)
        assertEquals(ArticleOpenMode.EXTERNAL, store.articleOpenMode.value)
        assertEquals(TimeFormat.RELATIVE, store.timeFormat.value)
        assertFalse(store.sfxEnabled.value)
        assertFalse(store.hapticsEnabled.value)
    }

    // --- 端末だけの 4 command（android-cmd-prefs-02） ---

    @Test
    fun `android-cmd-prefs-02 端末だけの4つの設定はstoreへ書くだけでサーバーへ送らない`() = runTest {
        // verifies: android-cmd-prefs-02
        val rig = newRig()

        rig.sync.setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        rig.sync.setTimeFormat(TimeFormat.RELATIVE)
        rig.sync.setSfxEnabled(false)
        rig.sync.setHapticsEnabled(false)

        assertEquals(ArticleOpenMode.EXTERNAL, rig.store.articleOpenMode.value)
        assertEquals(TimeFormat.RELATIVE, rig.store.timeFormat.value)
        assertFalse(rig.store.sfxEnabled.value)
        assertFalse(rig.store.hapticsEnabled.value)
        assertEquals(0, rig.api.updateCallCount)
        assertEquals(0, rig.api.fetchCalls)
    }

    // --- query（android-query-prefs-01） ---

    @Test
    fun `android-query-prefs-01 preferencesは構築時のstoreの値から始まりstoreの変更に追従する`() = runTest {
        // verifies: android-query-prefs-01
        val rig = newRig(store = nonDefaultStore())
        assertEquals(Difficulty.IELTS_7, rig.sync.preferences.value.defaultDifficulty)
        assertEquals(2.0, rig.sync.preferences.value.defaultPlaybackSpeed.multiplier, 0.0)
        assertEquals(7, rig.sync.preferences.value.weeklyGoal.episodes)

        rig.store.setDefaultDifficulty(Difficulty.EIKEN_2)
        rig.store.setDefaultPlaybackSpeed(0.75)
        rig.store.setWeeklyGoalEpisodes(10)
        rig.store.setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        rig.store.setTimeFormat(TimeFormat.RELATIVE)
        rig.store.setSfxEnabled(false)
        rig.store.setHapticsEnabled(false)
        rig.store.markAchievementsSeen(setOf("streak_7"))
        runCurrent()

        val view = rig.sync.preferences.value
        assertEquals(Difficulty.EIKEN_2, view.defaultDifficulty)
        assertEquals(0.75, view.defaultPlaybackSpeed.multiplier, 0.0)
        assertEquals(10, view.weeklyGoal.episodes)
        assertEquals(ArticleOpenMode.EXTERNAL, view.articleOpenMode)
        assertEquals(TimeFormat.RELATIVE, view.timeFormat)
        assertFalse(view.sfxEnabled)
        assertFalse(view.hapticsEnabled)
        assertEquals(setOf("streak_7"), view.seenAchievementIds)
    }

    @Test
    fun `android-query-prefs-01 preferencesは選択肢を型の正本から持ち同期失敗の印は初期値falseである`() = runTest {
        // verifies: android-query-prefs-01
        val rig = newRig()

        val view = rig.sync.preferences.value

        assertEquals(Difficulty.entries.toList(), view.difficultyOptions)
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5), view.speedOptions.map { it.multiplier })
        assertEquals(listOf(3, 5, 7, 10), view.weeklyGoalOptions.map { it.episodes })
        assertFalse(rig.sync.preferencesSyncFailed.value)
    }
}

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.model.StarRequest
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 記録 fake と hook の工場。書込は引数の値に依らず 1 回 1 件、読みは積まない。 */
class RecordingPortsTest {

    private val feed = FeedResponse(articles = emptyList(), date = "2026-10-14")

    // ---- 書込事象・読み・hook の工場 ----

    @Test
    fun 書込と全hookの形は1回1件で積まれ読みは積まれず関数は同一性の表に登録される() {
        val env = ProbeEnvironment.create(scenarioA(canned = mapOf("ApiClient#fetchFeed" to listOf(feed))))
        assertEquals("recorder は段階 組立 で作る", ProbeStage.ASSEMBLY, env.recorder.stage)
        val hooks = env.hooks

        fun now(): Long = env.scheduler.currentTime

        // 同じ値の書込を 2 回。1 回目は印なし、2 回目は印を付けた coroutine の中。
        runBlocking { env.preferencesStore.markAchievementsSeen(setOf("a")) }
        val firstAt = now()
        env.scheduler.advanceTimeBy(1_000)
        runBlocking(env.marker) { env.preferencesStore.markAchievementsSeen(setOf("a")) }
        val secondAt = now()
        env.scheduler.advanceTimeBy(1_000)

        val fetched = runBlocking { env.apiClient.fetchFeed("all") }
        assertEquals("列の読みは canned の値を返す", feed, fetched)

        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { env.apiClient.fetchPodcasts() } }
        assertTrue("尽きの例外が操作名を含まない: ${failure.message}", failure.message.orEmpty().contains("fetchPodcasts"))

        val write = hooks.write("Owner#w")
        val suspendWrite = hooks.suspendWrite("Owner#sw")
        val write1 = hooks.write1<Int>("Owner#w1")
        val suspendWrite1 = hooks.suspendWrite1<Int>("Owner#sw1")
        val hookAt = now()
        write()
        runBlocking { suspendWrite() }
        write1(1)
        runBlocking { suspendWrite1(1) }
        write1(2)
        runBlocking { suspendWrite1(2) }

        val read = hooks.read("Owner#r") { 10 }
        val suspendRead = hooks.suspendRead("Owner#sr") { 20 }
        val read1 = hooks.read1<Int, Int>("Owner#r1") { it + 1 }
        val suspendRead1 = hooks.suspendRead1<Int, Int>("Owner#sr1") { it + 2 }
        assertEquals(10, read())
        assertEquals(20, runBlocking { suspendRead() })
        assertEquals(6, read1(5))
        assertEquals(7, runBlocking { suspendRead1(5) })

        val writes = env.recorder.writes
        assertEquals("書込事象は 8 件（同値 2 件と hook の 6 件）: $writes", 8, writes.size)
        assertEquals(
            listOf("PreferencesStore", "PreferencesStore", "hook", "hook", "hook", "hook", "hook", "hook"),
            writes.map { it.port },
        )
        assertEquals(
            listOf("markAchievementsSeen", "markAchievementsSeen", "Owner#w", "Owner#sw", "Owner#w1", "Owner#sw1", "Owner#w1", "Owner#sw1"),
            writes.map { it.op },
        )
        assertEquals(
            "印を立てた coroutine の中の 1 件だけが DIRECT",
            listOf(Origin.BACKGROUND, Origin.DIRECT) + List(6) { Origin.BACKGROUND },
            writes.map { it.origin },
        )
        assertEquals("at は呼んだ時点の scheduler の時刻", listOf(firstAt, secondAt) + List(6) { hookAt }, writes.map { it.at })
        assertTrue("時刻が動いていない（at の照合が空振り）", firstAt != secondAt && secondAt != hookAt)

        assertEquals(HookRegistration("Owner#w", HookKind.WRITE, HookShape.NO_ARG), hooks.registrationOf(write))
        assertEquals(HookRegistration("Owner#sw", HookKind.WRITE, HookShape.SUSPEND_NO_ARG), hooks.registrationOf(suspendWrite))
        assertEquals(HookRegistration("Owner#w1", HookKind.WRITE, HookShape.ONE_ARG), hooks.registrationOf(write1))
        assertEquals(HookRegistration("Owner#sw1", HookKind.WRITE, HookShape.SUSPEND_ONE_ARG), hooks.registrationOf(suspendWrite1))
        assertEquals(HookRegistration("Owner#r", HookKind.READ, HookShape.NO_ARG), hooks.registrationOf(read))
        assertEquals(HookRegistration("Owner#sr", HookKind.READ, HookShape.SUSPEND_NO_ARG), hooks.registrationOf(suspendRead))
        assertEquals(HookRegistration("Owner#r1", HookKind.READ, HookShape.ONE_ARG), hooks.registrationOf(read1))
        assertEquals(HookRegistration("Owner#sr1", HookKind.READ, HookShape.SUSPEND_ONE_ARG), hooks.registrationOf(suspendRead1))
        assertEquals("工場の外の関数は登録されていない", null, hooks.registrationOf({}))

        val failures = env.recorder.failures
        assertEquals("canned の尽きだけが ProbeFailure: $failures", 1, failures.size)
        assertTrue(failures.single().cause.contains("fetchPodcasts"))
    }

    @Test
    fun 列の読みは呼出ごとにcannedを1つ進め尽きると失敗を積んでから投げる() {
        val second = FeedResponse(articles = emptyList(), date = "2026-10-15")
        val env = ProbeEnvironment.create(scenarioA(canned = mapOf("ApiClient#fetchFeed" to listOf(feed, second))))

        assertEquals(feed, runBlocking { env.apiClient.fetchFeed("all") })
        assertEquals(second, runBlocking { env.apiClient.fetchFeed("all") })
        assertTrue("尽きる前は失敗を積まない", env.recorder.failures.isEmpty())

        assertThrows(IllegalStateException::class.java) { runBlocking { env.apiClient.fetchFeed("all") } }
        assertEquals(1, env.recorder.failures.size)
        assertEquals("読みは書込事象を積まない", 0, env.recorder.writes.size)
    }

    @Test
    fun ProbeFailureの段はrecorderの段階と印から決まる() {
        fun stageOf(stage: ProbeStage, marked: Boolean): FailureStage {
            val env = ProbeEnvironment.create(scenarioA())
            env.recorder.stage = stage
            assertThrows(IllegalStateException::class.java) {
                if (marked) runBlocking(env.marker) { env.apiClient.fetchFeed("all") } else runBlocking { env.apiClient.fetchFeed("all") }
            }
            return env.recorder.failures.single().stage
        }

        assertEquals("観測の段階で印あり", FailureStage.CALL, stageOf(ProbeStage.OBSERVE, marked = true))
        assertEquals("観測の段階で印なし", FailureStage.BACKGROUND, stageOf(ProbeStage.OBSERVE, marked = false))
        assertEquals(FailureStage.WARM_UP, stageOf(ProbeStage.WARM_UP, marked = false))
        assertEquals(FailureStage.ASSEMBLY, stageOf(ProbeStage.ASSEMBLY, marked = false))
        assertEquals(FailureStage.CLEANUP, stageOf(ProbeStage.CLEANUP, marked = false))
    }

    @Test
    fun 別の実行単位のhooksは自分のrecorderにだけ積む() {
        val first = ProbeEnvironment.create(scenarioA())
        val second = ProbeEnvironment.create(scenarioA())

        first.hooks.write("Owner#w")()

        assertEquals(1, first.recorder.writes.size)
        assertEquals("別の実行単位の recorder に積まれた", 0, second.recorder.writes.size)
        assertNotSame(first.recorder, second.recorder)
        assertNotSame(first.scheduler, second.scheduler)
        assertNotSame(first.marker, second.marker)
    }

    // ---- 書込の戻り値と property の読み ----

    private data class Exercised(
        val env: ProbeEnvironment,
        val updated: List<Any?>,
        val starred: List<Any?>,
        val completed: List<Any?>,
        val saved: List<Boolean>,
        val deleted: List<Boolean>,
        val credentials: List<String>,
        val sfx: List<Any>,
        val positions: List<Any>,
        val online: List<Any>,
        val onPlaybackCompleted: Any?,
        val callback: () -> Unit,
    )

    /** 書込 6 操作を 2 回ずつ → callback の代入と seekTo → property の読み、の順に呼ぶ。 */
    private fun exercise(scenario: ProbeScenario): Exercised {
        val env = ProbeEnvironment.create(scenario)
        val callback: () -> Unit = {}
        val updated = mutableListOf<Any?>()
        val starred = mutableListOf<Any?>()
        val completed = mutableListOf<Any?>()
        val saved = mutableListOf<Boolean>()
        val deleted = mutableListOf<Boolean>()
        val credentials = mutableListOf<String>()
        repeat(2) { runBlocking { updated += env.apiClient.updatePlaybackPosition("p", 1.0) } }
        repeat(2) { runBlocking { starred += env.apiClient.starArticle("a", StarRequest()) } }
        repeat(2) { runBlocking { completed += env.apiClient.markCompleted("p") } }
        repeat(2) { saved += env.sessionStore.save("t") }
        repeat(2) { deleted += env.fileSystem.delete("x") }
        repeat(2) { runBlocking { credentials += env.passkeyProvider.getCredential("{}") } }
        env.playerController.onPlaybackCompleted = callback
        env.playerController.seekTo(30.0)
        val sfx = List(2) { env.preferencesStore.sfxEnabled }
        val positions = List(3) { env.playerController.positionSeconds }
        val online = List(2) { env.networkMonitor.isOnline }
        return Exercised(
            env, updated, starred, completed, saved, deleted, credentials, sfx, positions, online,
            env.playerController.onPlaybackCompleted, callback,
        )
    }

    private fun assertWriteContract(r: Exercised) {
        val writes = r.env.recorder.writes
        assertEquals("書込事象は 14 件（書込 6 操作 × 2 回 ＋ callback の代入 1 ＋ seekTo 1）: $writes", 14, writes.size)
        assertTrue("印を立てていないのですべて BACKGROUND", writes.all { it.origin == Origin.BACKGROUND })
        assertEquals(
            listOf(
                "ApiClient" to 6, "SessionStore" to 2, "FileSystem" to 2, "PasskeyProvider" to 2, "PlayerController" to 2,
            ),
            writes.groupBy { it.port }.map { it.key to it.value.size },
        )

        assertEquals(List(2) { RecordingDefaults.podcastResponse }, r.updated)
        assertEquals(List(2) { RecordingDefaults.actionResponse }, r.starred)
        assertEquals(List(2) { Unit }, r.completed)
        assertEquals(listOf(true, true), r.saved)
        assertEquals(listOf(true, true), r.deleted)
        assertEquals(listOf("{}", "{}"), r.credentials)

        r.sfx.forEach { assertSame("getter を繰り返し呼んでも同じオブジェクト", r.sfx.first(), it) }
        r.positions.forEach { assertSame(r.positions.first(), it) }
        r.online.forEach { assertSame(r.online.first(), it) }
        assertEquals(RecordingDefaults.INITIAL_SFX_ENABLED, r.env.preferencesStore.sfxEnabled.value)
        assertEquals(
            "seekTo の後も property の値は変わらない（書込は property の読みを変えない）",
            RecordingDefaults.INITIAL_POSITION_SECONDS,
            r.env.playerController.positionSeconds.value,
            0.0,
        )
        assertSame("setOnPlaybackCompleted が最後に代入した値を返す", r.callback, r.onPlaybackCompleted)
        assertEquals("property の読みは書込事象を積まない", 14, r.env.recorder.writes.size)
        assertEquals("書込と property の読みは ProbeFailure を積まない", emptyList<ProbeFailure>(), r.env.recorder.failures)
    }

    @Test
    fun scenarioAの書込は固定の既定値を返し失敗せずpropertyの読みは同じオブジェクトを返す() {
        val result = exercise(scenarioA())

        assertWriteContract(result)
        assertEquals(RecordingDefaults.INITIAL_IS_ONLINE, result.env.networkMonitor.isOnline.value)
    }

    @Test
    fun scenarioBでも書込とpropertyの読みは失敗せず列の読みだけが注入した例外を投げる() {
        val scenario = scenarioB()
        val result = exercise(scenario)

        assertWriteContract(result)

        val injected = assertThrows(ApiException::class.java) { runBlocking { result.env.apiClient.fetchFeed("all") } }
        assertSame("scenario が注入したインスタンスそのもの", scenario.readFailure, injected)
        assertTrue("注入した例外は recorder に登録される", result.env.recorder.injected.any { it === injected })
        assertEquals("注入した例外は ProbeFailure にならない", emptyList<ProbeFailure>(), result.env.recorder.failures)
        assertEquals("列の読みは書込事象を積まない", 14, result.env.recorder.writes.size)
    }

    @Test
    fun propertyの読みの初期値はscenarioが与えた値になる() {
        val scenario = scenarioA(initialProperties = mapOf("NetworkMonitoring#getIsOnline" to false))
        val env = ProbeEnvironment.create(scenario)

        assertEquals(false, env.networkMonitor.isOnline.value)
        assertEquals(
            "与えていない property は既定の初期値",
            RecordingDefaults.INITIAL_SFX_ENABLED,
            env.preferencesStore.sfxEnabled.value,
        )
    }
}

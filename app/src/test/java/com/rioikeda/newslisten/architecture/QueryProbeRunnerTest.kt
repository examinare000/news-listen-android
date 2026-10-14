package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.cancel.CancelTimerAndWrite
import com.rioikeda.newslisten.architecture.fixture.cancel.RestartTimerInPhase
import com.rioikeda.newslisten.architecture.fixture.cancel.SuppressTimerAndWriteNow
import com.rioikeda.newslisten.architecture.fixture.deadline.DelayedFlowOwner
import com.rioikeda.newslisten.architecture.fixture.deadline.PeriodicWriterWithStateGetter
import com.rioikeda.newslisten.architecture.fixture.deadline.SlowSuspendQuery
import com.rioikeda.newslisten.architecture.fixture.failure.InjectedFailurePropagatingQuery
import com.rioikeda.newslisten.architecture.fixture.failure.PrimingSwallowingQuery
import com.rioikeda.newslisten.architecture.fixture.failure.ReadingInScopeQuery
import com.rioikeda.newslisten.architecture.fixture.failure.SwallowingQuery
import com.rioikeda.newslisten.architecture.fixture.failure.ThrowingDirectlyInScopeQuery
import com.rioikeda.newslisten.architecture.fixture.failure.ThrowingInScopeQuery
import com.rioikeda.newslisten.architecture.fixture.failure.ThrowingSuspendQuery
import com.rioikeda.newslisten.architecture.fixture.timer.DelayedWriter
import com.rioikeda.newslisten.architecture.fixture.timer.PeriodicWriter
import com.rioikeda.newslisten.model.FeedResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * probe の実行（runner）。control（scenario ごとに 1 つ）と treatment（`(行, signature)` ごと）を別の実行単位として、
 * 共通の期限 `t0 + 60 秒` まで仮想時刻で進める。実時間の待ちに依存しない。
 */
class QueryProbeRunnerTest {

    private val feed = FeedResponse(articles = emptyList(), date = "2026-10-14")
    private val twoFeeds = mapOf("ApiClient#fetchFeed" to listOf<Any?>(feed, feed))

    private fun write(op: String, signature: String, origin: Origin, scenario: String = "A") =
        ObservedWrite(op, signature, "ApiClient", "updatePlaybackPosition", scenario, origin)

    private fun List<ExecutionUnitTrace>.control() = single { it.role == UnitRole.CONTROL }

    private fun List<ExecutionUnitTrace>.treatments() = filter { it.role == UnitRole.TREATMENT }

    private fun scopeJobOf(owner: Any): Job? {
        val field = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }
        return (field.get(owner) as CoroutineScope).coroutineContext[Job]
    }

    private fun runFailing(set: QueryProbeSet, context: ProbeContext): ProbeRunFailure {
        val before = Thread.currentThread().uncaughtExceptionHandler
        val failure = assertThrows(ProbeRunFailure::class.java) { QueryProbeRunner.run(set, context) }
        assertSame("run の前後で thread の uncaughtExceptionHandler が同じオブジェクト", before, Thread.currentThread().uncaughtExceptionHandler)
        return failure
    }

    private fun runPassing(set: QueryProbeSet, context: ProbeContext): ProbeRunResult {
        val before = Thread.currentThread().uncaughtExceptionHandler
        val result = QueryProbeRunner.run(set, context)
        assertSame("run の前後で thread の uncaughtExceptionHandler が同じオブジェクト", before, Thread.currentThread().uncaughtExceptionHandler)
        return result
    }

    // ---- 背景の timer（位置同期 15 秒）と自前の scope ----

    private val timerWorld = FixtureWorld(
        anchor = PeriodicWriter::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.timer",
        files = listOf("TimerFixtures.kt"),
        rows = listOf(
            commandRow("PeriodicWriter#start"),
            probeQuery("PeriodicWriter#idle"),
            probeQuery("DelayedWriter#fire"),
        ),
    )

    @Test(timeout = 60_000)
    fun 背景のtimerは両方の実行単位で相殺され共通の期限で読み有限時間で終わる() {
        val set = queryProbeSet(
            owner = "PeriodicWriter",
            scenarios = listOf(scenarioA(warmUp = { owner, _ -> (owner as PeriodicWriter).start() })),
            calls = listOf(CallSpec("PeriodicWriter#idle")),
        ) { env -> PeriodicWriter(env.apiClient, env.newDispatcher()) }

        val result = runPassing(set, timerWorld.context)

        val control = result.traces.control()
        val treatment = result.traces.treatments().single()
        assertEquals("treatment の対象", "PeriodicWriter#idle" to "()", treatment.target)
        assertTrue("書込が相殺されず観測された: ${result.observed}", result.observed.isEmpty())
        assertTrue(result.backgroundChanges.isEmpty())

        listOf(control, treatment).forEach { trace ->
            assertEquals("記録を読んだ時刻は t0 + 60 秒", trace.t0 + 60_000, trace.readAt)
            assertEquals(
                "背景の timer が両方で走っている（相殺の前提）",
                listOf(15_000L, 30_000L, 45_000L, 60_000L),
                trace.env.recorder.writes.map { it.at - trace.t0 },
            )
            assertTrue(trace.env.recorder.writes.all { it.origin == Origin.BACKGROUND })
            val job = scopeJobOf(trace.graph.single { it is PeriodicWriter })
            assertFalse("runner の cancel の後に scope の Job が active", job!!.isActive)
        }
        assertEquals("control と treatment の t0 が等しい", control.t0, treatment.t0)
    }

    @Test(timeout = 60_000)
    fun 呼出ごとに窓の中の遅延の書込を数える() {
        val set = queryProbeSet(
            owner = "DelayedWriter",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("DelayedWriter#fire")),
        ) { env -> DelayedWriter(env.apiClient, env.newDispatcher()) }

        val result = runPassing(set, timerWorld.context)

        assertEquals(
            "呼出 2 回 × 1 件。control は query を呼ばないので 0 件",
            List(2) { write("DelayedWriter#fire", "()", Origin.BACKGROUND) },
            result.observed,
        )
        assertTrue(result.backgroundChanges.isEmpty())
    }

    // ---- 観測の期限 ----

    private val deadlineWorld = FixtureWorld(
        anchor = DelayedFlowOwner::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.deadline",
        files = listOf("DeadlineFixtures.kt"),
        rows = listOf(
            commandRow("PeriodicWriterWithStateGetter#start"),
            probeQuery("PeriodicWriterWithStateGetter#getState"),
            probeQuery("DelayedFlowOwner#early"),
            probeQuery("DelayedFlowOwner#late"),
            probeQuery("SlowSuspendQuery#fetch"),
        ),
    )

    @Test(timeout = 60_000)
    fun 副作用の無いgetterは周期の書込があっても違反にならず時刻が揃う() {
        val set = queryProbeSet(
            owner = "PeriodicWriterWithStateGetter",
            scenarios = listOf(scenarioA(warmUp = { owner, _ -> (owner as PeriodicWriterWithStateGetter).start() })),
        ) { env -> PeriodicWriterWithStateGetter(env.apiClient, env.newDispatcher()) }

        val result = runPassing(set, deadlineWorld.context)

        assertTrue("getter の観測が 0 のはず: ${result.observed}", result.observed.isEmpty())
        assertTrue("背景処理の変化が出た: ${result.backgroundChanges}", result.backgroundChanges.isEmpty())
        val control = result.traces.control()
        val treatment = result.traces.treatments().single()
        assertEquals("PeriodicWriterWithStateGetter#getState" to "()", treatment.target)
        assertEquals(control.t0, treatment.t0)
        assertEquals(control.t0 + 60_000, control.readAt)
        assertEquals(treatment.t0 + 60_000, treatment.readAt)
        listOf(control, treatment).forEach { trace ->
            assertEquals(
                "周期の書込は両方の実行単位で BACKGROUND の t0+15/30/45/60",
                listOf(15_000L, 30_000L, 45_000L, 60_000L),
                trace.env.recorder.writes.map { it.at - trace.t0 },
            )
            assertTrue(
                "timer の Job が期限で ACTIVE: ${trace.backgroundJobs}",
                trace.backgroundJobs.any { it.key.contains("job") && it.atDeadline == JobState.ACTIVE },
            )
        }
        assertEquals(
            "control と treatment で背景の Job の鍵の集合が一致する",
            control.backgroundJobs.map { it.key }.toSet(),
            treatment.backgroundJobs.map { it.key }.toSet(),
        )
    }

    @Test(timeout = 60_000)
    fun 期限の内の遅延は数え期限の後の遅延は数えない() {
        val set = queryProbeSet(
            owner = "DelayedFlowOwner",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("DelayedFlowOwner#early"), CallSpec("DelayedFlowOwner#late")),
        ) { env -> DelayedFlowOwner(env.apiClient) }

        val result = runPassing(set, deadlineWorld.context)

        assertEquals(
            "early は呼出 2 回 × 1 件、late は期限の後なので 0 件",
            List(2) { write("DelayedFlowOwner#early", "()", Origin.DIRECT) },
            result.observed,
        )
        assertEquals("early と late の treatment が実行された", 2, result.traces.treatments().size)
    }

    @Test(timeout = 60_000)
    fun 期限までに完了しないsuspendの呼出は操作IDとsignatureを名指しして失敗する() {
        val set = queryProbeSet(
            owner = "SlowSuspendQuery",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("SlowSuspendQuery#fetch")),
        ) { _ -> SlowSuspendQuery() }

        val failure = runFailing(set, deadlineWorld.context)

        assertFailureMessage(failure, Headings.DEADLINE_INCOMPLETE, "SlowSuspendQuery#fetch", "suspend ()")
    }

    // ---- 例外の受け口と失敗の段 ----

    private val failureWorld = FixtureWorld(
        anchor = ThrowingSuspendQuery::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.failure",
        files = listOf("FailureFixtures.kt"),
        rows = listOf(
            probeQuery("ThrowingSuspendQuery#load"),
            probeQuery("ThrowingInScopeQuery#observe"),
            probeQuery("ThrowingDirectlyInScopeQuery#observe"),
            probeQuery("SwallowingQuery#load"),
            probeQuery("ReadingInScopeQuery#observe"),
            probeQuery("InjectedFailurePropagatingQuery#load"),
            probeQuery("PrimingSwallowingQuery#load"),
            commandRow("PrimingSwallowingQuery#prime"),
        ),
    )

    private fun ProbeRunFailure.failures(): List<ProbeFailure> = trace?.failures ?: throw AssertionError("失敗した実行単位の trace が無い: $message")

    @Test(timeout = 60_000)
    fun 呼出のcoroutineの例外は段呼出の失敗になり同じ例外のインスタンスは1件にまとまる() {
        val set = queryProbeSet(
            owner = "ThrowingSuspendQuery",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("ThrowingSuspendQuery#load")),
        ) { _ -> ThrowingSuspendQuery() }

        val failure = runFailing(set, failureWorld.context)

        assertEquals(UnitRole.TREATMENT, failure.trace!!.role)
        assertEquals("呼出 2 回 × 1 件", List(2) { FailureStage.CALL }, failure.failures().map { it.stage })
        assertFailureMessage(failure, "ThrowingSuspendQuery#load", "suspend ()", "A", "treatment", FailureStage.CALL.label)
    }

    @Test(timeout = 60_000)
    fun 所有者の内部scopeで尽きた読みと未捕捉の例外は段背景の2種類の失敗になる() {
        val set = queryProbeSet(
            owner = "ThrowingInScopeQuery",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("ThrowingInScopeQuery#observe")),
        ) { env -> ThrowingInScopeQuery(env.apiClient, env.preferencesStore, env.newDispatcher()) }

        val failure = runFailing(set, failureWorld.context)

        assertEquals(UnitRole.TREATMENT, failure.trace!!.role)
        assertEquals("呼出 2 回 × (記録 fake の尽き・handler の例外)", List(4) { FailureStage.BACKGROUND }, failure.failures().map { it.stage })
        assertFailureMessage(failure, "ThrowingInScopeQuery#observe", "fetchFeed", FailureStage.BACKGROUND.label)
    }

    @Test(timeout = 60_000)
    fun portを呼ばない内部scopeの例外はthreadのhandlerで受けて段背景になる() {
        val set = queryProbeSet(
            owner = "ThrowingDirectlyInScopeQuery",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("ThrowingDirectlyInScopeQuery#observe")),
        ) { env -> ThrowingDirectlyInScopeQuery(env.apiClient, env.preferencesStore, env.newDispatcher()) }

        val failure = runFailing(set, failureWorld.context)

        assertEquals("呼出 2 回 × handler の例外 1 件", List(2) { FailureStage.BACKGROUND }, failure.failures().map { it.stage })
    }

    @Test(timeout = 60_000)
    fun productionが握りつぶした尽きも記録fakeが積んだ失敗として段呼出で残る() {
        val set = queryProbeSet(
            owner = "SwallowingQuery",
            scenarios = listOf(scenarioA()),
            calls = listOf(CallSpec("SwallowingQuery#load")),
        ) { env -> SwallowingQuery(env.apiClient) }

        val failure = runFailing(set, failureWorld.context)

        assertEquals("呼出 2 回 × 記録 fake の尽き 1 件", List(2) { FailureStage.CALL }, failure.failures().map { it.stage })
        assertFailureMessage(failure, "SwallowingQuery#load", "fetchFeed")
    }

    @Test(timeout = 60_000)
    fun cannedが足りる正の対照は書込0件でProbeFailure0のgreenになる() {
        val reading = queryProbeSet(
            owner = "ReadingInScopeQuery",
            scenarios = listOf(scenarioA(canned = twoFeeds)),
            calls = listOf(CallSpec("ReadingInScopeQuery#observe")),
        ) { env -> ReadingInScopeQuery(env.apiClient, env.preferencesStore, env.newDispatcher()) }
        val swallowing = queryProbeSet(
            owner = "SwallowingQuery",
            scenarios = listOf(scenarioA(canned = twoFeeds)),
            calls = listOf(CallSpec("SwallowingQuery#load")),
        ) { env -> SwallowingQuery(env.apiClient) }

        listOf(reading, swallowing).forEach { set ->
            val result = runPassing(set, failureWorld.context)

            assertTrue("書込 0 件のはず: ${result.observed}", result.observed.isEmpty())
            assertTrue(result.backgroundChanges.isEmpty())
            assertEquals(
                "control と treatment の両方が実行された",
                listOf(UnitRole.CONTROL, UnitRole.TREATMENT),
                result.traces.map { it.role },
            )
            assertTrue(result.traces.all { it.failures.isEmpty() })
        }
    }

    @Test(timeout = 60_000)
    fun scenarioBで注入した例外をそのまま外へ投げる呼出は失敗にせずtraceに残す() {
        val set = queryProbeSet(
            owner = "InjectedFailurePropagatingQuery",
            scenarios = listOf(scenarioB()),
            calls = listOf(CallSpec("InjectedFailurePropagatingQuery#load")),
        ) { env -> InjectedFailurePropagatingQuery(env.apiClient) }

        val result = runPassing(set, failureWorld.context)

        val treatment = result.traces.treatments().single()
        assertEquals(
            "2 回とも注入した失敗で終了",
            List(2) { CallCompletion.INJECTED_FAILURE },
            treatment.calls.map { it.completion },
        )
        assertTrue(treatment.failures.isEmpty())
    }

    @Test(timeout = 60_000)
    fun warmupの失敗は観測へ進まずcontrolで段warmupとして失敗する() {
        val set = queryProbeSet(
            owner = "PrimingSwallowingQuery",
            scenarios = listOf(scenarioA(warmUp = { owner, _ -> (owner as PrimingSwallowingQuery).prime() })),
            calls = listOf(CallSpec("PrimingSwallowingQuery#load")),
        ) { env -> PrimingSwallowingQuery(env.apiClient) }

        val failure = runFailing(set, failureWorld.context)

        val trace = failure.trace!!
        assertEquals("実行単位の順は control が先", UnitRole.CONTROL, trace.role)
        assertEquals("記録 fake の尽きと warm-up の coroutine の例外", List(2) { FailureStage.WARM_UP }, failure.failures().map { it.stage })
        assertTrue("warm-up の失敗の後は観測の手順を飛ばす", trace.aborted)
        assertFailureMessage(failure, "control", "A", "fetchFeed", FailureStage.WARM_UP.label)
    }

    @Test(timeout = 60_000)
    fun 組立のlambdaの同期の例外は段組立で失敗しグラフは空になる() {
        val set = queryProbeSet(
            owner = "SwallowingQuery",
            scenarios = listOf(scenarioA(canned = twoFeeds)),
            calls = listOf(CallSpec("SwallowingQuery#load")),
        ) { env ->
            SwallowingQuery(env.apiClient)
            throw IllegalStateException("assemble failed")
        }

        val failure = runFailing(set, failureWorld.context)

        val trace = failure.trace!!
        assertEquals(UnitRole.CONTROL, trace.role)
        assertEquals(listOf(FailureStage.ASSEMBLY), failure.failures().map { it.stage })
        assertTrue("組立が失敗した実行単位のグラフは空", trace.graph.isEmpty())
        assertTrue(trace.aborted)
        assertFailureMessage(failure, FailureStage.ASSEMBLY.label)
    }

    // ---- 相殺（背景の書込を止めて同じ件数を書く） ----

    private val cancelWorld = FixtureWorld(
        anchor = CancelTimerAndWrite::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.cancel",
        files = listOf("CancelFixtures.kt"),
        rows = listOf(
            commandRow("CancelTimerAndWrite#start"),
            probeQuery("CancelTimerAndWrite#takeOver"),
            commandRow("RestartTimerInPhase#start"),
            probeQuery("RestartTimerInPhase#resync"),
            commandRow("SuppressTimerAndWriteNow#start"),
            probeQuery("SuppressTimerAndWriteNow#pause"),
        ),
    )

    private fun cancelSet(owner: String, call: String, create: (ProbeEnvironment) -> Any, start: suspend (Any) -> Unit) = queryProbeSet(
        owner = owner,
        scenarios = listOf(scenarioA(warmUp = { o, _ -> start(o) })),
        calls = listOf(CallSpec("$owner#$call")),
        assemble = create,
    )

    private fun compare(result: ProbeRunResult) = ViolationChecks.compare(
        observed = result.observed,
        backgroundChanges = result.backgroundChanges,
        table = emptyList(),
        ledger = cancelWorld.rows,
        population = cancelWorld.population,
        sliceIds = SliceIds.all,
    )

    @Test(timeout = 60_000)
    fun timerをcancelして直接書く形は直接の書込と背景の減少とJobの状態変化の3つで落ちる() {
        val set = cancelSet(
            "CancelTimerAndWrite", "takeOver",
            { env -> CancelTimerAndWrite(env.apiClient, env.newDispatcher()) },
            { (it as CancelTimerAndWrite).start() },
        )

        val result = runPassing(set, cancelWorld.context)

        assertEquals(
            "呼出 2 回 × 直接 2 回の DIRECT 4 件だけ",
            List(4) { write("CancelTimerAndWrite#takeOver", "suspend ()", Origin.DIRECT) },
            result.observed,
        )
        val decreased = result.backgroundChanges.filter { it.kind == BackgroundChangeKind.WRITE_DECREASED }
        assertEquals("t0+15/30/45/60 の 4 件の減少: ${result.backgroundChanges}", 4, decreased.size)
        assertEquals("減少した時刻ごとに別の変化", 4, decreased.map { it.detail }.toSet().size)
        val jobChanges = result.backgroundChanges.filter { it.kind == BackgroundChangeKind.JOB_STATE_CHANGED }
        assertEquals(1, jobChanges.size)
        assertTrue("job の鍵を名指しする: ${jobChanges.single().detail}", jobChanges.single().detail.contains("job"))
        assertEquals(
            "timer の Job は control で ACTIVE、treatment で CANCELLED",
            listOf(JobState.ACTIVE, JobState.CANCELLED),
            listOf(result.traces.control(), result.traces.treatments().single()).map { trace ->
                trace.backgroundJobs.single { it.key.contains("job") }.atDeadline
            },
        )

        val findings = compare(result)
        findings.requireFinding(Headings.UNLISTED_WRITE, "CancelTimerAndWrite#takeOver")
        findings.requireFinding(Headings.BACKGROUND_CHANGE, "CancelTimerAndWrite#takeOver")
    }

    @Test(timeout = 60_000)
    fun timerを同じ位相で張り直す形は件数の差が0でもJobの状態変化で落ちる() {
        val set = cancelSet(
            "RestartTimerInPhase", "resync",
            { env -> RestartTimerInPhase(env.apiClient, env.newDispatcher()) },
            { (it as RestartTimerInPhase).start() },
        )

        val result = runPassing(set, cancelWorld.context)

        assertTrue("張り直した timer は印が無く同じ位相なので観測 0: ${result.observed}", result.observed.isEmpty())
        assertEquals(
            listOf(BackgroundChangeKind.JOB_STATE_CHANGED),
            result.backgroundChanges.map { it.kind },
        )
        assertEquals(
            "t0 に集めた元の Job が treatment で CANCELLED",
            JobState.CANCELLED,
            result.traces.treatments().single().backgroundJobs.single { it.key.contains("job") }.atDeadline,
        )

        val findings = compare(result)
        findings.requireFinding(Headings.BACKGROUND_CHANGE, "RestartTimerInPhase#resync")
        assertTrue("書込は観測されていない", findings.withHeading(Headings.UNLISTED_WRITE).isEmpty())
    }

    @Test(timeout = 60_000)
    fun flagで背景の書込を止めて内部scopeから同じ件数を書く形は時刻つきの差で落ちる() {
        val set = cancelSet(
            "SuppressTimerAndWriteNow", "pause",
            { env -> SuppressTimerAndWriteNow(env.apiClient, env.newDispatcher()) },
            { (it as SuppressTimerAndWriteNow).start() },
        )

        val result = runPassing(set, cancelWorld.context)

        assertEquals(
            "内部 scope の書込は印が無いので BACKGROUND。時刻はすべて t0 で control の 0 件との差は 4",
            List(4) { write("SuppressTimerAndWriteNow#pause", "()", Origin.BACKGROUND) },
            result.observed,
        )
        assertEquals(
            "timer の 4 件が flag で消えた",
            List(4) { BackgroundChangeKind.WRITE_DECREASED },
            result.backgroundChanges.map { it.kind },
        )
        assertEquals(
            "timer の Job は両方とも期限で ACTIVE",
            listOf(JobState.ACTIVE, JobState.ACTIVE),
            listOf(result.traces.control(), result.traces.treatments().single()).map { trace ->
                trace.backgroundJobs.single { it.key.contains("job") }.atDeadline
            },
        )

        val findings = compare(result)
        findings.requireFinding(Headings.UNLISTED_WRITE, "SuppressTimerAndWriteNow#pause")
        findings.requireFinding(Headings.BACKGROUND_CHANGE, "SuppressTimerAndWriteNow#pause")
    }
}

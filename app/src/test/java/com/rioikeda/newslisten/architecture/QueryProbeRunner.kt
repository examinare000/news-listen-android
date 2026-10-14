@file:OptIn(ExperimentalCoroutinesApi::class)

package com.rioikeda.newslisten.architecture

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * 呼び方 1 つ。中身は引数の supplier だけ（実行単位の環境を受け、引数の list を返す）。
 * runner が所有者の class の `(operationId, signature)` の public メソッドを、この引数で reflection により呼ぶ。
 * [signature] を省けるのは、その行の signature が 1 つのときだけ。
 */
class CallSpec(
    val operationId: String,
    val signature: String? = null,
    val args: (ProbeEnvironment) -> List<Any?> = { emptyList() },
)

/**
 * 1 つの所有者の型の probe set。consumer（各 context の `*QueryProbes.kt`）が実装する面は、
 * 組立・warm-up（scenario が持つ）・呼び方・後始末・scenario だけで、`Method` は受け取らない。
 */
interface QueryProbeSet {
    /** 所有者の型名（package を除く）。 */
    val owner: String
    val scenarios: List<ProbeScenario>
    val calls: List<CallSpec>

    /** 実行単位の環境だけからグラフを組み、所有者を返す。環境の外のオブジェクトを使わない。 */
    fun assemble(env: ProbeEnvironment): Any

    fun cleanup(env: ProbeEnvironment, owner: Any) {}
}

/** probe set を関数の列で作る。 */
fun queryProbeSet(
    owner: String,
    scenarios: List<ProbeScenario>,
    calls: List<CallSpec> = emptyList(),
    cleanup: ((ProbeEnvironment, Any) -> Unit)? = null,
    assemble: (ProbeEnvironment) -> Any,
): QueryProbeSet = object : QueryProbeSet {
    override val owner = owner
    override val scenarios = scenarios
    override val calls = calls
    override fun assemble(env: ProbeEnvironment): Any = assemble(env)
    override fun cleanup(env: ProbeEnvironment, owner: Any) {
        cleanup?.invoke(env, owner)
    }
}

/** scenario だけを変えた写し（warm-up を外す対照など）。 */
fun QueryProbeSet.mapScenarios(transform: (ProbeScenario) -> ProbeScenario): QueryProbeSet {
    val base = this
    return object : QueryProbeSet {
        override val owner = base.owner
        override val scenarios = base.scenarios.map(transform)
        override val calls = base.calls
        override fun assemble(env: ProbeEnvironment): Any = base.assemble(env)
        override fun cleanup(env: ProbeEnvironment, owner: Any) = base.cleanup(env, owner)
    }
}

/** 呼び方だけを変えた写し。 */
fun QueryProbeSet.withCalls(newCalls: List<CallSpec>): QueryProbeSet {
    val base = this
    return object : QueryProbeSet {
        override val owner = base.owner
        override val scenarios = base.scenarios
        override val calls = newCalls
        override fun assemble(env: ProbeEnvironment): Any = base.assemble(env)
        override fun cleanup(env: ProbeEnvironment, owner: Any) = base.cleanup(env, owner)
    }
}

/** runner への入力のうち、probe set の外にあるもの。 */
data class ProbeContext(val population: Population, val ledger: List<LedgerRow>, val catalog: EffectTypeCatalog)

/** 観測した query の書込 1 件（件数は多重集合の要素の数）。 */
data class ObservedWrite(
    val operationId: String,
    val signature: String,
    val port: String,
    val op: String,
    val scenario: String,
    val origin: Origin,
)

enum class ResolutionSource { AUTHOR, DEFAULT }

data class ResolvedCall(val operationId: String, val signature: String, val source: ResolutionSource)

enum class BackgroundChangeKind { WRITE_DECREASED, JOB_STATE_CHANGED }

/** 背景処理の変化。違反の表の行では許可できない失敗。 */
data class BackgroundChange(
    val operationId: String,
    val signature: String,
    val scenario: String,
    val kind: BackgroundChangeKind,
    val detail: String,
)

enum class JobState { ACTIVE, COMPLETED, CANCELLED }

enum class UnitRole { CONTROL, TREATMENT }

enum class CallCompletion { NORMAL, INJECTED_FAILURE, INCOMPLETE }

data class CallRecord(val method: Method, val completion: CallCompletion)

data class BackgroundJobRecord(val key: String, val atT0: JobState, val atDeadline: JobState)

/** 代入の後・配線の検査の前に読んだ `FUNCTION` の field の値。`label` は hook 表の `所有者#field`。 */
data class FunctionFieldValue(val holder: Any, val label: String, val value: Any?)

/**
 * 実行単位の記録。runner が実際に使ったオブジェクトへの参照で、写しではない。
 * `architecture/` の検査器のテストだけが読む。
 */
data class ExecutionUnitTrace(
    val ownerType: String,
    val scenario: String,
    val role: UnitRole,
    /** treatment の対象 `(操作 ID, signature)`。control は null。 */
    val target: Pair<String, String>?,
    val env: ProbeEnvironment,
    val graph: List<Any>,
    val functionFields: List<FunctionFieldValue>,
    val calls: List<CallRecord>,
    val t0: Long,
    val readAt: Long,
    val backgroundJobs: List<BackgroundJobRecord>,
    val failures: List<ProbeFailure>,
    val aborted: Boolean,
)

data class ProbeRunResult(
    val observed: List<ObservedWrite>,
    val resolutions: List<ResolvedCall>,
    val traces: List<ExecutionUnitTrace>,
    val backgroundChanges: List<BackgroundChange>,
)

/** 最初に失敗した実行単位の手順 11 で投げる。実行単位より前の失敗（呼び方の解決）は trace を持たない。 */
class ProbeRunFailure(message: String, val trace: ExecutionUnitTrace?) : RuntimeException(message)

object QueryProbeRunner {

    /** 観測の期限（`t0` からの仮想時間）。control と treatment で共通。 */
    internal const val WINDOW_MILLIS = 60_000L

    /**
     * probe set を、実行単位ごとの環境の作成・組立・warm-up・観測・後始末の順で実行する。scenario の条件（A/B/warm-up の必須）は当てない
     * （網羅の検査 [ProbeCoverage] だけが当てる）。
     */
    fun run(probeSet: QueryProbeSet, context: ProbeContext): ProbeRunResult {
        val (targets, resolutionFindings) = CallResolution.resolve(probeSet, context)
        if (resolutionFindings.isNotEmpty()) {
            throw ProbeRunFailure(
                "probe の呼び方の解決に失敗した: ${probeSet.owner}\n" + resolutionFindings.joinToString("\n") { "  $it" },
                null,
            )
        }
        val classifier = TypeClassifier(context.population, context.catalog)
        val effectful = EffectfulTypes.fixedPoint(classifier)
        val wiring = ProbeWiring(classifier, effectful, context.catalog.hooks.associateBy { it.ownerField })

        val traces = mutableListOf<ExecutionUnitTrace>()
        val observed = mutableListOf<ObservedWrite>()
        val changes = mutableListOf<BackgroundChange>()
        for (scenario in probeSet.scenarios) {
            val control = UnitExecution(probeSet, scenario, null, null, wiring).execute()
            traces += control.trace
            control.failureMessage()?.let { throw ProbeRunFailure(it, control.trace) }
            for (target in targets) {
                val treatment = UnitExecution(probeSet, scenario, target, control.jobKeys, wiring).execute()
                traces += treatment.trace
                treatment.failureMessage()?.let { throw ProbeRunFailure(it, treatment.trace) }
                attribute(control, treatment, target, scenario, observed, changes)
            }
        }
        return ProbeRunResult(
            observed = observed,
            resolutions = targets.map { ResolvedCall(it.row.id, it.signature, it.source) },
            traces = traces,
            backgroundChanges = changes,
        )
    }

    /** 帰属の 3 段（直接・背景の差・背景の Job の状態）で、treatment の書込を query に帰属させる。 */
    private fun attribute(
        control: UnitResult,
        treatment: UnitResult,
        target: ResolvedTarget,
        scenario: ProbeScenario,
        observed: MutableList<ObservedWrite>,
        changes: MutableList<BackgroundChange>,
    ) {
        val id = target.row.id
        val signature = target.signature
        treatment.writes.filter { it.origin == Origin.DIRECT }.forEach {
            observed += ObservedWrite(id, signature, it.port, it.op, scenario.name, Origin.DIRECT)
        }

        fun backgroundCounts(unit: UnitResult) = unit.writes.filter { it.origin == Origin.BACKGROUND }
            .groupingBy { Triple(it.port, it.op, it.at - unit.t0) }.eachCount()

        val controlCounts = backgroundCounts(control)
        val treatmentCounts = backgroundCounts(treatment)
        for (key in (controlCounts.keys + treatmentCounts.keys).sortedWith(compareBy({ it.third }, { it.first }, { it.second }))) {
            val before = controlCounts[key] ?: 0
            val after = treatmentCounts[key] ?: 0
            val (port, op, offset) = key
            when {
                after > before -> repeat(after - before) {
                    observed += ObservedWrite(id, signature, port, op, scenario.name, Origin.BACKGROUND)
                }
                after < before -> changes += BackgroundChange(
                    id, signature, scenario.name, BackgroundChangeKind.WRITE_DECREASED,
                    "$port#$op の t0+${offset}ms の書込が減った（control $before 件 → treatment $after 件）",
                )
            }
        }

        val controlStates = control.jobStatesAtDeadline
        treatment.jobStatesAtDeadline.forEach { (key, state) ->
            val before = controlStates[key]
            if (before != null && before != state) {
                changes += BackgroundChange(
                    id, signature, scenario.name, BackgroundChangeKind.JOB_STATE_CHANGED,
                    "背景の Job $key の期限での状態が変わった（control $before → treatment $state）",
                )
            }
        }
    }
}

/** 1 つの実行単位の結果。 */
internal class UnitResult(
    val trace: ExecutionUnitTrace,
    val writes: List<WriteEvent>,
    val t0: Long,
    val jobKeys: Set<String>,
    val jobStatesAtDeadline: Map<String, JobState>,
    private val message: String?,
) {
    fun failureMessage(): String? = message
}

private class CallState {
    var injected = false
    var returnedFlow = false
    var invoked = false
    lateinit var job: Job
}

private class TrackedJob(val key: String, val job: Job, val atT0: JobState)

/**
 * 1 つの probe set の 1 つの scenario の、control か treatment の 1 回。環境（scheduler・recorder・記録 fake・hooks）と
 * グラフは、この実行単位のために新しく作り、別の実行単位と共有しない。
 */
private class UnitExecution(
    private val set: QueryProbeSet,
    private val scenario: ProbeScenario,
    private val target: ResolvedTarget?,
    private val controlKeys: Set<String>?,
    private val wiring: ProbeWiring,
) {
    private val env = ProbeEnvironment.create(scenario)
    private val recorder get() = env.recorder
    private val scheduler: TestCoroutineScheduler get() = env.scheduler
    private val dispatcher = env.newDispatcher()
    private val errors = mutableListOf<Finding>()
    private val runnerJobs = mutableListOf<Job>()
    private val warmUpJobs = mutableListOf<Job>()
    private val calls = mutableListOf<CallState>()

    private var owner: Any? = null
    private var graph: List<Any> = emptyList()
    private var functionFields: List<FunctionFieldValue> = emptyList()
    private var assembled = false
    private var aborted = false
    private var t0 = 0L
    private var readAt = 0L
    private var tracked: List<TrackedJob> = emptyList()
    private var writes: List<WriteEvent> = emptyList()
    private var statesAtDeadline: Map<String, JobState> = emptyMap()
    private var completions: List<CallCompletion> = emptyList()

    fun execute(): UnitResult {
        val thread = Thread.currentThread()
        val saved = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable -> onThreadFailure(throwable) }
        try {
            // 内部 scope の未捕捉の例外は handler で失敗として報告するので、kotlinx-coroutines-test の collector に溜まった分は取り除く。
            CoroutineTestExceptionGuard.protect {
                observe()
                cleanup()
            }
        } finally {
            thread.uncaughtExceptionHandler = if (saved === thread.threadGroup) null else saved
        }
        return result()
    }

    // ---- 手順 2〜10 ----

    private fun observe() {
        // 手順 2: 組立
        val built = try {
            set.assemble(env)
        } catch (e: Throwable) {
            recorder.fail(FailureStage.ASSEMBLY, "組立の lambda が例外を投げた: ${describe(e)}", e)
            aborted = true
            return
        }
        owner = built
        assembled = true
        graph = try {
            wiring.graphOf(built)
        } catch (e: Throwable) {
            recorder.fail(FailureStage.ASSEMBLY, "probe のグラフを辿れない: ${describe(e)}", e)
            aborted = true
            return
        }
        if (recorder.failures.isNotEmpty()) {
            aborted = true
            return
        }

        // 手順 3: SETTER の行へ工場の WRITE hook を代入する。手順 4: FUNCTION の field の値の記録と配線の検査。
        errors += wiring.assignSetters(graph, env.hooks)
        functionFields = wiring.functionFields(graph)
        errors += wiring.check(graph, env)
        if (errors.isNotEmpty() || recorder.failures.isNotEmpty()) {
            aborted = true
            return
        }

        // 手順 5: warm-up
        recorder.stage = ProbeStage.WARM_UP
        scenario.warmUp?.let { warmUp ->
            val job = launchRoot(dispatcher + runnerHandler(FailureStage.WARM_UP, null)) { warmUp(built, env) }
            runnerJobs += job
            warmUpJobs += job
            scheduler.runCurrent()
            if (!job.isCompleted) {
                recorder.fail(FailureStage.WARM_UP, "warm-up が runCurrent の後も完了していない（仮想時間の待ちに入った）")
            }
        }
        if (recorder.failures.isNotEmpty()) {
            aborted = true
            return
        }

        // 手順 6: 記録を空にし、t0 と背景の Job を記録する。
        recorder.stage = ProbeStage.OBSERVE
        recorder.writes.clear()
        t0 = scheduler.currentTime
        tracked = collectBackgroundJobs()
        if (controlKeys != null) {
            val keys = tracked.map { it.key }.toSet()
            if (keys != controlKeys) {
                errors += Finding(
                    Headings.BACKGROUND_KEYS, set.owner,
                    "t0 の背景の Job の鍵の集合が control と違う（control のみ: ${(controlKeys - keys).sorted()}、treatment のみ: ${(keys - controlKeys).sorted()}）",
                )
            }
        }

        // 手順 7: treatment の呼出を 2 回（独立の coroutine）。どちらも仮想時刻 t0 に起動する。
        if (target != null) {
            repeat(2) {
                val state = CallState()
                state.job = launchRoot(dispatcher + env.marker + runnerHandler(FailureStage.CALL, state)) {
                    invokeCall(target, built, state)
                }
                calls += state
                runnerJobs += state.job
                scheduler.runCurrent()
            }
        }

        // 手順 8: 期限
        scheduler.advanceTimeBy(QueryProbeRunner.WINDOW_MILLIS)
        scheduler.runCurrent()
        if (scheduler.currentTime != t0 + QueryProbeRunner.WINDOW_MILLIS) {
            errors += Finding(
                Headings.DEADLINE_CLOCK, set.owner,
                "期限の仮想時刻が t0 + 60 秒でない（t0=$t0、実際=${scheduler.currentTime}）",
            )
        }

        // 手順 9: 記録を読む。
        readAt = scheduler.currentTime
        writes = recorder.writes.toList()
        statesAtDeadline = tracked.associate { it.key to stateOf(it.job) }

        // 手順 10: 完了していない呼出の失敗と、収集の cancel。
        completions = calls.map { state ->
            when {
                state.injected -> CallCompletion.INJECTED_FAILURE
                !state.returnedFlow && !state.job.isCompleted -> CallCompletion.INCOMPLETE
                else -> CallCompletion.NORMAL
            }
        }
        completions.forEachIndexed { index, completion ->
            if (completion == CallCompletion.INCOMPLETE) {
                errors += Finding(
                    Headings.DEADLINE_INCOMPLETE, "${target!!.row.id} ${target.signature}",
                    "呼出 ${index + 1} 回目が t0 + 60 秒の期限までに完了しない（scenario ${scenario.name}）",
                )
            }
        }
        runnerJobs.forEach { it.cancel() }
    }

    // ---- 手順 11 ----

    private fun cleanup() {
        if (!assembled) return
        val built = owner ?: return
        recorder.stage = ProbeStage.CLEANUP
        try {
            set.cleanup(env, built)
        } catch (e: Throwable) {
            recorder.fail(FailureStage.CLEANUP, "後始末の lambda が例外を投げた: ${describe(e)}", e)
        }
        val scopes = mutableListOf<Pair<String, Job>>()
        val seen = IdentityHashMap<Job, Boolean>()
        for (obj in graph) {
            for (field in wiring.instanceFields(obj)) {
                if (!CoroutineScope::class.java.isAssignableFrom(field.type)) continue
                val scope = field.get(obj) as? CoroutineScope ?: continue
                val job = scope.coroutineContext[Job] ?: continue
                if (seen.put(job, true) != null) continue
                scopes += wiring.label(field) to job
                scope.cancel()
            }
        }
        runnerJobs.forEach { it.cancel() }
        scheduler.runCurrent()
        scopes.filter { (_, job) -> job.isActive }.forEach { (label, _) ->
            errors += Finding(Headings.LEFTOVER_JOB, label, "runner の cancel と runCurrent の後も scope の Job が active")
        }
    }

    // ---- 呼出 ----

    private suspend fun invokeCall(target: ResolvedTarget, built: Any, state: CallState) {
        val args = target.args(env)
        val method = target.operation.method
        val declared = target.operation.parameterTypes
        args.forEachIndexed { index, value ->
            val label = "${target.row.id} ${target.signature} の引数 #${index + 1}"
            val declaredType = declared.getOrNull(index)
            val finding = declaredType?.let { wiring.checkArgument(label, it, value, env) }
            if (finding != null) {
                errors += finding
                return
            }
        }
        method.isAccessible = true
        val receiver = if (Modifier.isStatic(method.modifiers)) null else built
        state.invoked = true
        val result: Any? = if (target.signature.startsWith("suspend ")) {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                reflectiveInvoke(method, receiver, args + continuation)
            }
        } else {
            reflectiveInvoke(method, receiver, args)
        }
        if (result is Flow<*>) {
            state.returnedFlow = true
            result.collect { }
        }
    }

    private fun reflectiveInvoke(method: Method, receiver: Any?, args: List<Any?>): Any? = try {
        method.invoke(receiver, *args.toTypedArray())
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    // ---- 例外の受け口 ----

    private fun runnerHandler(kind: FailureStage, state: CallState?): CoroutineContext =
        CoroutineExceptionHandler { _, throwable ->
            val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
            when {
                cause is CancellationException -> Unit
                state != null && isInjected(cause) -> state.injected = true
                else -> recorder.fail(kind, "runner の coroutine（${kind.label}）の例外: ${describe(cause)}", cause)
            }
        }

    private fun onThreadFailure(throwable: Throwable) {
        if (throwable is CancellationException) return
        recorder.failHere("thread の uncaughtExceptionHandler で受けた未捕捉の例外: ${describe(throwable)}", throwable)
    }

    private fun isInjected(throwable: Throwable): Boolean =
        generateSequence(throwable) { it.cause }.any { candidate -> recorder.injected.any { it === candidate } }

    private fun describe(throwable: Throwable): String = "${throwable.javaClass.name}: ${throwable.message}"

    private fun launchRoot(context: CoroutineContext, block: suspend CoroutineScope.() -> Unit): Job =
        CoroutineScope(SupervisorJob() + context).launch(block = block)

    // ---- 背景の Job ----

    private fun stateOf(job: Job): JobState = when {
        job.isCancelled -> JobState.CANCELLED
        job.isCompleted -> JobState.COMPLETED
        else -> JobState.ACTIVE
    }

    private fun collectBackgroundJobs(): List<TrackedJob> {
        val seen = IdentityHashMap<Job, Boolean>()
        val result = mutableListOf<TrackedJob>()
        fun add(key: String, job: Job) {
            if (job.isActive && seen.put(job, true) == null) result += TrackedJob(key, job, stateOf(job))
        }
        fun addDescendants(prefix: String, job: Job) {
            job.children.toList().forEachIndexed { index, child ->
                add("$prefix/$index", child)
                addDescendants("$prefix/$index", child)
            }
        }
        for (obj in graph) {
            for (field in wiring.instanceFields(obj)) {
                val value = field.get(obj)
                val label = wiring.label(field)
                if (value is CoroutineScope && CoroutineScope::class.java.isAssignableFrom(field.type)) {
                    value.coroutineContext[Job]?.let { addDescendants(label, it) }
                }
                if (value is Job && Job::class.java.isAssignableFrom(field.type)) add(label, value)
            }
        }
        warmUpJobs.forEachIndexed { index, job -> add("warm-up#${index + 1}", job) }
        return result
    }

    // ---- 結果 ----

    private fun result(): UnitResult {
        val failures = recorder.failures.toList()
        val trace = ExecutionUnitTrace(
            ownerType = set.owner,
            scenario = scenario.name,
            role = if (target == null) UnitRole.CONTROL else UnitRole.TREATMENT,
            target = target?.let { it.row.id to it.signature },
            env = env,
            graph = graph,
            functionFields = functionFields,
            calls = calls.indices.mapNotNull { index ->
                if (!calls[index].invoked) null else CallRecord(target!!.operation.method, completions.getOrElse(index) { CallCompletion.NORMAL })
            },
            t0 = t0,
            readAt = readAt,
            backgroundJobs = tracked.map { BackgroundJobRecord(it.key, it.atT0, statesAtDeadline[it.key] ?: it.atT0) },
            failures = failures,
            aborted = aborted,
        )
        return UnitResult(
            trace = trace,
            writes = writes,
            t0 = t0,
            jobKeys = tracked.map { it.key }.toSet(),
            jobStatesAtDeadline = statesAtDeadline,
            message = failureText(failures),
        )
    }

    private fun failureText(failures: List<ProbeFailure>): String? {
        if (failures.isEmpty() && errors.isEmpty()) return null
        val identity = buildString {
            append("探査の実行単位が失敗した: ${set.owner} ")
            if (target == null) append("control") else append("treatment ${target.row.id} ${target.signature}")
            append(" scenario=${scenario.name}")
        }
        val lines = failures.map { "  [段=${it.stage.label}] ${it.cause}" } + errors.map { "  $it" }
        return identity + "\n" + lines.joinToString("\n")
    }
}

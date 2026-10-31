@file:OptIn(ExperimentalCoroutinesApi::class)

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.container.CallbacksOwner
import com.rioikeda.newslisten.architecture.fixture.container.JobsOwner
import com.rioikeda.newslisten.architecture.fixture.container.LazyApiOwner
import com.rioikeda.newslisten.architecture.fixture.container.StoresOwner
import com.rioikeda.newslisten.architecture.fixture.getters.GetterOwner
import com.rioikeda.newslisten.architecture.fixture.overload.OverloadedLoader
import com.rioikeda.newslisten.architecture.fixture.wiring.DispatcherOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.FeedReader
import com.rioikeda.newslisten.architecture.fixture.wiring.HookedOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.MixedInfraOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.PlatformOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.PortOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.PublicCallbackOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.ReadonlyCallbackOwner
import com.rioikeda.newslisten.architecture.fixture.wiring.ScopeOwner
import com.rioikeda.newslisten.model.FeedResponse
import com.rioikeda.newslisten.network.SessionStore
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配線の検査（probe のグラフの各 field を宣言の型で検査する）、呼び方の解決、登録 list に当てる網羅の検査。
 * 正しい probe を落とさず、port・hook・dispatcher・scope の取り違えを field 名つきで落とす。
 */
class ProbeWiringTest {

    private val feed = FeedResponse(articles = emptyList(), date = "2026-10-14")

    private val plainSessionStore = object : SessionStore {
        override fun save(token: String): Boolean = true
        override fun load(): String? = null
        override fun clear() {}
    }

    private fun catalogWith(vararg hooks: HookRow): EffectTypeCatalog =
        EffectTypeCatalog.production.let { it.copy(hooks = it.hooks + hooks) }

    private val hookedOwner = HookRow("HookedOwner#onSave", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR)
    private val publicCallbackSetter = HookRow("PublicCallbackOwner#onChanged", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER)
    private val readonlyCallback = HookRow("ReadonlyCallbackOwner#onDone", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR)
    private val mixedWrite = HookRow("MixedInfraOwner#onItem", HookKind.WRITE, HookShape.SUSPEND_ONE_ARG, HookWiring.CONSTRUCTOR)
    private val mixedRead = HookRow("MixedInfraOwner#reorder", HookKind.READ, HookShape.ONE_ARG, HookWiring.CONSTRUCTOR)

    private val wiringRows = listOf(
        probeQuery("HookedOwner#ping"),
        probeQuery("PortOwner#ping"),
        probeQuery("PublicCallbackOwner#ping"),
        probeQuery("PublicCallbackOwner#getOnChanged"),
        commandRow("PublicCallbackOwner#setOnChanged"),
        probeQuery("ReadonlyCallbackOwner#ping"),
        probeQuery("FeedReader#peek"),
        probeQuery("DispatcherOwner#ping"),
        probeQuery("PlatformOwner#ping"),
        probeQuery("ScopeOwner#ping"),
        probeQuery("MixedInfraOwner#count"),
    )

    private fun wiringWorld(vararg hooks: HookRow) = FixtureWorld(
        anchor = HookedOwner::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.wiring",
        files = listOf("WiringFixtures.kt"),
        rows = wiringRows,
        catalog = catalogWith(*hooks),
    )

    private val fullHooks = arrayOf(hookedOwner, publicCallbackSetter, readonlyCallback, mixedWrite, mixedRead)

    private fun probe(owner: String, call: String, scenarios: List<ProbeScenario> = listOf(scenarioA()), create: (ProbeEnvironment) -> Any) =
        queryProbeSet(owner, scenarios, listOf(CallSpec("$owner#$call")), assemble = create)

    private fun assertWiringFailure(set: QueryProbeSet, world: FixtureWorld, heading: String, vararg parts: String) {
        val failure = assertThrows(ProbeRunFailure::class.java) { QueryProbeRunner.run(set, world.context) }
        assertFailureMessage(failure, heading, *parts)
    }

    private fun assertWiringPasses(set: QueryProbeSet, world: FixtureWorld): ProbeRunResult {
        val result = QueryProbeRunner.run(set, world.context)
        assertTrue("実行単位が実行されていない（空振り）", result.traces.isNotEmpty())
        assertTrue("配線の違反が出ない probe に ProbeFailure: ${result.traces.flatMap { it.failures }}", result.traces.all { it.failures.isEmpty() })
        return result
    }

    // ---- hook の配線 ----

    @Test(timeout = 60_000)
    fun 呼び方を外した非getterのPROBE行は欠けた呼び方として名指しされる() {
        val world = wiringWorld(*fullHooks)
        val set = probe("HookedOwner", "ping") { env -> HookedOwner(env.hooks.write("HookedOwner#onSave")) }

        val findings = ProbeCoverage.check(listOf(set.withCalls(emptyList())), world.context)
        findings.requireFinding(Headings.MISSING_CALL, "HookedOwner#ping")

        assertTrue(
            "正の対照: 呼び方がある probe は欠けた呼び方にならない",
            ProbeCoverage.check(listOf(set), world.context).withHeading(Headings.MISSING_CALL).isEmpty(),
        )
    }

    @Test(timeout = 60_000)
    fun 構築子のhookに工場の外の関数を渡すと工場の外の関数として失敗する() {
        val world = wiringWorld(*fullHooks)
        val outside = probe("HookedOwner", "ping") { _ -> HookedOwner({}) }
        val factory = probe("HookedOwner", "ping") { env -> HookedOwner(env.hooks.write("HookedOwner#onSave")) }

        assertWiringFailure(outside, world, Headings.WIRING_OUTSIDE_FACTORY, "HookedOwner#onSave")

        val trace = assertWiringPasses(factory, world).traces.first()
        val value = trace.functionFields.single { it.label == "HookedOwner#onSave" }.value
        assertEquals(
            HookRegistration("HookedOwner#onSave", HookKind.WRITE, HookShape.NO_ARG),
            trace.env.hooks.registrationOf(value),
        )
    }

    @Test(timeout = 60_000)
    fun WRITEのfieldにREADの関数を渡すと分類の不一致として失敗する() {
        val world = wiringWorld(*fullHooks)
        val mismatch = probe("HookedOwner", "ping") { env -> HookedOwner(env.hooks.read("HookedOwner#onSave") { Unit }) }

        assertWiringFailure(mismatch, world, Headings.WIRING_CLASS_MISMATCH, "HookedOwner#onSave")
    }

    @Test(timeout = 60_000)
    fun portに記録fakeでない実物を渡すと記録fakeでない実物として失敗する() {
        val world = wiringWorld(*fullHooks)
        val plain = probe("PortOwner", "ping") { _ -> PortOwner(plainSessionStore) }
        val recording = probe("PortOwner", "ping") { env -> PortOwner(env.sessionStore) }

        assertWiringFailure(plain, world, Headings.WIRING_NOT_RECORDING_FAKE, "sessions")
        assertWiringPasses(recording, world)
    }

    @Test(timeout = 60_000)
    fun 公開callbackをCONSTRUCTORと書いた表はrunnerの代入から漏れて工場の外の関数として失敗する() {
        val leaking = wiringWorld(
            hookedOwner,
            HookRow("PublicCallbackOwner#onChanged", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR),
            readonlyCallback, mixedWrite, mixedRead,
        )
        val set = probe("PublicCallbackOwner", "ping") { _ -> PublicCallbackOwner() }

        assertWiringFailure(set, leaking, Headings.WIRING_OUTSIDE_FACTORY, "PublicCallbackOwner#onChanged")
    }

    @Test(timeout = 60_000)
    fun 公開callbackをSETTERと書いた正の対照はrunnerが工場のwriteを代入して配線の違反0で通る() {
        val world = wiringWorld(*fullHooks)
        val set = probe("PublicCallbackOwner", "ping") { _ -> PublicCallbackOwner() }

        val result = assertWiringPasses(set, world)

        val trace = result.traces.first()
        val assigned = trace.functionFields.single { it.label == "PublicCallbackOwner#onChanged" }
        assertEquals(
            "runner が代入した値が、その実行単位の工場の WRITE の関数として登録されている",
            HookRegistration("PublicCallbackOwner#onChanged", HookKind.WRITE, HookShape.NO_ARG),
            trace.env.hooks.registrationOf(assigned.value),
        )
    }

    @Test(timeout = 60_000)
    fun setterの無いvalをSETTERと書いた表は代入できないSETTERの行として失敗する() {
        val world = wiringWorld(
            hookedOwner, publicCallbackSetter,
            HookRow("ReadonlyCallbackOwner#onDone", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER),
            mixedWrite, mixedRead,
        )
        val set = probe("ReadonlyCallbackOwner", "ping") { env -> ReadonlyCallbackOwner(env.hooks.write("ReadonlyCallbackOwner#onDone")) }

        assertWiringFailure(set, world, Headings.UNASSIGNABLE_SETTER, "onDone")
    }

    // ---- scenario の条件は登録 list に当てる網羅の検査だけが当てる ----

    @Test(timeout = 60_000)
    fun scenarioBが欠けた登録は網羅の検査で落ちるがrunに直接渡した対照は落ちない() {
        val world = wiringWorld(*fullHooks)
        val onlyA = probe("FeedReader", "peek", listOf(scenarioA(canned = mapOf("ApiClient#fetchFeed" to listOf<Any?>(feed, feed))))) { env ->
            FeedReader(env.apiClient)
        }

        val findings = ProbeCoverage.check(listOf(onlyA), world.context)
        findings.requireFinding(Headings.MISSING_SCENARIO, "FeedReader", "B")

        val result = QueryProbeRunner.run(onlyA, world.context)
        assertTrue("書込 0 件のはず: ${result.observed}", result.observed.isEmpty())
        assertTrue("run は scenario の条件を当てない（treatment が実行されている）", result.traces.any { it.role == UnitRole.TREATMENT })
        assertTrue(result.traces.all { it.failures.isEmpty() })

        val withB = probe(
            "FeedReader", "peek",
            listOf(scenarioA(canned = mapOf("ApiClient#fetchFeed" to listOf<Any?>(feed, feed))), scenarioB()),
        ) { env -> FeedReader(env.apiClient) }
        assertTrue(
            "正の対照: scenario B があれば欠けた scenario にならない",
            ProbeCoverage.check(listOf(withB), world.context).withHeading(Headings.MISSING_SCENARIO).isEmpty(),
        )
    }

    // ---- dispatcher・scope・PLATFORM ----

    @Test(timeout = 60_000)
    fun 別のschedulerのdispatcherとscopeとPLATFORMのfieldはfield名を名指しして失敗する() {
        val world = wiringWorld(*fullHooks)
        val foreign = { StandardTestDispatcher(TestCoroutineScheduler()) }

        assertWiringFailure(
            probe("DispatcherOwner", "ping") { _ -> DispatcherOwner(foreign()) },
            world, Headings.WIRING_FOREIGN_SCHEDULER, "dispatcher",
        )
        assertWiringFailure(
            probe("ScopeOwner", "ping") { _ -> ScopeOwner(CoroutineScope(SupervisorJob() + foreign())) },
            world, Headings.WIRING_FOREIGN_SCHEDULER, "scope",
        )
        assertWiringFailure(
            probe("PlatformOwner", "ping") { _ -> PlatformOwner(null) },
            world, Headings.WIRING_PLATFORM, "context",
        )
    }

    @Test(timeout = 60_000)
    fun その実行単位のschedulerに載るdispatcherとscopeは通る() {
        val world = wiringWorld(*fullHooks)

        assertWiringPasses(probe("DispatcherOwner", "ping") { env -> DispatcherOwner(env.newDispatcher()) }, world)
        assertWiringPasses(
            probe("ScopeOwner", "ping") { env -> ScopeOwner(CoroutineScope(SupervisorJob() + env.newDispatcher())) },
            world,
        )
    }

    // ---- 組み立て直後に null の INFRA・同期の型・引数を取る hook ----

    private fun mixedSet(optionalApi: (ProbeEnvironment) -> com.rioikeda.newslisten.network.ApiClient?) = probe("MixedInfraOwner", "count") { env ->
        MixedInfraOwner(
            api = env.apiClient,
            onItem = env.hooks.suspendWrite1<String>("MixedInfraOwner#onItem"),
            reorder = env.hooks.read1<List<String>, List<String>>("MixedInfraOwner#reorder") { it },
            optionalApi = optionalApi(env),
        )
    }

    @Test(timeout = 60_000)
    fun nullのJobと同期の型と引数を取るhookを持つ所有者は分類も配線も通り書込0件になる() {
        val world = wiringWorld(*fullHooks)

        val findings = CatalogVerifier.verify(world.population, world.catalog)
        assertTrue("母集団が空", world.population.classes.isNotEmpty())
        assertTrue("未分類:\n${findings.render()}", findings.isEmpty())

        val result = assertWiringPasses(mixedSet { it.apiClient }, world)
        assertTrue("query の書込 0 件のはず: ${result.observed}", result.observed.isEmpty())
    }

    @Test(timeout = 60_000)
    fun nullableな宣言のportにnullを渡すと効果を持ちうる型のfieldがnullとして失敗する() {
        val world = wiringWorld(*fullHooks)

        assertWiringFailure(mixedSet { null }, world, Headings.WIRING_NULL_EFFECTFUL, "optionalApi")
    }

    // ---- 型引数に効果を持ちうる型を含む容器 ----

    private val containerWorld = FixtureWorld(
        anchor = StoresOwner::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.container",
        files = listOf("ContainerFixtures.kt"),
        rows = listOf(
            probeQuery("StoresOwner#ping"),
            probeQuery("LazyApiOwner#ping"),
            probeQuery("CallbacksOwner#ping"),
            probeQuery("JobsOwner#ping"),
        ),
    )

    @Test(timeout = 60_000)
    fun 容器の型引数にportや関数型を含むfieldは中身が記録fakeでも失敗し正の対照は通る() {
        val world = containerWorld
        val findings = CatalogVerifier.verify(world.population, world.catalog)
        assertTrue("母集団が空", world.population.classes.isNotEmpty())
        assertTrue("分類表の完全性は通る:\n${findings.render()}", findings.isEmpty())

        assertWiringFailure(
            probe("StoresOwner", "ping") { env -> StoresOwner(listOf(env.sessionStore)) },
            world, Headings.WIRING_CONTAINER, "stores",
        )
        assertWiringFailure(
            probe("LazyApiOwner", "ping") { env -> LazyApiOwner(lazyOf(env.apiClient)) },
            world, Headings.WIRING_CONTAINER, "lazyApi",
        )
        assertWiringFailure(
            probe("CallbacksOwner", "ping") { _ -> CallbacksOwner(MutableStateFlow({})) },
            world, Headings.WIRING_CONTAINER, "callbacks",
        )
        assertWiringPasses(probe("JobsOwner", "ping") { _ -> JobsOwner(mutableMapOf(), MutableStateFlow(null)) }, world)
    }

    // ---- 呼び方の解決（getter の既定生成） ----

    private val getterWorld = FixtureWorld(
        anchor = GetterOwner::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.getters",
        files = listOf("GetterFixtures.kt"),
        rows = listOf(probeQuery("GetterOwner#getA"), probeQuery("GetterOwner#getB")),
    )

    private fun getterSet(calls: List<CallSpec>) =
        queryProbeSet("GetterOwner", listOf(scenarioA()), calls) { _ -> GetterOwner() }

    @Test(timeout = 60_000)
    fun 作者がgetterの呼び方を書かなければrunnerが既定を生成する() {
        val set = getterSet(emptyList())

        assertTrue(ProbeCoverage.check(listOf(set), getterWorld.context).render(), ProbeCoverage.check(listOf(set), getterWorld.context).isEmpty())
        val result = QueryProbeRunner.run(set, getterWorld.context)

        assertEquals(
            setOf(
                ResolvedCall("GetterOwner#getA", "()", ResolutionSource.DEFAULT),
                ResolvedCall("GetterOwner#getB", "()", ResolutionSource.DEFAULT),
            ),
            result.resolutions.toSet(),
        )
        assertEquals("getter の treatment が 2 つ実行される", 2, result.traces.count { it.role == UnitRole.TREATMENT })
    }

    @Test(timeout = 60_000)
    fun 作者が1つのgetterの呼び方を書けばそのgetterには既定を生成しない() {
        val set = getterSet(listOf(CallSpec("GetterOwner#getA")))

        val result = QueryProbeRunner.run(set, getterWorld.context)

        assertEquals(
            listOf(
                ResolvedCall("GetterOwner#getA", "()", ResolutionSource.AUTHOR),
                ResolvedCall("GetterOwner#getB", "()", ResolutionSource.DEFAULT),
            ),
            result.resolutions.sortedBy { it.operationId },
        )
    }

    @Test(timeout = 60_000)
    fun 同じgetterの呼び方を2つ登録すると重なった呼び方として失敗する() {
        val set = getterSet(listOf(CallSpec("GetterOwner#getA"), CallSpec("GetterOwner#getA")))

        ProbeCoverage.check(listOf(set), getterWorld.context)
            .requireFinding(Headings.OVERLAPPING_CALL, "GetterOwner#getA", "()")

        val failure = assertThrows(ProbeRunFailure::class.java) { QueryProbeRunner.run(set, getterWorld.context) }
        assertFailureMessage(failure, Headings.OVERLAPPING_CALL, "GetterOwner#getA")
        assertNull("実行単位より前の失敗は trace を持たない", failure.trace)
    }

    // ---- overload は signature の単位で呼ぶ ----

    private val overloadRows = listOf(
        probeQuery("OverloadedLoader#load", signatures = setOf("()", "(int)", "suspend (String)")),
    )

    private val overloadWorld = FixtureWorld(
        anchor = OverloadedLoader::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.overload",
        files = listOf("OverloadFixtures.kt"),
        rows = overloadRows,
    )

    private fun overloadSet(calls: List<CallSpec>) =
        queryProbeSet("OverloadedLoader", listOf(scenarioA()), calls) { env -> OverloadedLoader(env.sessionStore, env.preferencesStore) }

    private val allOverloadCalls = listOf(
        CallSpec("OverloadedLoader#load", "()"),
        CallSpec("OverloadedLoader#load", "(int)") { listOf(1) },
        CallSpec("OverloadedLoader#load", "suspend (String)") { listOf("a") },
    )

    @Test(timeout = 60_000)
    fun overloadの一部の呼び方だけだと欠けた呼び方として足りないsignatureを名指しする() {
        val set = overloadSet(listOf(CallSpec("OverloadedLoader#load", "()")))

        ProbeCoverage.check(listOf(set), overloadWorld.context)
            .requireFinding(Headings.MISSING_CALL, "OverloadedLoader#load", "(int)", "suspend (String)")
    }

    @Test(timeout = 60_000)
    fun overloadの全signatureの呼び方があればそれぞれ対象のMethodを2回呼び表に無い書込として落ちる() {
        val set = overloadSet(allOverloadCalls)

        val outcome = QueryEffectChecks.execute(overloadWorld.input(listOf(set)))

        val result = outcome.results.single()
        val treatments = result.traces.filter { it.role == UnitRole.TREATMENT }
        assertEquals("signature ごとに treatment が 1 つ", 3, treatments.size)
        val expectedParameters = mapOf(
            "()" to emptyList<Class<*>>(),
            "(int)" to listOf(Int::class.javaPrimitiveType!!),
            "suspend (String)" to listOf(String::class.java, Continuation::class.java),
        )
        treatments.forEach { trace ->
            val (id, signature) = trace.target!!
            assertEquals("OverloadedLoader#load", id)
            assertEquals(2, trace.calls.size)
            trace.calls.forEach { call ->
                assertEquals("load", call.method.name)
                assertEquals("対象の signature の Method を呼ぶ: $signature", expectedParameters.getValue(signature), call.method.parameterTypes.toList())
                assertEquals("期限までに完了する", CallCompletion.NORMAL, call.completion)
            }
        }
        assertEquals(setOf(ResolutionSource.AUTHOR), result.resolutions.map { it.source }.toSet())

        val expectedObserved = List(2) { ObservedWrite("OverloadedLoader#load", "(int)", "SessionStore", "save", "A", Origin.DIRECT) } +
            List(2) { ObservedWrite("OverloadedLoader#load", "suspend (String)", "PreferencesStore", "markAchievementsSeen", "A", Origin.DIRECT) }
        assertEquals(
            "() は 0 件、(int) と suspend (String) が 2 件ずつ",
            expectedObserved.groupingBy { it }.eachCount(),
            result.observed.groupingBy { it }.eachCount(),
        )

        val unlisted = outcome.findings.withHeading(Headings.UNLISTED_WRITE)
        assertEquals("表に無い書込はこの 2 つだけ: ${outcome.findings.render()}", 2, unlisted.size)
        assertEquals("ほかの失敗は無い", unlisted, outcome.findings)
        unlisted.requireFinding(Headings.UNLISTED_WRITE, "OverloadedLoader#load", "(int)", "SessionStore#save")
        unlisted.requireFinding(Headings.UNLISTED_WRITE, "OverloadedLoader#load", "suspend (String)", "PreferencesStore#markAchievementsSeen")
    }
}

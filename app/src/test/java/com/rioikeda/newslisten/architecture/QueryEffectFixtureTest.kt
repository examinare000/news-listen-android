package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.violation.FlowCollectFiresCallback
import com.rioikeda.newslisten.architecture.fixture.violation.FlowCollectReadsOnly
import com.rioikeda.newslisten.architecture.fixture.violation.GetterReadsOnly
import com.rioikeda.newslisten.architecture.fixture.violation.GetterSavesSession
import com.rioikeda.newslisten.architecture.fixture.violation.HelperReadsOnly
import com.rioikeda.newslisten.architecture.fixture.violation.HelperRewritesSameValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 負の fixture 3 つ（helper 経由の同値 write・getter 内の write・Flow の収集時の callback の write）が、
 * 本番と同じ選別から実行時の検査まで通して RED になり、読み取りだけの正の fixture 3 つは green になる。
 * fixture は本番の台帳・違反の表・登録 list に現れず、違反の表は空で通す。
 */
class QueryEffectFixtureTest {

    private companion object {
        private val callbackWrite = HookRow("FlowCollectFiresCallback#onChanged", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER)
        private val callbackRead = HookRow("FlowCollectReadsOnly#onChanged", HookKind.WRITE, HookShape.NO_ARG, HookWiring.SETTER)

        val world = FixtureWorld(
            anchor = HelperRewritesSameValue::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.violation",
            files = listOf("QueryEffectFixtures.kt"),
            rows = listOf(
                probeQuery("HelperRewritesSameValue#load"),
                probeQuery("GetterSavesSession#getItems"),
                probeQuery("FlowCollectFiresCallback#observe"),
                probeQuery("FlowCollectFiresCallback#getOnChanged"),
                commandRow("FlowCollectFiresCallback#setOnChanged"),
                probeQuery("HelperReadsOnly#load"),
                probeQuery("GetterReadsOnly#getItems"),
                probeQuery("FlowCollectReadsOnly#observe"),
                probeQuery("FlowCollectReadsOnly#getOnChanged"),
                commandRow("FlowCollectReadsOnly#setOnChanged"),
            ),
            catalog = EffectTypeCatalog.production.let { it.copy(hooks = it.hooks + callbackWrite + callbackRead) },
        )

        // 公開 callback を持つ所有者は command（setter）を持つので、warm-up を持つ scenario を置く（中身は何もしない）。
        private val noopWarmUp: suspend (Any, ProbeEnvironment) -> Unit = { _, _ -> }

        private val probeSets: List<QueryProbeSet> = listOf(
            queryProbeSet("HelperRewritesSameValue", listOf(scenarioA()), listOf(CallSpec("HelperRewritesSameValue#load"))) { env ->
                HelperRewritesSameValue(env.preferencesStore)
            },
            queryProbeSet("GetterSavesSession", listOf(scenarioA())) { env -> GetterSavesSession(env.sessionStore) },
            queryProbeSet(
                "FlowCollectFiresCallback", listOf(scenarioA(warmUp = noopWarmUp)), listOf(CallSpec("FlowCollectFiresCallback#observe")),
            ) { _ -> FlowCollectFiresCallback() },
            queryProbeSet("HelperReadsOnly", listOf(scenarioA()), listOf(CallSpec("HelperReadsOnly#load"))) { env ->
                HelperReadsOnly(env.preferencesStore)
            },
            queryProbeSet("GetterReadsOnly", listOf(scenarioA())) { env -> GetterReadsOnly(env.preferencesStore) },
            queryProbeSet(
                "FlowCollectReadsOnly", listOf(scenarioA(warmUp = noopWarmUp)), listOf(CallSpec("FlowCollectReadsOnly#observe")),
            ) { _ -> FlowCollectReadsOnly() },
        )

        val outcome: CheckOutcome by lazy {
            // 違反の表・既知の違反は空。fixture は移行違反の許可表を通さない。
            QueryEffectChecks.execute(world.input(probeSets, violations = emptyList(), known = emptyList()))
        }
    }

    private val unlisted: List<Finding> get() = outcome.findings.withHeading(Headings.UNLISTED_WRITE)

    private fun treatmentsOf(owner: String): List<ExecutionUnitTrace> =
        outcome.results.flatMap { it.traces }.filter { it.ownerType == owner && it.role == UnitRole.TREATMENT }

    @Test(timeout = 120_000)
    fun helper経由の同値の書込を持つqueryは表に無い書込として落ちる() {
        assertTrue("fixture の母集団が列挙されていない", outcome.population?.operations?.isNotEmpty() == true)

        unlisted.requireFinding(Headings.UNLISTED_WRITE, "HelperRewritesSameValue#load", "PreferencesStore#markAchievementsSeen")
    }

    @Test(timeout = 120_000)
    fun getter内の書込は既定生成の呼び方で表に無い書込として落ちる() {
        unlisted.requireFinding(Headings.UNLISTED_WRITE, "GetterSavesSession#getItems", "SessionStore#save")
        assertEquals(
            "getter の呼び方は runner が既定生成した",
            ResolutionSource.DEFAULT,
            outcome.results.flatMap { it.resolutions }.single { it.operationId == "GetterSavesSession#getItems" }.source,
        )
    }

    @Test(timeout = 120_000)
    fun Flowの収集時のcallbackの書込は配線の違反0のまま表に無い書込として落ちる() {
        val wiring = listOf(
            Headings.WIRING_CLASS_MISMATCH, Headings.WIRING_OUTSIDE_FACTORY, Headings.WIRING_NOT_RECORDING_FAKE,
            Headings.WIRING_CONTAINER, Headings.WIRING_NULL_EFFECTFUL, Headings.WIRING_FOREIGN_SCHEDULER,
            Headings.WIRING_PLATFORM, Headings.UNASSIGNABLE_SETTER,
        )
        assertTrue(
            "公開 callback の代入の後に配線を検査するので、配線の違反は 0: ${outcome.findings.render()}",
            outcome.findings.none { it.heading in wiring },
        )

        unlisted.requireFinding(Headings.UNLISTED_WRITE, "FlowCollectFiresCallback#observe", "hook", "FlowCollectFiresCallback#onChanged")
        assertEquals(
            "Flow を 2 回呼んで、収集ごとに callback が 1 回発火する",
            List(2) {
                ObservedWrite("FlowCollectFiresCallback#observe", "()", "hook", "FlowCollectFiresCallback#onChanged", "A", Origin.DIRECT)
            },
            outcome.observed.filter { it.operationId == "FlowCollectFiresCallback#observe" },
        )
    }

    @Test(timeout = 120_000)
    fun 読み取りだけの正の対照は同じ経路で実行され0件になる() {
        val positives = listOf("HelperReadsOnly", "GetterReadsOnly", "FlowCollectReadsOnly")

        positives.forEach { owner ->
            val treatments = treatmentsOf(owner)
            assertTrue("$owner の treatment が実行されていない（空振り）", treatments.isNotEmpty())
            assertTrue(
                "$owner の呼出が期限までに完了していない",
                treatments.all { t -> t.calls.isNotEmpty() && t.calls.all { it.completion == CallCompletion.NORMAL } },
            )
            assertTrue(
                "$owner が観測された: ${outcome.observed.filter { it.operationId.startsWith("$owner#") }}",
                outcome.observed.none { it.operationId.startsWith("$owner#") },
            )
        }

        assertEquals(
            "負の fixture の 3 件だけが失敗する: ${outcome.findings.render()}",
            3,
            outcome.findings.size,
        )
        assertEquals(outcome.findings, unlisted)
        assertTrue(
            "fixture が本番の違反の表・台帳に現れた",
            QueryWriteViolations.table.none { it.operationId in world.rows.map { r -> r.id } } &&
                OperationLedger.rows.none { it.id in world.rows.map { r -> r.id } },
        )
    }
}

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.cancel.CancelTimerAndWrite
import com.rioikeda.newslisten.architecture.fixture.cancel.SuppressTimerAndWriteNow
import com.rioikeda.newslisten.architecture.fixture.overload.OverloadedLoader
import com.rioikeda.newslisten.architecture.fixture.summary.SummaryOwner
import com.rioikeda.newslisten.account.accountViewModelQueryProbes
import com.rioikeda.newslisten.account.sessionsViewModelQueryProbes
import com.rioikeda.newslisten.auth.authViewModelQueryProbes
import com.rioikeda.newslisten.engagement.ApiListeningStreakStore
import com.rioikeda.newslisten.engagement.apiListeningStreakStoreQueryProbes
import com.rioikeda.newslisten.feed.feedViewModelQueryProbes
import com.rioikeda.newslisten.learning.learningViewModelQueryProbes
import com.rioikeda.newslisten.onboarding.onboardingViewModelQueryProbes
import com.rioikeda.newslisten.passkey.passkeyCredentialsViewModelQueryProbes
import com.rioikeda.newslisten.passkey.passkeyLoginViewModelQueryProbes
import com.rioikeda.newslisten.passkey.passkeyRegistrationViewModelQueryProbes
import com.rioikeda.newslisten.podcast.podcastViewModelQueryProbes
import com.rioikeda.newslisten.settings.settingsViewModelQueryProbes
import com.rioikeda.newslisten.vocabulary.vocabularyTestViewModelQueryProbes
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本番の検査（全 query が間接的にも書き込まないことを、台帳・分類表・probe・移行違反の表で検査する）と、
 * 到達先・総数・保証範囲の要約。各 context の probe set は、ここの登録 list に足す。
 */

/** 登録 list。後続 slice が操作・query を足したら、その所有者の probe set をここに足す。 */
internal val registeredQueryProbeSets: List<QueryProbeSet> = listOf(
    podcastViewModelQueryProbes,
    authViewModelQueryProbes,
    accountViewModelQueryProbes,
    sessionsViewModelQueryProbes,
    passkeyCredentialsViewModelQueryProbes,
    passkeyLoginViewModelQueryProbes,
    passkeyRegistrationViewModelQueryProbes,
    feedViewModelQueryProbes,
    learningViewModelQueryProbes,
    vocabularyTestViewModelQueryProbes,
    settingsViewModelQueryProbes,
    onboardingViewModelQueryProbes,
    apiListeningStreakStoreQueryProbes,
)

internal fun productionCheckInput(probeSets: List<QueryProbeSet> = registeredQueryProbeSets): CheckInput = CheckInput(
    classRoot = ProductionWorld.classRoot,
    packagePrefix = ProductionWorld.PACKAGE_PREFIX,
    layerOf = LayerMap.byFile,
    loader = ProductionWorld.loader,
    ledger = OperationLedger.rows,
    catalog = EffectTypeCatalog.production,
    probeSets = probeSets,
    violations = QueryWriteViolations.table,
    knownViolations = QueryWriteViolations.known,
    sliceIds = SliceIds.all,
)

/**
 * 到達先・総数・保証範囲の要約。`## ` で始まる行で区切った 5 つの節を、次の順で返す。
 * (1) 操作総数 (2) 型のグラフが持つ書込の port と WRITE の hook (3) 観測した query の書込 (4) command の到達先を観測していないことの明記
 * (5) 保証範囲の外側。要約は検査と同じ入力から作る。
 */
internal fun renderQueryEffectSummary(
    operations: List<PublicOperation>,
    ledger: List<LedgerRow>,
    catalog: EffectTypeCatalog,
    result: ProbeRunResult,
): String {
    val sections = listOf(
        "## 1. 操作総数" to summaryTotals(operations, ledger, result),
        "## 2. 型のグラフが持つ書込の port と WRITE の hook" to summaryReach(operations, catalog),
        "## 3. 観測した query の書込" to summaryObserved(result),
        "## 4. command の到達先" to listOf(
            "command の操作単位の到達先は観測していない。(2) は型のグラフが持つ書込の port と hook を出したもので、command がどれを呼ぶかは示さない。",
        ),
        "## 5. 保証範囲の外側（この検査が数えない範囲）" to OUTSIDE_GUARANTEE,
    )
    return sections.joinToString("\n\n") { (title, lines) -> title + "\n" + lines.joinToString("\n") } + "\n"
}

private val OUTSIDE_GUARANTEE = listOf(
    "- 関数本体の中で生成したオブジェクト経由の作用（field にも引数にも現れない adapter）。依存の規則が import の段階で押さえる範囲。",
    "- static な SDK・OS の API の直接呼出（例 SystemClock・Log）と、母集団の外の Kotlin object の状態。",
    "- probe が作らない状態でしか通らない分岐（scenario A・B・warm-up の canned の値で到達しない分岐、記録 fake の書込が返す固定の既定値や property の読みの初期値と違う値でしか到達しない分岐を含む）。",
    "- 期限 t0 + 60 秒より後の遅延処理と、期限の後に runner が収集・scope を cancel したことで起きる処理。実行単位の scheduler に載らない dispatcher。",
    "- Composable 関数（UI の入口で、query と command の分類の対象外）。",
    "- command の操作単位の到達先（観測しない。(2) は型のグラフの到達先だけを出す）。",
    "- control と treatment で canned の読みの列の進み方が違うために生じる背景処理の差の、原因の特定（差そのものは失敗として現れる）。",
    "- query が背景の Job の状態を変えずに背景の書込を止め、所有者の scope の新しい coroutine から同じ (port, op, 仮想時刻) の多重集合を書く相殺。",
)

private fun summaryTotals(operations: List<PublicOperation>, ledger: List<LedgerRow>, result: ProbeRunResult): List<String> {
    val queries = ledger.filter { it.kind == OperationKind.QUERY }
    return listOf(
        "公開操作: ${operations.size}（列挙した (操作 ID, signature) は ${operations.map { it.id to it.signature }.distinct().size}）",
        "台帳の行: ${ledger.size}（別名 ${ledger.sumOf { it.aliases.size }}）",
        "QUERY: ${queries.size}（PROBE ${queries.count { it.evidence == QueryEvidence.PROBE }}、STRUCTURAL ${queries.count { it.evidence == QueryEvidence.STRUCTURAL }}）",
        "COMMAND: ${ledger.count { it.kind == OperationKind.COMMAND }}",
        "probe の呼び方: ${result.resolutions.size}（getter の既定生成 ${result.resolutions.count { it.source == ResolutionSource.DEFAULT }}、作者の登録 ${result.resolutions.count { it.source == ResolutionSource.AUTHOR }}）",
    )
}

/** 公開操作を宣言する型ごとに、型のグラフ（field の型を辿る）が持つ書込の port と WRITE の hook を機械的に求める。 */
private fun summaryReach(operations: List<PublicOperation>, catalog: EffectTypeCatalog): List<String> {
    val declaring = operations.map { it.method.declaringClass }.distinct()
    val portsOfInterface = catalog.ports.flatMap { row -> row.interfaces.map { it to row.port } }.toMap()
    val writeHooks = catalog.hooks.filter { it.kind == HookKind.WRITE }.map { it.ownerField }.toSet()
    val backed = catalog.addedTypes.filter { it.classification == TypeClass.PORT_BACKED }.map { it.typeName }.toSet()

    fun reach(root: Class<*>): Pair<Set<String>, Set<String>> {
        val ports = sortedSetOf<String>()
        val hooks = sortedSetOf<String>()
        val seen = mutableSetOf<Class<*>>()
        fun visit(type: Class<*>) {
            if (!seen.add(type)) return
            type.declaredFields.forEach { field ->
                val label = "${OperationEnumerator.typeName(type)}#${field.name}"
                if (field.type.name.startsWith(FUNCTION_PREFIX) && label in writeHooks) hooks += label
                typeTree(field.genericType).forEach { referenced ->
                    when {
                        referenced.name in portsOfInterface -> ports += portsOfInterface.getValue(referenced.name)
                        referenced.name in backed || referenced.name.startsWith(PROJECT_PACKAGE) && !referenced.isEnum -> {
                            visit(referenced)
                            // interface・abstract の型は、実装する型（公開操作を宣言する型）のグラフも辿る。
                            if (referenced.isInterface || java.lang.reflect.Modifier.isAbstract(referenced.modifiers)) {
                                declaring.filter { it != referenced && referenced.isAssignableFrom(it) }.forEach { visit(it) }
                            }
                        }
                    }
                }
            }
        }
        visit(root)
        return ports to hooks
    }

    return declaring.mapNotNull { type ->
        val (ports, hooks) = reach(type)
        if (ports.isEmpty() && hooks.isEmpty()) null
        else "${OperationEnumerator.typeName(type)}: port ${ports.joinToString().ifEmpty { "なし" }}; hook ${hooks.joinToString().ifEmpty { "なし" }}"
    }.sorted().ifEmpty { listOf("書込の port も WRITE の hook も持つ型が無い") }
}

private fun summaryObserved(result: ProbeRunResult): List<String> =
    result.observed
        .groupingBy { listOf(it.operationId, it.signature, "${it.port}#${it.op}", it.scenario, it.origin.name) }
        .eachCount()
        .map { (key, count) -> "${key[0]} ${key[1]} ${key[2]} scenario=${key[3]} origin=${key[4]} count=$count" }
        .sorted()
        .ifEmpty { listOf("(観測した書込は無い)") }

internal fun summaryFile(): File {
    val build = if (File("app/build").isDirectory) File("app/build") else File("build")
    return File(build, "reports/query-effect-coverage/summary.txt")
}

/** 本番の検査が green のとき、要約を [summaryFile] に書く。 */
internal fun writeProductionSummary(outcome: CheckOutcome): File {
    val population = outcome.population ?: throw AssertionError("母集団が無いので要約を作れない")
    val text = renderQueryEffectSummary(population.operations, OperationLedger.rows, EffectTypeCatalog.production, outcome.combinedResult())
    return summaryFile().also {
        it.parentFile!!.mkdirs()
        it.writeText(text)
    }
}

class QueryEffectCoverageTest {

    private companion object {
        val owners = listOf(
            "PodcastViewModel", "AuthViewModel", "AccountViewModel", "SessionsViewModel",
            "PasskeyCredentialsViewModel", "PasskeyLoginViewModel", "PasskeyRegistrationViewModel",
            "FeedViewModel", "LearningViewModel", "VocabularyTestViewModel", "SettingsViewModel", "OnboardingViewModel",
            "ApiListeningStreakStore",
        )

        /** 登録 list の各 probe set を、本番の母集団・台帳・分類表で 1 回ずつ run した結果。 */
        val runs: List<Pair<QueryProbeSet, ProbeRunResult>> by lazy {
            registeredQueryProbeSets.map { it to QueryProbeRunner.run(it, ProductionWorld.context) }
        }

        val allTraces: List<ExecutionUnitTrace> get() = runs.flatMap { it.second.traces }

        val productionOutcome: CheckOutcome by lazy { QueryEffectChecks.execute(productionCheckInput()) }
    }

    private fun identitySet(items: Iterable<Any>): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).also { set -> items.forEach { set.add(it) } }

    // ---- 本番の probe の網羅と配線 ----

    @Test(timeout = 600_000)
    fun 登録listは12のViewModelとApiListeningStreakStoreを網羅し配線の違反なしで実行できる() {
        val registered = registeredQueryProbeSets.map { it.owner }
        assertEquals("登録された probe set の所有者", owners.sorted(), registered.sorted())

        val coverage = ProbeCoverage.check(registeredQueryProbeSets, ProductionWorld.context)
        assertTrue("網羅と配線の不一致:\n${coverage.render()}", coverage.isEmpty())
        assertEquals(registeredQueryProbeSets.size, runs.size)
    }

    @Test(timeout = 600_000)
    fun traceはscenarioごとにcontrolと呼び方ごとのtreatmentを含む() {
        assertTrue("trace が空", runs.isNotEmpty() && allTraces.isNotEmpty())
        runs.forEach { (set, result) ->
            val resolved = result.resolutions.map { it.operationId to it.signature }.toSet()
            assertTrue("${set.owner}: 呼び方の解決が 0 件", resolved.isNotEmpty())
            set.scenarios.forEach { scenario ->
                val traces = result.traces.filter { it.ownerType == set.owner && it.scenario == scenario.name }
                assertEquals("${set.owner}/${scenario.name}: control は 1 つ", 1, traces.count { it.role == UnitRole.CONTROL })
                assertEquals(
                    "${set.owner}/${scenario.name}: treatment は (操作 ID, signature) ごとに 1 つ",
                    resolved,
                    traces.filter { it.role == UnitRole.TREATMENT }.map { it.target }.toSet(),
                )
                assertEquals(resolved.size, traces.count { it.role == UnitRole.TREATMENT })
            }
        }
    }

    @Test(timeout = 600_000)
    fun 共有する委譲先のstoreは所有者ごとのグラフにちょうど1つあり_AccountViewModelはAuthViewModelを含む() {
        listOf("SettingsViewModel", "PodcastViewModel").forEach { owner ->
            val traces = allTraces.filter { it.ownerType == owner }
            assertTrue("$owner の trace が無い", traces.isNotEmpty())
            traces.forEach { trace ->
                assertEquals(
                    "$owner のグラフの ApiListeningStreakStore はちょうど 1 つ",
                    1,
                    trace.graph.count { it is ApiListeningStreakStore },
                )
            }
        }
        val account = allTraces.filter { it.ownerType == "AccountViewModel" }
        assertTrue("AccountViewModel の trace が無い", account.isNotEmpty())
        account.forEach { trace ->
            assertTrue(
                "AccountViewModel のグラフに AuthViewModel が無い",
                trace.graph.any { it.javaClass.simpleName == "AuthViewModel" },
            )
        }
    }

    @Test(timeout = 600_000)
    fun hookは該当する所有者の全traceに現れ_実行単位の工場の表に分類と形つきで登録されている() {
        data class Expected(val label: String, val owners: Set<String>, val kind: HookKind, val shape: HookShape)

        val expected = listOf(
            Expected("VocabularyTestViewModel#shuffle", setOf("VocabularyTestViewModel"), HookKind.READ, HookShape.ONE_ARG),
            Expected("PasskeyLoginViewModel#onLoginSuccess", setOf("PasskeyLoginViewModel"), HookKind.WRITE, HookShape.SUSPEND_ONE_ARG),
            Expected("FeedViewModel#onStarConfirmed", setOf("FeedViewModel"), HookKind.WRITE, HookShape.NO_ARG),
            Expected(
                "ApiListeningStreakStore#onStreakIncreased",
                setOf("SettingsViewModel", "PodcastViewModel"),
                HookKind.WRITE,
                HookShape.NO_ARG,
            ),
        )
        assertTrue("trace が空", allTraces.isNotEmpty())

        expected.forEach { e ->
            val traces = allTraces.filter { it.ownerType in e.owners }
            assertTrue("${e.label}: 所有者の trace が無い", traces.isNotEmpty())
            traces.forEach { trace ->
                val fields = trace.functionFields.filter { it.label == e.label }
                assertTrue("${e.label}: ${trace.ownerType}/${trace.scenario}/${trace.role} の functionFields に現れない", fields.isNotEmpty())
                fields.forEach { field ->
                    assertEquals(
                        "${e.label}: 値がその実行単位の hooks の表の分類・形と一致しない",
                        HookRegistration(e.label, e.kind, e.shape),
                        trace.env.hooks.registrationOf(field.value),
                    )
                }
            }
        }
    }

    @Test(timeout = 600_000)
    fun 本番の全probeで背景処理の変化とProbeFailureが0件() {
        assertTrue("trace が空", allTraces.isNotEmpty())
        runs.forEach { (set, result) ->
            assertTrue("${set.owner}: 背景処理の変化 ${result.backgroundChanges}", result.backgroundChanges.isEmpty())
        }
        val failures = allTraces.flatMap { t -> t.failures.map { "${t.ownerType}/${t.scenario}/${t.role}: $it" } }
        assertTrue("本番の探査の失敗が出た:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test(timeout = 600_000)
    fun 実行単位は別の実行単位とオブジェクトを共有しない() {
        val traces = allTraces
        assertTrue("trace が 2 つ未満", traces.size >= 2)
        val graphs = traces.map { identitySet(it.graph) }

        for (i in traces.indices) {
            for (j in i + 1 until traces.size) {
                val a = traces[i]
                val b = traces[j]
                val label = "${a.ownerType}/${a.scenario}/${a.role} と ${b.ownerType}/${b.scenario}/${b.role}"
                assertNotSame("$label: scheduler を共有", a.env.scheduler, b.env.scheduler)
                assertNotSame("$label: recorder を共有", a.env.recorder, b.env.recorder)
                assertNotSame("$label: hooks を共有", a.env.hooks, b.env.hooks)
                assertTrue("$label: 記録 fake を共有", a.env.fakes.none { fa -> b.env.fakes.any { it === fa } })
                assertTrue("$label: グラフのオブジェクトを共有", graphs[i].none { it in graphs[j] })
                assertTrue(
                    "$label: 一方の hook の関数がもう一方の hooks の表にある",
                    a.functionFields.none { b.env.hooks.registrationOf(it.value) != null },
                )
            }
        }
    }

    @Test(timeout = 600_000)
    fun 組立のlambdaを新しい2つの環境で呼ぶとApiListeningStreakStoreは別のオブジェクトになる() {
        listOf("SettingsViewModel", "PodcastViewModel").forEach { owner ->
            val set = registeredQueryProbeSets.single { it.owner == owner }
            val scenario = set.scenarios.first()

            fun storeOf(root: Any): Any = root.javaClass.declaredFields
                .filter { ApiListeningStreakStore::class.java.isAssignableFrom(it.type) || it.type.name.endsWith("ListeningStreakStore") }
                .map { it.apply { isAccessible = true }.get(root) }
                .single { it != null }!!

            val first = storeOf(set.assemble(ProbeEnvironment.create(scenario)))
            val second = storeOf(set.assemble(ProbeEnvironment.create(scenario)))
            assertNotSame("$owner: 組立のたびに store を新しく作る", first, second)
        }
    }

    // ---- 既知の違反の観測 ----

    @Test(timeout = 600_000)
    fun FeedViewModelのwarmupで保留を確定するとloadFeedとrefreshのstarArticleとdismissArticleが観測される() {
        val feed = registeredQueryProbeSets.single { it.owner == "FeedViewModel" }

        val result = QueryProbeRunner.run(feed, ProductionWorld.context)

        val seen = result.observed.map { Triple(it.operationId, it.port, it.op) }.toSet()
        listOf("FeedViewModel#loadFeed", "FeedViewModel#refresh").forEach { operation ->
            listOf("starArticle", "dismissArticle").forEach { op ->
                assertTrue("$operation の ApiClient#$op が観測されない: $seen", Triple(operation, "ApiClient", op) in seen)
            }
        }
        assertTrue("探査の失敗: ${result.traces.flatMap { it.failures }}", result.traces.all { it.failures.isEmpty() })
    }

    @Test(timeout = 600_000)
    fun warmupを外した写しは既知の違反が観測されずprobeの欠陥で失敗する() {
        val feed = registeredQueryProbeSets.single { it.owner == "FeedViewModel" }
        val withoutWarmUp = feed.mapScenarios { it.copy(warmUp = null, noWarmUpReason = "対照: warm-up を外した") }
        val sets = registeredQueryProbeSets.map { if (it.owner == "FeedViewModel") withoutWarmUp else it }

        val outcome = QueryEffectChecks.execute(productionCheckInput(sets))

        assertTrue(
            "既知の違反の list と表は変えていない",
            QueryWriteViolations.known.isNotEmpty() && QueryWriteViolations.known.all { k ->
                QueryWriteViolations.table.any { it.operationId == k.operationId && it.port == k.port && it.op == k.op }
            },
        )
        outcome.findings.requireFinding(Headings.PROBE_DEFECT, "FeedViewModel#loadFeed", "starArticle")
    }

    // ---- 本番の検査 ----

    @Test(timeout = 600_000)
    fun 本番の全probeの観測と移行違反の表が両方向で一致する() {
        val outcome = productionOutcome

        assertTrue("本番の母集団が空", outcome.population?.operations?.isNotEmpty() == true)
        assertTrue("本番の検査の失敗:\n${outcome.findings.render()}", outcome.findings.isEmpty())

        val observed = outcome.observed.map { Triple(it.operationId, it.port, it.op) }.toSet()
        val table = QueryWriteViolations.table.map { Triple(it.operationId, it.port, it.op) }.toSet()
        assertTrue("観測が 0 件（空振り）", observed.isNotEmpty())
        assertEquals("観測した組と移行違反の表の組が等しい", observed, table)
        QueryWriteViolations.known.forEach {
            assertTrue("既知の違反が観測されない: $it", Triple(it.operationId, it.port, it.op) in observed)
        }
        QueryWriteViolations.table.forEach {
            assertTrue("owner・fixTask・removeBy が sliceIds に無い: $it", it.owner in SliceIds.all && it.fixTask in SliceIds.all && it.removeBy in SliceIds.all)
        }

        writeProductionSummary(outcome)
    }

    // ---- skip の禁止 ----

    @Test
    fun 本sliceが足したファイルにassumeとIgnoreが無い() {
        val root = ArchitectureScanner.resolveTestSourceRoot()
        val preexisting = setOf("Allowlist.kt", "ArchitectureStructureTest.kt", "LayerMap.kt")
        val added = root.walkTopDown().filter { f ->
            f.isFile && f.extension == "kt" && (
                (f.parentFile?.name == "architecture" && f.name !in preexisting) ||
                    "architecture${File.separator}fixture" in f.path ||
                    f.name.endsWith("QueryProbes.kt")
                )
        }.toList()
        assertTrue("本 slice が足したファイルが少なすぎる（走査が空振り）: ${added.map { it.name }}", added.size >= 10)
        assertTrue("この検査自身が走査の対象に入っていない", added.any { it.name == "QueryEffectCoverageTest.kt" })

        // 禁止語は、この検査自身に現れないよう組み立てる。
        val forbidden = Regex("""assume(True|False)|@Ign""" + "ore")
        val hits = added.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue("skip の記述が残っている: $hits", hits.isEmpty())
    }

    // ---- 要約（到達先・総数・保証範囲） ----

    private fun sections(summary: String): List<List<String>> {
        val lines = summary.lines()
        val starts = lines.indices.filter { lines[it].startsWith("## ") }
        return starts.mapIndexed { n, start ->
            lines.subList(start + 1, if (n + 1 < starts.size) starts[n + 1] else lines.size).filter { it.isNotBlank() }
        }
    }

    private val rowFormat = Regex("""^\S+ (suspend )?\([^)]*\) \S+#\S+ scenario=\S+ origin=(DIRECT|BACKGROUND) count=\d+$""")

    private fun summaryOf(world: FixtureWorld, result: ProbeRunResult): List<List<String>> {
        val summary = renderQueryEffectSummary(world.population.operations, world.rows, world.catalog, result)
        val parsed = sections(summary)
        assertEquals("要約は 5 つの節を持つ:\n$summary", 5, parsed.size)
        return parsed
    }

    private fun counts(section: List<String>): Int = section.sumOf { Regex("""count=(\d+)$""").find(it)!!.groupValues[1].toInt() }

    @Test(timeout = 120_000)
    fun 要約の到達先はグラフが持つportとWRITEのhookだけを含む() {
        val world = FixtureWorld(
            anchor = SummaryOwner::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.summary",
            files = listOf("SummaryFixtures.kt"),
            rows = listOf(probeQuery("SummaryOwner#ping")),
            catalog = EffectTypeCatalog.production.let {
                it.copy(hooks = it.hooks + HookRow("SummaryOwner#onSaved", HookKind.WRITE, HookShape.NO_ARG, HookWiring.CONSTRUCTOR))
            },
        )
        val set = queryProbeSet("SummaryOwner", listOf(scenarioA()), listOf(CallSpec("SummaryOwner#ping"))) { env ->
            SummaryOwner(env.sessionStore, env.hooks.write("SummaryOwner#onSaved"))
        }

        val reach = summaryOf(world, QueryProbeRunner.run(set, world.context))[1].joinToString("\n")

        assertTrue("port SessionStore が到達先に無い: $reach", reach.contains("SessionStore"))
        assertTrue("WRITE の hook が到達先に無い: $reach", reach.contains("SummaryOwner#onSaved"))
        listOf("ApiClient", "PreferencesStore", "PlayerController", "FileSystem", "PasskeyProvider").forEach {
            assertTrue("グラフが持たない port $it が到達先に出た: $reach", !reach.contains(it))
        }
    }

    @Test(timeout = 120_000)
    fun 要約は違う_signatureの書込を別の行で出し件数の合計は観測と等しい() {
        val world = FixtureWorld(
            anchor = OverloadedLoader::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.overload",
            files = listOf("OverloadFixtures.kt"),
            rows = listOf(probeQuery("OverloadedLoader#load", signatures = setOf("()", "(int)", "suspend (String)"))),
        )
        val set = queryProbeSet(
            "OverloadedLoader",
            listOf(scenarioA()),
            listOf(
                CallSpec("OverloadedLoader#load", "()"),
                CallSpec("OverloadedLoader#load", "(int)") { listOf(1) },
                CallSpec("OverloadedLoader#load", "suspend (String)") { listOf("a") },
            ),
        ) { env -> OverloadedLoader(env.sessionStore, env.preferencesStore) }
        val result = QueryProbeRunner.run(set, world.context)

        val observed = summaryOf(world, result)[2]

        assertEquals(
            listOf(
                "OverloadedLoader#load (int) SessionStore#save scenario=A origin=DIRECT count=2",
                "OverloadedLoader#load suspend (String) PreferencesStore#markAchievementsSeen scenario=A origin=DIRECT count=2",
            ),
            observed,
        )
        assertTrue("書込の無い () の行が出た", observed.none { it.startsWith("OverloadedLoader#load ()") })
        assertEquals(result.observed.size, counts(observed))
    }

    @Test(timeout = 120_000)
    fun 要約は直接の書込と背景の書込を1行ずつ出す() {
        val world = FixtureWorld(
            anchor = CancelTimerAndWrite::class.java,
            packageName = "com.rioikeda.newslisten.architecture.fixture.cancel",
            files = listOf("CancelFixtures.kt"),
            rows = listOf(
                commandRow("CancelTimerAndWrite#start"), probeQuery("CancelTimerAndWrite#takeOver"),
                commandRow("RestartTimerInPhase#start"), probeQuery("RestartTimerInPhase#resync"),
                commandRow("SuppressTimerAndWriteNow#start"), probeQuery("SuppressTimerAndWriteNow#pause"),
            ),
        )
        val cancelSet = queryProbeSet(
            "CancelTimerAndWrite",
            listOf(scenarioA(warmUp = { o, _ -> (o as CancelTimerAndWrite).start() })),
            listOf(CallSpec("CancelTimerAndWrite#takeOver")),
        ) { env -> CancelTimerAndWrite(env.apiClient, env.newDispatcher()) }
        val suppressSet = queryProbeSet(
            "SuppressTimerAndWriteNow",
            listOf(scenarioA(warmUp = { o, _ -> (o as SuppressTimerAndWriteNow).start() })),
            listOf(CallSpec("SuppressTimerAndWriteNow#pause")),
        ) { env -> SuppressTimerAndWriteNow(env.apiClient, env.newDispatcher()) }

        val takeOver = QueryProbeRunner.run(cancelSet, world.context)
        val direct = summaryOf(world, takeOver)[2]
        assertEquals(
            listOf("CancelTimerAndWrite#takeOver suspend () ApiClient#updatePlaybackPosition scenario=A origin=DIRECT count=4"),
            direct,
        )
        assertEquals(takeOver.observed.size, counts(direct))

        val pause = QueryProbeRunner.run(suppressSet, world.context)
        val background = summaryOf(world, pause)[2]
        assertEquals(
            listOf("SuppressTimerAndWriteNow#pause () ApiClient#updatePlaybackPosition scenario=A origin=BACKGROUND count=4"),
            background,
        )
        assertEquals(pause.observed.size, counts(background))
    }

    @Test(timeout = 600_000)
    fun 本番の要約は5節を持ちファイルに書かれrun内IDを含まない() {
        val outcome = productionOutcome
        val file = writeProductionSummary(outcome)

        assertTrue("要約のファイルが無い: $file", file.isFile)
        val text = file.readText()
        val parsed = sections(text)
        assertEquals("要約は 5 つの節を持つ", 5, parsed.size)

        val population = outcome.population ?: throw AssertionError("母集団が無い")
        assertTrue("(1) 操作総数（行数）が無い", parsed[0].joinToString("\n").contains(OperationLedger.rows.size.toString()))
        assertTrue("(1) 公開操作の総数が無い", parsed[0].joinToString("\n").contains(population.operations.size.toString()))
        assertTrue("(2) 到達先が空", parsed[1].isNotEmpty())
        assertTrue("(3) の行が行の形に合わない: ${parsed[2].filterNot { rowFormat.matches(it) }}", parsed[2].isNotEmpty() && parsed[2].all { rowFormat.matches(it) })
        assertEquals("(3) の行は辞書順", parsed[2].sorted(), parsed[2])
        assertEquals("(3) の件数の合計は観測の件数と等しい", outcome.observed.size, counts(parsed[2]))
        assertTrue("(4) command の到達先を観測していない旨が無い", parsed[3].joinToString("\n").contains("command"))
        assertTrue("(5) 保証範囲の外側が 8 項目未満", parsed[4].size >= 8)
        assertTrue("run 内 ID の形が残っている", !Regex("""OUT[0-9]""").containsMatchIn(text))
    }
}

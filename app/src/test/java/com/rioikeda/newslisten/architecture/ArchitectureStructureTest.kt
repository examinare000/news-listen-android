package com.rioikeda.newslisten.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Target Architecture の構造検査（Spec §7 android-check-01〜android-check-09・§8.4 android-slice-skeleton-01）。
 *
 * main を字句で走査し、違反の組 `(規則 ID, ファイル, 対象)` の集合が [Allowlist] の集合と**等しい**ことを検査する。
 * 違反が増えても（許可リストに無い違反）、減らしたのに許可リストを縮め忘れても（違反が無い組）落ちる。
 *
 * 走査器 [ArchitectureScanner] は、本ファイルの下で定義する（契約: spec.md §3.3・§3.4・CI-01〜CI-17）。
 * main・`build.gradle.kts`・既存テストは変えない。`konsist`・`archunit`・`detekt`・`ktlint` は足さない（ADR-066・SG-R11）。
 *
 * 限界（検査の限界であり、許可リストの対象外）:
 * - 同じ package の参照は import に現れないので、依存の向きの検査では見えない。
 * - 型の情報を持たないので、port の受け取りは名前の形、setter の呼出は受け手の変数名 `preferencesStore`、
 *   遷移関数の呼出は受け手の名前の形でしか判定しない。
 * - 同じ組に当たる行が増えても検出しない（組の単位で見る）。
 * - 規則の置き場（android-check-04）は、列挙した式しか数えない。
 * - 見出しの途中、丸括弧の深さ 0 の行が打ち切りの語・`@`・`}` で始まる class、見出しの中の丸括弧の外の `}`、
 *   見出しの語を拾えない形（直前が `:`・`.`・英数字・`_`）の class では、本体を宣言の位置と読めず、
 *   android-dep-07 が黙って 0 件になる（spec §3.3 (3)「誤りの向き」(i)〜(iii)）。
 */
class ArchitectureStructureTest {

    private companion object {
        val mainSources: List<SourceFile> by lazy {
            ArchitectureScanner.readSources(ArchitectureScanner.resolveMainSourceRoot())
        }
        val testSources: List<SourceFile> by lazy {
            ArchitectureScanner.readSources(ArchitectureScanner.resolveTestSourceRoot())
        }
        val layerOf: Map<String, Layer> get() = LayerMap.byFile

        val sliceIds = setOf(
            "android-slice-playback-02", "android-slice-episode-01", "android-slice-episode-02", "android-slice-playback-03", "android-slice-playback-04", "android-slice-playback-05", "android-slice-ci-01", "android-slice-departure-01", "android-slice-departure-02",
            "android-slice-playapp-01", "android-slice-playapp-02", "android-slice-playapp-03", "android-slice-failure-01", "android-slice-prefs-01", "android-slice-prefs-02", "android-slice-account-01", "android-slice-account-02",
            "android-slice-learning-01", "android-slice-learning-02", "android-slice-learning-03", "android-slice-periphery-01", "android-slice-periphery-02", "android-slice-closure-01",
        )
        val allowlistRuleIds = setOf(
            "android-dep-01", "android-dep-02", "android-dep-03", "android-dep-04", "android-dep-06", "android-dep-07", "android-dep-09",
            "android-check-02", "android-check-04", "android-check-08",
        )
    }

    // ---- 共通の比較（CI-03） ----

    /** 違反の集合 V と許可リストの集合 A の等式。両方向を別の見出しで出す。 */
    private fun assertMatchesAllowlist(rules: Set<String>, hits: List<Hit>) {
        val violations = hits.filter { it.rule in rules }
            .groupBy { it.key }
            .mapValues { (_, same) -> same.flatMap { it.lines }.sorted() }
        val allowed = Allowlist.entries.filter { it.rule in rules }.map { it.key }.toSet()

        val unexpected = violations.keys - allowed
        val stale = allowed - violations.keys

        val message = buildString {
            if (unexpected.isNotEmpty()) {
                appendLine("許可リストに無い違反 (${unexpected.size}):")
                unexpected.sortedBy { it.toString() }.forEach { appendLine("  $it 行=${violations[it]}") }
            }
            if (stale.isNotEmpty()) {
                appendLine("許可リストにあるのに違反が無い組（縮め忘れ） (${stale.size}):")
                stale.sortedBy { it.toString() }.forEach { appendLine("  $it") }
            }
        }
        assertTrue(message, unexpected.isEmpty() && stale.isEmpty())
    }

    /** 恒久の例外を V から除く。V に現れなかった例外は「使われていない」として失敗する。 */
    private fun withoutPermanentExceptions(rules: Set<String>, hits: List<Hit>): List<Hit> {
        val applicable = LayerMap.permanentExceptions.filter { it.rule in rules }
        val unused = applicable.filter { pe ->
            hits.none { it.rule == pe.rule && it.file == pe.file && it.target == pe.target }
        }
        assertTrue("使われていない恒久の例外: ${unused.map { it.id }}", unused.isEmpty())
        return hits.filterNot { hit ->
            applicable.any { it.rule == hit.rule && it.file == hit.file && it.target == hit.target }
        }
    }

    // ---- android-check-01 対応表 ----

    @Test
    fun android_check_01_対応表がmainの全ファイルと1対1で対応する() {
        // verifies: CI-01, CI-02 / T-01
        val actual = mainSources.map { it.path }
        val mapped = LayerMap.rows.map { it.first }

        assertEquals(
            "対応表の key が重複している: ${mapped.groupBy { it }.filter { it.value.size > 1 }.keys}",
            mapped.size,
            mapped.toSet().size,
        )
        val missing = actual.toSet() - mapped.toSet()
        val dangling = mapped.toSet() - actual.toSet()
        val message = buildString {
            if (missing.isNotEmpty()) appendLine("対応表に無いファイル: ${missing.sorted()}")
            if (dangling.isNotEmpty()) appendLine("実在しない対応表の行: ${dangling.sorted()}")
        }
        assertTrue(message, missing.isEmpty() && dangling.isEmpty())
    }

    @Test
    fun android_check_01_依存の向きの違反が許可リストと一致する() {
        // verifies: CI-03, CI-05 / T-02（android-dep-01・android-dep-03・android-dep-04・android-dep-05）
        val rules = setOf("android-dep-01", "android-dep-03", "android-dep-04", "android-dep-05")
        val hits = ArchitectureScanner.dependencyHits(mainSources, layerOf)

        assertMatchesAllowlist(rules, withoutPermanentExceptions(setOf("android-dep-04", "android-dep-05"), hits))
    }

    @Test
    fun android_check_02_データモデルの漏れが許可リストと一致する() {
        // verifies: CI-03, CI-06 / T-03（android-dep-02・android-check-02）
        val hits = ArchitectureScanner.dataModelHits(mainSources, layerOf)

        assertMatchesAllowlist(setOf("android-dep-02", "android-check-02"), hits)
    }

    @Test
    fun android_check_03_adapterの生成と入口とcomposition_rootの比較式が許可リストと一致する() {
        // verifies: CI-03, CI-07 / T-04（android-dep-06）
        val hits = ArchitectureScanner.compositionHits(mainSources)

        assertMatchesAllowlist(setOf("android-dep-06"), withoutPermanentExceptions(setOf("android-dep-06"), hits))
    }

    @Test
    fun android_check_04_規則の式の出現が許可リストと一致する() {
        // verifies: CI-03, CI-10 / T-05（android-check-04・android-dep-09）
        val hits = ArchitectureScanner.ruleLocationHits(mainSources)

        assertMatchesAllowlist(setOf("android-check-04", "android-dep-09"), hits)
    }

    @Test
    fun android_check_05_公開のvarとMutableStateFlowとdata_classの可変が許可リストと一致する() {
        // verifies: CI-03, CI-11, CI-13 / T-06（android-dep-07。android-check-05 の data class は許可リストに行が無く 0 件）
        val hits = ArchitectureScanner.publicSurfaceHits(mainSources, layerOf)

        assertMatchesAllowlist(setOf("android-dep-07"), hits)
        val dataClassHits = hits.filter { it.rule == "android-check-05" }
        assertTrue("data class の可変の違反は 0 件であるべき: $dataClassHits", dataClassHits.isEmpty())
    }

    @Test
    fun android_check_05_検査つき生成の型の構築子が公開でない() {
        // verifies: CI-12 / T-07（対象の型が main に現れるまで skip）
        val types = listOf("Episode", "SubjectId", "PlaybackSpeed", "WeeklyGoal")
        val declared = types.filter { ArchitectureScanner.topLevelDeclarationFile(mainSources, it) != null }
        assumeTrue("対象の型が main に無い（android-slice-episode-01・android-slice-prefs-01・android-slice-account-01 が足す）", declared.isNotEmpty())

        val violations = ArchitectureScanner.nonPublicConstructorViolations(mainSources, declared)
        assertTrue("構築子が公開の型: $violations", violations.isEmpty())
    }

    @Test
    fun android_check_01_型単位の行NowPlayingが宣言のファイルと一致する() {
        // verifies: CI-13 / T-08（NowPlaying が main に現れるまで skip）
        val row = LayerMap.typeRows.single { it.type == "NowPlaying" }
        val declaredIn = ArchitectureScanner.topLevelDeclarationFile(mainSources, "NowPlaying")
        assumeTrue("NowPlaying が main に無い（${row.owner} が足す）", declaredIn != null)

        assertEquals(
            "NowPlaying の宣言のファイルが型単位の行と違う: 宣言=$declaredIn、行=${row.file}",
            row.file,
            declaredIn,
        )
    }

    @Test
    fun android_check_07_QueriesとCommandsの静的な枠() {
        // verifies: CI-14 / T-09（*Queries・*Commands の interface が main に現れるまで skip）
        val targets = ArchitectureScanner.queriesCommandsInterfaces(mainSources)
        assumeTrue("*Queries・*Commands の interface が main に無い（android-slice-playapp-01 が足す）", targets.isNotEmpty())

        val violations = ArchitectureScanner.queriesCommandsViolations(mainSources)
        assertTrue("枠に反する関数: $violations", violations.isEmpty())
    }

    @Test
    fun android_check_08_domainとapplicationのテストが通信とOSをimportしない() {
        // verifies: CI-03, CI-15, §3.4 / T-10
        val layers = ArchitectureScanner.testLayers(testSources, layerOf)
        val layered = layers.filterValues { it is TestLayer.Resolved }
        val ties = layers.filterValues { it is TestLayer.Tie }

        assertTrue("層を持つ test が 0 件（vacuous pass）", layered.isNotEmpty())
        assertTrue(
            "層の分かれる同点がある。LayerMap に test の明示の行を足す: $ties",
            ties.isEmpty(),
        )
        assertMatchesAllowlist(setOf("android-check-08"), ArchitectureScanner.testDependencyHits(testSources, layerOf))
    }

    @Test
    fun android_check_09_error呼出が0件でcode401の比較が2件() {
        // verifies: CI-16 / T-11
        val errors = ArchitectureScanner.errorCallLines(mainSources)
        val code401 = ArchitectureScanner.code401Lines(mainSources)

        assertTrue("error(\" を含む行が残っている: $errors", errors.isEmpty())
        assertEquals("code == 401 を含む行: $code401", 2, code401.size)
    }

    @Test
    fun android_check_01_許可リストの組が一意で持ち主と規則IDが正しい() {
        // verifies: CI-04 / T-12（件数は書かない。以後の slice が減らす）
        val keys = Allowlist.entries.map { it.key }
        val duplicates = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("許可リストの組が重複している: $duplicates", duplicates.isEmpty())

        val badRules = Allowlist.entries.filter { it.rule !in allowlistRuleIds }
        assertTrue("規則 ID が不正: $badRules", badRules.isEmpty())

        val testPaths = testSources.map { it.path }.toSet()
        val badFiles = Allowlist.entries.filter {
            if (it.rule == "android-check-08") it.file !in testPaths else it.file !in LayerMap.byFile.keys
        }
        assertTrue("実在しないファイル: $badFiles", badFiles.isEmpty())

        val badOwners = Allowlist.entries.filter { it.owner !in sliceIds }
        assertTrue("持ち主が §8.1 の slice ID でない: $badOwners", badOwners.isEmpty())
    }

    // ---- 走査器の単体の検査（fixture） ----

    private fun fixture(vararg lines: String) = lines.joinToString("\n")

    private fun lineTexts(text: String, numbers: List<Int>) = numbers.map { text.lines()[it - 1].trim() }

    private fun sourceOf(path: String, vararg lines: String) = SourceFile(path, fixture(*lines))

    @Test
    fun 走査器_import先の対象と最上位の宣言を読める() {
        // verifies: §3.3 (4), CI-05 / T-13
        val pkg = { name: String -> "package com.rioikeda.newslisten.$name" }
        val sources = listOf(
            sourceOf(
                "designsystem/Color.kt", pkg("designsystem"), "",
                "val androidx.compose.material3.ColorScheme.success: Color = Color(0)",
            ),
            sourceOf(
                "designsystem/Theme.kt", pkg("designsystem"), "",
                "@Composable fun NewsListenTheme(",
                "    content: () -> Unit,",
                ") {",
                "}",
            ),
            sourceOf("designsystem/Spacing.kt", pkg("designsystem"), "object DSSpacing {", "    val x = 1", "}"),
            sourceOf(
                "core/Failure.kt", pkg("core"),
                "sealed class Failure(",
                "    val m: String,",
                ") : Exception() {",
                "    class InvalidId : Failure(\"x\")",
                "}",
            ),
            sourceOf("core/Kind.kt", pkg("core"), "enum class Kind { A, B }"),
            sourceOf("core/Wrapper.kt", pkg("core"), "class Wrapper(", "    val bar: Int,", ") {", "}"),
            sourceOf("dup/A.kt", pkg("dup"), "class Same"),
            sourceOf("dup/B.kt", pkg("dup"), "class Same"),
        )
        val layers = mapOf(
            "designsystem/Color.kt" to Layer.PRESENTATION,
            "designsystem/Theme.kt" to Layer.PRESENTATION,
            "designsystem/Spacing.kt" to Layer.PRESENTATION,
            "core/Failure.kt" to Layer.DOMAIN,
            "core/Kind.kt" to Layer.DOMAIN,
            "core/Wrapper.kt" to Layer.DOMAIN,
            "dup/A.kt" to Layer.DOMAIN,
            "dup/B.kt" to Layer.PRESENTATION,
        )
        val index = ArchitectureScanner.declarationIndex(sources, layers)
        val p = "com.rioikeda.newslisten."

        val expected = linkedMapOf<String, String?>(
            "android.os.Bundle" to "android",
            "androidx.compose.ui.Modifier" to "androidx",
            "kotlinx.coroutines.flow.Flow" to "kotlinx.coroutines",
            "kotlinx.serialization.json.Json" to "kotlinx.serialization",
            "okhttp3.OkHttpClient" to "okhttp3",
            "okhttp3.mockwebserver.MockWebServer" to "okhttp3",
            "java.util.UUID" to null,
            "kotlin.math.max" to null,
            "${p}model.Podcast" to "model",
            "${p}network.AudioCacheException" to "network",
            "${p}di.AppContainer" to "di",
            "${p}R" to "R",
            "${p}R.string.app_name" to "R",
            "${p}BuildConfig" to "BuildConfig",
            "${p}designsystem.success" to "layer:PRESENTATION",
            "${p}designsystem.NewsListenTheme" to "layer:PRESENTATION",
            "${p}designsystem.DSSpacing" to "layer:PRESENTATION",
            "${p}core.Failure" to "layer:DOMAIN",
            "${p}core.Kind" to "layer:DOMAIN",
            "${p}core.InvalidId" to ArchitectureScanner.UNRESOLVED,
            "${p}core.bar" to ArchitectureScanner.UNRESOLVED,
            "${p}core.Nothing" to ArchitectureScanner.UNRESOLVED,
            "${p}dup.Same" to ArchitectureScanner.UNRESOLVED,
        )
        val mismatches = expected.mapNotNull { (name, want) ->
            val got = ArchitectureScanner.importTarget(name, index)
            if (got == want) null else "$name: 期待=$want 実際=$got"
        }
        assertTrue("import 先の対象が違う:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())

        assertEquals(
            "okhttp3.mockwebserver は android-check-08 のときだけ別の対象になる",
            "okhttp3.mockwebserver",
            ArchitectureScanner.importTarget("okhttp3.mockwebserver.MockWebServer", index, forTestV8 = true),
        )

        val importText = fixture(
            "package a",
            "import android.os.Bundle",
            "import com.rioikeda.newslisten.core.Foo as Bar",
            "// import okhttp3.OkHttpClient",
            "/*",
            "import okhttp3.Call",
            "*/",
            "import java.util.List",
        )
        assertEquals(
            listOf("android.os.Bundle", "com.rioikeda.newslisten.core.Foo", "java.util.List"),
            ArchitectureScanner.importNames("fixture.kt", importText),
        )
    }

    @Test
    fun 走査器_宣言の位置のvarだけを公開のvarとして数える() {
        // verifies: §3.3 (3), CI-11 / T-14
        class Case(val label: String, val text: String, val vars: List<String>, val flows: List<String> = emptyList())

        val cases = listOf(
            Case(
                "①FeedViewModel の形（KDoc つきの引数 → `) {`）",
                fixture(
                    "class X(",
                    "    /**",
                    "     * KDoc の中の { や ( は読まない",
                    "     */",
                    "    private val a: Int,",
                    ") {",
                    "    var a1 = 0",
                    "}",
                ),
                listOf("var a1 = 0"),
            ),
            Case(
                "②ApiListeningStreakStore の形（`) : Y {` と副構築子）",
                fixture(
                    "class X(",
                    "    private val a: Int,",
                    ") : Y {",
                    "    constructor(b: Boolean) : this(1)",
                    "    var a2: () -> Unit = {}",
                    "}",
                ),
                listOf("var a2: () -> Unit = {}"),
            ),
            Case(
                "③関数内のローカル var",
                fixture(
                    "class X {",
                    "    suspend fun f() = withContext(d) {",
                    "        var t = 0",
                    "    }",
                    "}",
                ),
                emptyList(),
            ),
            Case(
                "④companion object の var",
                fixture("class X {", "    companion object {", "        var c = 0", "    }", "}"),
                listOf("var c = 0"),
            ),
            Case("⑤private var", fixture("class X {", "    private var p = 0", "}"), emptyList()),
            Case(
                "⑥主構築子の引数の var",
                fixture("class X(", "    var p6: Int,", ")"),
                listOf("var p6: Int,"),
            ),
            Case(
                "⑦本体の無い data object の次の行の関数内の var",
                fixture(
                    "sealed interface S {",
                    "    data object A : S",
                    "    fun g() {",
                    "        var l = 0",
                    "    }",
                    "}",
                ),
                emptyList(),
            ),
            Case(
                "⑧無名の object の中の var",
                fixture("class X {", "    val cb = object : Cb {", "        var o = 0", "    }", "}"),
                emptyList(),
            ),
            Case("⑨interface の var", fixture("interface P {", "    var q: Int", "}"), listOf("var q: Int")),
            Case(
                "⑩AuthViewModel の形（引数のラムダの既定値 `= {}`）",
                fixture(
                    "class X(",
                    "    private val f: suspend () -> Unit = {},",
                    "    private val g: suspend () -> Unit = {},",
                    ") {",
                    "    var a10 = 0",
                    "    val s = MutableStateFlow(0)",
                    "}",
                ),
                listOf("var a10 = 0"),
                listOf("val s = MutableStateFlow(0)"),
            ),
            Case(
                "⑪VocabularyTestViewModel の形（`= { it.shuffled() }`）",
                fixture(
                    "class X(",
                    "    private val shuffle: (List<String>) -> List<String> = { it.shuffled() },",
                    ") {",
                    "    var b = 0",
                    "}",
                ),
                listOf("var b = 0"),
            ),
            Case(
                "⑫SettingsViewModel の形（`= { false }` と複数行の構築子の呼出）",
                fixture(
                    "class X(",
                    "    private val p: () -> Boolean = { false },",
                    "    private val s: Y =",
                    "        Z(a, b),",
                    ") {",
                    "    var c = 0",
                    "}",
                ),
                listOf("var c = 0"),
            ),
            Case(
                "⑬1 行の sealed interface の後は丸括弧の深さ 0 の `}` で打ち切る",
                fixture(
                    "sealed interface S { data object A : S }",
                    "run {",
                    "    var d = 0",
                    "}",
                ),
                emptyList(),
            ),
            Case(
                "private な MutableStateFlow は数えない",
                fixture("class X {", "    private val _s = MutableStateFlow(0)", "}"),
                emptyList(),
            ),
        )

        val failures = cases.mapNotNull { case ->
            val gotVars = lineTexts(case.text, ArchitectureScanner.publicVarLines("fixture.kt", case.text))
            val gotFlows = lineTexts(case.text, ArchitectureScanner.publicMutableStateFlowLines("fixture.kt", case.text))
            if (gotVars == case.vars && gotFlows == case.flows) {
                null
            } else {
                "${case.label}: var 期待=${case.vars} 実際=$gotVars / MutableStateFlow 期待=${case.flows} 実際=$gotFlows"
            }
        }
        assertTrue("宣言の位置の判定が違う:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun 走査器_portの複数行の宣言を1つとして読みKDocを除く() {
        // verifies: CI-06 / T-15
        val text = fixture(
            "interface Api {",
            "    suspend fun fetchPodcasts(",
            "        page: Int,",
            "    ): PodcastResponse",
            "",
            "    suspend fun fetchPodcast(id: String): Result<PodcastResponse>",
            "    suspend fun fetchList(): PodcastResponseList",
            "    /** Episode を返さない。KDoc の型名は数えない */",
            "    fun noModel(): Unit",
            "    val state: Flow<Episode>",
            "    var current: String",
            "}",
        )

        val hits = ArchitectureScanner.portModelTypeHits("fixture.kt", text, setOf("PodcastResponse", "Episode"))

        assertEquals(setOf("fetchPodcasts", "fetchPodcast", "state"), hits)
    }

    @Test
    fun 走査器_portの受け取りと遷移関数の呼出を名前の形で判定する() {
        // verifies: CI-08, CI-09 / T-16
        val text = fixture(
            "val prefs = getPreferencesStore()",
            "val provider = CredentialManagerPasskeyProvider(this)",
            "val store = DataStorePreferencesStore(context)",
            "viewModel.playNext(p)",
            "queue.remove(id)",
            "/**",
            " * PodcastApi と PlayerController はここでは数えない。queue.add(x) も数えない",
            " */",
        )

        assertEquals(
            setOf("PreferencesStore", "transition:remove"),
            ArchitectureScanner.presentationHits("fixture.kt", text),
        )
    }

    @Test
    fun 走査器_testのファイルの層を先頭の語の一致数で決める() {
        // verifies: §3.4 / T-17
        val domainMain = mapOf(
            "RelativeTimeFormatter" to Layer.DOMAIN,
            "ResumeRule" to Layer.DOMAIN,
            "Difficulty" to Layer.DOMAIN,
        )
        val appMain = mapOf(
            "PodcastViewModel" to Layer.APPLICATION,
            "PodcastResponse" to Layer.ADAPTER,
        )
        val uiMain = mapOf("DSFeedback" to Layer.PRESENTATION)
        val rss = mapOf("RssSource" to Layer.ADAPTER, "RssSourceRequest" to Layer.ADAPTER)
        val decoding = mapOf("PodcastResponse" to Layer.ADAPTER, "PodcastListResponse" to Layer.ADAPTER)
        val split = mapOf("FooBaz" to Layer.DOMAIN, "FooQux" to Layer.PRESENTATION)
        val x = mapOf("X" to Layer.DOMAIN)

        fun resolved(layer: Layer) = TestLayer.Resolved(layer)

        assertEquals(resolved(Layer.DOMAIN), ArchitectureScanner.testLayer("RelativeTimeConformanceTest", domainMain))
        assertEquals(resolved(Layer.APPLICATION), ArchitectureScanner.testLayer("PodcastViewModelPortTest", appMain))
        assertEquals(resolved(Layer.PRESENTATION), ArchitectureScanner.testLayer("DSFeedbackTest", uiMain))
        assertEquals(resolved(Layer.ADAPTER), ArchitectureScanner.testLayer("RssSourceTest", rss))
        assertEquals(resolved(Layer.ADAPTER), ArchitectureScanner.testLayer("PodcastDecodingTest", decoding))

        val tie = ArchitectureScanner.testLayer("FooBarTest", split)
        assertTrue("層の違う 2 候補が同じ一致数なら同点: $tie", tie is TestLayer.Tie)
        assertEquals(setOf("FooBaz", "FooQux"), (tie as TestLayer.Tie).candidates.keys)

        assertEquals("同じ package に一致する main が無い", TestLayer.None, ArchitectureScanner.testLayer("UnrelatedTest", domainMain))
        assertEquals("Fake で始まる", TestLayer.None, ArchitectureScanner.testLayer("FakeX", x))
        assertEquals("Stub で始まる", TestLayer.None, ArchitectureScanner.testLayer("StubXTest", x))
        assertEquals("Test で終わらない", TestLayer.None, ArchitectureScanner.testLayer("XHelper", x))
    }

    @Test
    fun 走査器_コメントと文字列を除いて括弧の深さを数え不整合で失敗する() {
        // verifies: §3.3 (1)(2)(3) / T-25
        val balanced = mapOf(
            "①ブロックコメントの中（`*` で始まらない行）の `{`" to fixture(
                "class A {",
                "/*",
                " { not star",
                " { another",
                "*/",
                "    var v = 0",
                "}",
            ),
            "②文字列の中の `{` と `//`" to fixture("class A {", "    val s = \"{ //\"", "    var v = 0", "}"),
            "③テンプレートの中の引用符" to fixture("class A {", "    val t = \"\${f(\"x\")}\"", "    var v = 0", "}"),
            "④行中の `/* } */`" to fixture("class A {", "    val u = 1 /* } */", "    var v = 0", "}"),
            "⑤行末の `// }`" to fixture("class A {", "    val w = 2 // }", "    var v = 0", "}"),
        )
        val failures = balanced.mapNotNull { (label, text) ->
            val got = runCatching { lineTexts(text, ArchitectureScanner.publicVarLines("fixture.kt", text)) }
            if (got.getOrNull() == listOf("var v = 0")) null else "$label: 期待=[var v = 0] 実際=$got"
        }
        assertTrue("深さが変わってはいけない形で読み違えた:\n${failures.joinToString("\n")}", failures.isEmpty())

        val extraClose = assertThrows(IllegalStateException::class.java) {
            ArchitectureScanner.publicVarLines("fixture.kt", fixture("class A {", "}", "}"))
        }
        assertTrue("path と行番号を含む: ${extraClose.message}", extraClose.message.orEmpty().contains("fixture.kt:3"))

        val negativeParen = assertThrows(IllegalStateException::class.java) {
            ArchitectureScanner.publicVarLines("fixture.kt", fixture("class A {", "    val x = 1)", "}"))
        }
        assertTrue("path と行番号を含む: ${negativeParen.message}", negativeParen.message.orEmpty().contains("fixture.kt:2"))

        val unclosed = assertThrows(IllegalStateException::class.java) {
            ArchitectureScanner.publicVarLines("fixture.kt", fixture("class A {", "    fun f() {", "}"))
        }
        assertTrue(
            "path と行番号を含む: ${unclosed.message}",
            Regex("""fixture\.kt:\d+""").containsMatchIn(unclosed.message.orEmpty()),
        )
    }
}

// ----------------------------------------------------------------------------------------------
// 走査器（契約: spec.md §3.3・§3.4・CI-01〜CI-17）。字句だけで読み、型の情報は持たない。
// ----------------------------------------------------------------------------------------------

data class SourceFile(val path: String, val text: String)

/** 違反の 1 組。同じ組に当たる行は `lines` にまとめる。 */
data class Hit(val rule: String, val file: String, val target: String, val lines: List<Int>) {
    val key: Triple<String, String, String> get() = Triple(rule, file, target)
}

sealed interface TestLayer {
    data class Resolved(val layer: Layer) : TestLayer
    data class Tie(val candidates: Map<String, Layer>) : TestLayer
    data object None : TestLayer
}

/** main の最上位の宣言の行を package ごとに持つ（import 先の解決に使う）。 */
class DeclarationIndex internal constructor(
    internal val packages: Set<String>,
    internal val topLevelFiles: Map<String, List<TopLevelFile>>,
)

internal class TopLevelFile(val path: String, val layer: Layer?, val codeLines: List<String>)

object ArchitectureScanner {

    const val UNRESOLVED = "<unresolved>"

    private const val PROJECT = "com.rioikeda.newslisten."

    private class CodeLine(val number: Int, val raw: String, val isComment: Boolean, val code: String)

    private class StructLine(val line: CodeLine, val declPosition: Boolean, val topLevel: Boolean)

    private class HitBuilder {
        private val map = linkedMapOf<Triple<String, String, String>, MutableList<Int>>()

        fun add(rule: String, file: String, target: String, line: Int) {
            map.getOrPut(Triple(rule, file, target)) { mutableListOf() } += line
        }

        fun build(): List<Hit> = map.map { (key, lines) -> Hit(key.first, key.second, key.third, lines.distinct().sorted()) }
    }

    // ---- 走査の根（CI-01） ----

    fun resolveMainSourceRoot(): File = resolveRoot(
        "main",
        listOf("app/src/main/java/com/rioikeda/newslisten", "src/main/java/com/rioikeda/newslisten"),
        "network/ApiClient.kt",
    )

    fun resolveTestSourceRoot(): File = resolveRoot(
        "test",
        listOf("app/src/test/java/com/rioikeda/newslisten", "src/test/java/com/rioikeda/newslisten"),
        "network/NoThrowingDefaultStructureTest.kt",
    )

    private fun resolveRoot(kind: String, candidates: List<String>, marker: String): File {
        val cwd = File(".").canonicalPath
        val root = candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("$kind の走査の根が見つからない（cwd=$cwd）。候補の directory が無い: $candidates")
        check(root.walkTopDown().any { it.isFile && it.extension == "kt" }) {
            "$kind の走査の根 $root に .kt が 1 件も無い（cwd=$cwd。vacuous pass の防止）"
        }
        check(File(root, marker).isFile) { "$kind の走査の根 $root に目印の $marker が無い（cwd=$cwd。vacuous pass の防止）" }
        return root
    }

    fun readSources(root: File): List<SourceFile> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { SourceFile(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        .sortedBy { it.path }
        .toList()

    // ---- 字句の読み方（§3.3 (1)(2)） ----

    private val closedBlockComment = Regex("""/\*.*?\*/""")

    private fun lex(text: String): List<CodeLine> {
        var inBlock = false
        return text.lines().mapIndexed { index, raw ->
            val trimmed = raw.trim()
            val isComment = when {
                inBlock -> {
                    if (raw.contains("*/")) inBlock = false
                    true
                }
                trimmed.startsWith("/*") -> {
                    if (!trimmed.contains("*/")) inBlock = true
                    true
                }
                else -> trimmed.startsWith("//") || trimmed.startsWith("*")
            }
            CodeLine(index + 1, raw, isComment, if (isComment) "" else codeOf(raw))
        }
    }

    /** 文字列の中身・閉じた `/* */`・行末の `//` を除いた、コードの文字列。 */
    private fun codeOf(raw: String): String {
        val outside = StringBuilder()
        var inString = false
        var previous = ' '
        for (c in raw) {
            if (c == '"' && previous != '\\') inString = !inString else if (!inString) outside.append(c)
            previous = c
        }
        return outside.toString().replace(closedBlockComment, "").substringBefore("//")
    }

    // ---- 括弧の深さと宣言の位置（§3.3 (3)） ----

    private val headingWord = Regex("""(?<![\w:.])(class|interface|object)(?!\w)""")

    private val cutWords = listOf(
        "public", "private", "internal", "protected", "open", "abstract", "sealed", "data", "enum", "inline",
        "value", "annotation", "override", "lateinit", "const", "suspend", "operator", "infix", "companion",
        "fun", "val", "var", "class", "interface", "object", "typealias", "init", "constructor",
    )
    private val cutWordRegex = Regex("^(" + cutWords.joinToString("|") + ")(?!\\w)")
    private val anonymousObjectBefore = Regex("""(=|\(|,|\breturn)$""")

    private fun headingStarts(code: String): Set<Int> = headingWord.findAll(code)
        .filterNot { it.groupValues[1] == "object" && anonymousObjectBefore.containsMatchIn(code.substring(0, it.range.first).trimEnd()) }
        .map { it.range.first }
        .toSet()

    private fun structure(path: String, lines: List<CodeLine>): List<StructLine> {
        val stack = ArrayList<Boolean>()
        var depth = 0
        var inHeading = false
        var lastNumber = 0
        val result = ArrayList<StructLine>()
        for (line in lines) {
            if (line.isComment) continue
            lastNumber = line.number
            val trimmed = line.code.trim()
            if (inHeading && depth == 0 &&
                (trimmed.startsWith("@") || trimmed.startsWith("}") || cutWordRegex.containsMatchIn(trimmed))
            ) {
                inHeading = false
            }
            result += StructLine(line, stack.all { it }, stack.isEmpty() && depth == 0)

            val starts = headingStarts(line.code)
            line.code.forEachIndexed { i, c ->
                if (i in starts) inHeading = true
                when (c) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        check(depth >= 0) { "$path:${line.number} 丸括弧の深さが負になった（字句の読み違いか、括弧の不整合）" }
                    }
                    '{' -> {
                        stack += inHeading && depth == 0
                        if (inHeading && depth == 0) inHeading = false
                    }
                    '}' -> {
                        check(stack.isNotEmpty()) { "$path:${line.number} 対応する `{` が無い `}`（字句の読み違いか、括弧の不整合）" }
                        stack.removeAt(stack.size - 1)
                        if (depth == 0) inHeading = false
                    }
                }
            }
        }
        check(stack.isEmpty() && depth == 0) {
            "$path:$lastNumber ファイルの終わりで括弧が閉じていない（波括弧の深さ=${stack.size}、丸括弧の深さ=$depth）"
        }
        return result
    }

    // ---- 公開面（CI-11） ----

    private const val DECLARATION_MODIFIERS =
        """(@[\w.]+(\([^)]*\))?|override|open|abstract|lateinit|public|private|internal|protected|const)"""
    private val varDeclaration = Regex("""^\s*($DECLARATION_MODIFIERS\s+)*var\s""")
    private val valOrVarDeclaration = Regex("""^\s*($DECLARATION_MODIFIERS\s+)*(val|var)\s""")
    private val nonPublicWord = Regex("""\b(private|internal|protected)\b""")

    fun publicVarLines(path: String, text: String): List<Int> = structure(path, lex(text))
        .filter { it.declPosition && varDeclaration.containsMatchIn(it.line.code) && !nonPublicWord.containsMatchIn(it.line.code) }
        .map { it.line.number }

    fun publicMutableStateFlowLines(path: String, text: String): List<Int> = structure(path, lex(text))
        .filter {
            val code = it.line.code
            it.declPosition && valOrVarDeclaration.containsMatchIn(code) &&
                !nonPublicWord.containsMatchIn(code) && code.contains("MutableStateFlow")
        }
        .map { it.line.number }

    private val dataClassHeading = Regex("""\bdata\s+class\s+(\w+)""")
    private val mutableParameterType = Regex("""\b(MutableList|MutableMap|MutableSet)\b|Array<|\b\w*Array\b""")
    private val parameterProperty = Regex("""\b(?:val|var)\s+(\w+)""")

    /** `(data class <型名>.<property>, 行)`。主構築子の引数に `var` か可変の型があるもの。 */
    private fun mutableDataClassParameters(text: String): List<Pair<String, Int>> {
        val code = lex(text).joinToString("\n") { it.code }
        return dataClassHeading.findAll(code).toList().flatMap { match ->
            val open = code.indexOf('(', match.range.last)
            if (open < 0) return@flatMap emptyList<Pair<String, Int>>()
            val line = code.substring(0, match.range.first).count { it == '\n' } + 1
            splitParameters(code, open).filter { it.contains(Regex("""\bvar\b""")) || mutableParameterType.containsMatchIn(it) }
                .map { parameter ->
                    val property = parameterProperty.find(parameter)?.groupValues?.get(1) ?: parameter.trim()
                    "data class ${match.groupValues[1]}.$property" to line
                }
        }
    }

    private fun splitParameters(code: String, open: Int): List<String> {
        val parameters = mutableListOf<String>()
        val current = StringBuilder()
        var parens = 1
        var angles = 0
        var i = open + 1
        while (i < code.length && parens > 0) {
            val c = code[i]
            when {
                c == '(' -> parens++
                c == ')' -> parens--
                c == '<' -> angles++
                c == '>' && code[i - 1] != '-' -> angles--
            }
            if (parens == 0) break
            if (c == ',' && parens == 1 && angles == 0) {
                parameters += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) parameters += current.toString()
        return parameters
    }

    fun publicSurfaceHits(sources: List<SourceFile>, layerOf: Map<String, Layer>): List<Hit> {
        val hits = HitBuilder()
        for (source in sources) {
            val layer = layerOf[source.path]
            if (layer in listOf(Layer.DOMAIN, Layer.APPLICATION, Layer.PORT, Layer.READ_MODEL)) {
                publicVarLines(source.path, source.text).forEach { hits.add("android-dep-07", source.path, "公開の var", it) }
                publicMutableStateFlowLines(source.path, source.text)
                    .forEach { hits.add("android-dep-07", source.path, "公開の MutableStateFlow", it) }
            }
            if (layer == Layer.DOMAIN || layer == Layer.READ_MODEL) {
                mutableDataClassParameters(source.text).forEach { (target, line) -> hits.add("android-check-05", source.path, target, line) }
            }
        }
        return hits.build()
    }

    // ---- import と宣言するファイルへの解決（§3.3 (4)） ----

    private fun importLines(text: String): List<Pair<Int, String>> = lex(text)
        .filter { !it.isComment && it.code.startsWith("import ") }
        .map { it.number to it.code.removePrefix("import ").trim().replace(Regex("""\s+as\s+\w+$"""), "") }

    fun importNames(path: String, text: String): List<String> = importLines(text).map { it.second }

    private fun declarationRegex(name: String) = Regex(
        """^\s*((@[\w.]+(\([^)]*\))?|public|internal|private|protected|sealed|data|enum|abstract|open|inline|value|""" +
            """annotation|const|suspend|operator|infix|fun|expect|actual)\s+)*(class|interface|object|typealias|fun|val|var)\s+""" +
            """(<[^>]*>\s*)?([\w.]+(<[^>]*>)?\??\.)?""" + Regex.escape(name) + """\b""",
    )

    fun declarationIndex(sources: List<SourceFile>, layers: Map<String, Layer>): DeclarationIndex {
        val packageLine = Regex("""^\s*package\s+([\w.]+)""")
        val byPackage = linkedMapOf<String, MutableList<TopLevelFile>>()
        for (source in sources) {
            val lines = structure(source.path, lex(source.text))
            val packageName = lines.firstNotNullOfOrNull { packageLine.find(it.line.code)?.groupValues?.get(1) }
                ?: error("${source.path}: package 行が無い")
            byPackage.getOrPut(packageName) { mutableListOf() } +=
                TopLevelFile(source.path, layers[source.path], lines.filter { it.topLevel }.map { it.line.code })
        }
        return DeclarationIndex(byPackage.keys.toSet(), byPackage)
    }

    private fun externalGroup(name: String, forTestV8: Boolean): String? = when {
        name.startsWith("android.") -> "android"
        name.startsWith("androidx.") -> "androidx"
        name.startsWith("kotlinx.coroutines.") -> "kotlinx.coroutines"
        name.startsWith("kotlinx.serialization.") -> "kotlinx.serialization"
        name.startsWith("okhttp3.mockwebserver.") -> if (forTestV8) "okhttp3.mockwebserver" else "okhttp3"
        name.startsWith("okhttp3.") -> "okhttp3"
        else -> null
    }

    /** import 先の対象。`null` は対象なし（`java.`・`kotlin.` など）、[UNRESOLVED] は project の宣言を決められない。 */
    fun importTarget(name: String, index: DeclarationIndex, forTestV8: Boolean = false, forApplication: Boolean = false): String? {
        externalGroup(name, forTestV8)?.let { return it }
        if (!name.startsWith(PROJECT)) return null
        val rest = name.removePrefix(PROJECT)
        return when {
            rest.startsWith("model.") -> "model"
            rest.startsWith("network.") -> "network"
            rest.startsWith("di.") -> "di"
            rest == "R" || rest.startsWith("R.") -> "R"
            rest == "BuildConfig" || rest.startsWith("BuildConfig.") -> "BuildConfig"
            else -> resolveLayer(name, index, forApplication)
        }
    }

    private fun resolveLayer(name: String, index: DeclarationIndex, forApplication: Boolean): String {
        val packageName = index.packages.filter { name.startsWith("$it.") }.maxByOrNull { it.length } ?: return UNRESOLVED
        val declared = name.removePrefix("$packageName.").substringBefore('.')
        val regex = declarationRegex(declared)
        val candidates = index.topLevelFiles.getValue(packageName).filter { file -> file.codeLines.any { regex.containsMatchIn(it) } }
        val layers = candidates.map { it.layer }.distinct()
        if (layers.size != 1 || layers.single() == null) return UNRESOLVED
        if (forApplication && candidates.any { it.path.contains("/infra/") }) return "infra"
        return "layer:${layers.single()!!.name}"
    }

    // ---- 依存の向き（CI-05。android-dep-01・android-dep-03・android-dep-04・android-dep-05） ----

    private val domainForbidden = setOf(
        "android", "androidx", "kotlinx.coroutines", "kotlinx.serialization", "okhttp3", "model", "network", "di", "R",
        "layer:APPLICATION", "layer:PORT", "layer:READ_MODEL", "layer:PRESENTATION",
    )
    private val applicationForbidden = setOf("network", "model", "android", "androidx", "infra", "layer:PRESENTATION")
    private val presentationForbidden = setOf("network", "model", "layer:ADAPTER")
    private val adapterForbidden = setOf("layer:APPLICATION", "layer:READ_MODEL", "layer:PRESENTATION", "layer:ENTRY")

    private const val MAIN_ACTIVITY = "MainActivity.kt"

    fun dependencyHits(sources: List<SourceFile>, layerOf: Map<String, Layer>): List<Hit> {
        val index = declarationIndex(sources, layerOf)
        val hits = HitBuilder()
        val unresolved = mutableListOf<String>()
        for (source in sources) {
            val layer = layerOf[source.path] ?: continue
            val (rule, forbidden) = when {
                layer == Layer.DOMAIN -> "android-dep-01" to domainForbidden
                layer in listOf(Layer.APPLICATION, Layer.PORT, Layer.READ_MODEL) -> "android-dep-03" to applicationForbidden
                layer == Layer.PRESENTATION || source.path == MAIN_ACTIVITY -> "android-dep-04" to presentationForbidden
                layer == Layer.ADAPTER -> "android-dep-05" to adapterForbidden
                else -> continue
            }
            for ((line, name) in importLines(source.text)) {
                val target = importTarget(name, index, forApplication = rule == "android-dep-03")
                when {
                    target == UNRESOLVED -> unresolved += "${source.path}:$line $name"
                    target in forbidden -> hits.add(rule, source.path, target!!, line)
                }
            }
            if (rule == "android-dep-04") {
                presentationLineHits(source.text).forEach { (target, line) -> hits.add("android-dep-04", source.path, target, line) }
            }
        }
        check(unresolved.isEmpty()) { "解決できない import（宣言するファイルを決められない）:\n${unresolved.joinToString("\n")}" }
        return hits.build()
    }

    // ---- port の受け取りと遷移関数の呼出（CI-08・CI-09） ----

    private val portNames = listOf(
        "PlayerController", "PasskeyProvider", "PreferencesStore", "PodcastApi",
        "LearningApi", "VocabularyTestApi", "SessionStore", "NetworkMonitoring",
    )
    private val portRegexes = portNames.associateWith { Regex("""(?<![A-Za-z0-9_])(get)?$it\b""") }
    private const val PREFERENCES_SETTER = "preferencesStore.set"
    private val transitionFunctions = listOf(
        "start", "setQueue", "add", "playNext", "jump", "advance", "remove", "moveUpNext",
        "retry", "playNow", "playerStarted", "playerEnded", "fail", "queueExhausted",
    )
    private val transitionCall = Regex(
        """(?:\b(?:PlaybackQueue|PlaybackSession)(?:<[^>]*>)?(?:\([^)]*\))?|\b\w*(?:queue|Queue|session|Session))""" +
            """(?:\.value)?\.(${transitionFunctions.joinToString("|")})\(""",
    )

    private fun presentationLineHits(text: String): List<Pair<String, Int>> = lex(text).filter { !it.isComment }.flatMap { line ->
        val code = line.code
        portRegexes.filterValues { it.containsMatchIn(code) }.keys.map { it to line.number } +
            (if (code.contains(PREFERENCES_SETTER)) listOf(PREFERENCES_SETTER to line.number) else emptyList()) +
            transitionCall.findAll(code).map { "transition:${it.groupValues[1]}" to line.number }.toList()
    }

    fun presentationHits(path: String, text: String): Set<String> = presentationLineHits(text).map { it.first }.toSet()

    // ---- データモデルの漏れ（CI-06。android-dep-02・android-check-02） ----

    private val topLevelTypeName = Regex("""(?<![\w.])(?:class|interface|object|typealias)\s+(\w+)""")
    private val functionDeclaration = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
    private val propertyDeclaration = Regex(
        """^\s*(?:(?:override|private|public|internal|abstract|open|suspend|inline|const|lateinit)\s+)*""" +
            """(?:val|var)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)""",
    )

    private class Declaration(val name: String, val line: Int, val text: String)

    /** port の宣言。`fun` は丸括弧が閉じる行の末尾まで（複数行を許す）、`val`・`var` は 1 行。 */
    private fun declarations(lines: List<CodeLine>): List<Declaration> {
        val result = mutableListOf<Declaration>()
        val code = lines.filter { !it.isComment }
        var i = 0
        while (i < code.size) {
            val line = code[i]
            val function = functionDeclaration.find(line.code)
            val property = propertyDeclaration.find(line.code)
            when {
                function != null -> {
                    val text = StringBuilder(line.code)
                    var depth = line.code.count { it == '(' } - line.code.count { it == ')' }
                    while (depth > 0 && i + 1 < code.size) {
                        i++
                        text.append('\n').append(code[i].code)
                        depth += code[i].code.count { it == '(' } - code[i].code.count { it == ')' }
                    }
                    result += Declaration(function.groupValues[1], line.number, text.toString())
                }
                property != null -> result += Declaration(property.groupValues[1], line.number, line.code)
            }
            i++
        }
        return result
    }

    /** port の宣言に、`model/` の型の名前が語として現れるもの（名前 → 行）。 */
    private fun portModelDeclarations(text: String, modelTypes: Set<String>): Map<String, Int> {
        val typeRegexes = modelTypes.map { Regex("""\b${Regex.escape(it)}\b""") }
        return declarations(lex(text))
            .filter { declaration -> typeRegexes.any { it.containsMatchIn(declaration.text) } }
            .associate { it.name to it.line }
    }

    fun portModelTypeHits(path: String, text: String, modelTypes: Set<String>): Set<String> =
        portModelDeclarations(text, modelTypes).keys

    fun dataModelHits(sources: List<SourceFile>, layerOf: Map<String, Layer>): List<Hit> {
        val modelTypes = sources.filter { it.path.startsWith("model/") }.flatMap { source ->
            structure(source.path, lex(source.text)).filter { it.topLevel }
                .flatMap { line -> topLevelTypeName.findAll(line.line.code).map { it.groupValues[1] }.toList() }
        }.toSet()
        check(modelTypes.isNotEmpty()) { "model/ の型の名前が 0 件（vacuous pass の防止）" }

        val hits = HitBuilder()
        for (source in sources) {
            val path = source.path
            if (path.startsWith("model/")) {
                for ((line, name) in importLines(source.text)) {
                    modelImportTarget(name)?.let { hits.add("android-dep-02", path, it, line) }
                }
            } else if (!path.startsWith("network/") && !path.startsWith("observability/")) {
                importLines(source.text).filter { it.second.startsWith("${PROJECT}model.") }
                    .forEach { hits.add("android-dep-02", path, "model", it.first) }
                lex(source.text).filter { !it.isComment && !it.code.startsWith("import ") && it.code.contains("${PROJECT}model.") }
                    .forEach { hits.add("android-dep-02", path, "model", it.number) }
            }
            if (layerOf[path] == Layer.PORT) {
                portModelDeclarations(source.text, modelTypes).forEach { (name, line) -> hits.add("android-check-02", path, name, line) }
            }
        }
        return hits.build()
    }

    private fun modelImportTarget(name: String): String? = when {
        name.startsWith("android.") -> "android"
        name.startsWith("androidx.") -> "androidx"
        name.startsWith(PROJECT) -> name.removePrefix(PROJECT).let { if (it == "R" || it.startsWith("R.")) "R" else it }
        else -> null
    }

    // ---- composition root（CI-07。android-dep-06） ----

    private val adapterConstructors = listOf(
        "OkHttpApiClient", "KeystoreSessionStore", "DataStorePreferencesStore", "ExoPlayerController",
        "AudioCacheManager", "ConnectivityNetworkMonitor", "JavaFileSystem", "CredentialManagerPasskeyProvider",
    )
    private val constructorCalls = adapterConstructors.associateWith { Regex("""\b$it\(""") }
    private const val APP_CONTAINER = "di/AppContainer.kt"

    fun compositionHits(sources: List<SourceFile>): List<Hit> {
        check(sources.any { it.path == APP_CONTAINER }) { "$APP_CONTAINER が main に無い" }
        val hits = HitBuilder()
        for (source in sources) {
            val path = source.path
            for (line in lex(source.text).filter { !it.isComment }) {
                val code = line.code
                if (!path.startsWith("di/") && listOf("class ", "fun ", "import ").none { code.contains(it) }) {
                    constructorCalls.filterValues { it.containsMatchIn(code) }.keys
                        .forEach { hits.add("android-dep-06", path, it, line.number) }
                }
                if (LayerMap.byFile[path] != Layer.ENTRY && !code.contains("fun ") && code.contains("getAppContainer()")) {
                    hits.add("android-dep-06", path, "getAppContainer()", line.number)
                }
                if (path == APP_CONTAINER && line.raw.contains("== \"")) hits.add("android-dep-06", path, "== \"", line.number)
            }
        }
        return hits.build()
    }

    // ---- 規則の置き場（CI-10。android-check-04・android-dep-09） ----

    private val statusExpressions = listOf("\"processing\"", "\"failed\"", "\"partial_failed\"", "\"completed\"")
    private val otherExpressions = listOf(
        "role ==", "PLAYBACK_SPEEDS", "listOf(3, 5, 7, 10)", "setOf(3, 5, 7, 10)", "/ 7.0", "/ 31.0",
        "correctRate >=", "limit == 0", "moveUpNext(index",
    )
    private val errorCode = Regex("""\be\.code\b""")

    fun ruleLocationHits(sources: List<SourceFile>): List<Hit> {
        val hits = HitBuilder()
        for (source in sources) {
            val path = source.path
            val inNetwork = path.startsWith("network/")
            val checkV4 = LayerMap.byFile[path] != Layer.DOMAIN
            source.text.lines().forEachIndexed { index, raw ->
                val number = index + 1
                if (checkV4) {
                    otherExpressions.filter { raw.contains(it) }.forEach { hits.add("android-check-04", path, it, number) }
                    if (!inNetwork) statusExpressions.filter { raw.contains(it) }.forEach { hits.add("android-check-04", path, it, number) }
                }
                if (!inNetwork) {
                    if (errorCode.containsMatchIn(raw)) hits.add("android-dep-09", path, "e.code", number)
                    if (raw.contains("= e.message")) hits.add("android-dep-09", path, "= e.message", number)
                }
            }
        }
        return hits.build()
    }

    // ---- 既存契約の回帰（CI-16。android-check-09） ----

    private fun linesContaining(sources: List<SourceFile>, needle: String): List<String> = sources.flatMap { source ->
        source.text.lines().mapIndexedNotNull { index, raw -> if (raw.contains(needle)) "${source.path}:${index + 1}" else null }
    }

    fun errorCallLines(sources: List<SourceFile>): List<String> = linesContaining(sources, "error(\"")

    fun code401Lines(sources: List<SourceFile>): List<String> = linesContaining(sources, "code == 401")

    // ---- 対象が現れるまで skip する検査（CI-12〜CI-14） ----

    fun topLevelDeclarationFile(sources: List<SourceFile>, name: String): String? {
        val declaration = Regex("""(?<![\w.])(?:class|interface|object|typealias)\s+${Regex.escape(name)}\b""")
        return sources.firstOrNull { source ->
            structure(source.path, lex(source.text)).any { it.topLevel && declaration.containsMatchIn(it.line.code) }
        }?.path
    }

    fun nonPublicConstructorViolations(sources: List<SourceFile>, types: List<String>): List<String> = types.filter { type ->
        val path = topLevelDeclarationFile(sources, type) ?: return@filter false
        val code = lex(sources.first { it.path == path }.text).joinToString("\n") { it.code }
        !Regex("""\bclass\s+${Regex.escape(type)}\b(<[^>]*>)?\s*(private|internal)\s+constructor\b""").containsMatchIn(code)
    }

    private val queriesOrCommands = Regex("""\binterface\s+(\w+(?:Queries|Commands))\b""")

    fun queriesCommandsInterfaces(sources: List<SourceFile>): List<String> = sources.flatMap { source ->
        structure(source.path, lex(source.text)).filter { it.topLevel }
            .mapNotNull { queriesOrCommands.find(it.line.code)?.let { match -> "${source.path}:${match.groupValues[1]}" } }
    }

    private fun returnTypeOf(declarationText: String): String {
        val open = declarationText.indexOf('(')
        var depth = 0
        var close = -1
        for (i in open until declarationText.length) {
            if (declarationText[i] == '(') depth++
            if (declarationText[i] == ')' && --depth == 0) {
                close = i
                break
            }
        }
        return Regex("""^\s*:\s*([^={]+)""").find(declarationText.substring(close + 1))?.groupValues?.get(1)?.trim() ?: "Unit"
    }

    /** `*Queries` の関数は `Unit` を返さない。`*Commands` の関数は `Unit` を返す（receipt の型の集合は android-slice-playapp-01 が足す）。 */
    fun queriesCommandsViolations(sources: List<SourceFile>): List<String> = sources.flatMap { source ->
        val lines = lex(source.text)
        val ownerAt = mutableMapOf<Int, String?>()
        var owner: String? = null
        for (line in structure(source.path, lines)) {
            if (line.topLevel) owner = queriesOrCommands.find(line.line.code)?.groupValues?.get(1)
            ownerAt[line.line.number] = owner
        }
        declarations(lines).filter { functionDeclaration.containsMatchIn(it.text) }.mapNotNull { declaration ->
            val interfaceName = ownerAt[declaration.line] ?: return@mapNotNull null
            val returnsUnit = returnTypeOf(declaration.text) == "Unit"
            val violates = if (interfaceName.endsWith("Queries")) returnsUnit else !returnsUnit
            if (violates) "${source.path}:${declaration.line} $interfaceName.${declaration.name}" else null
        }
    }

    // ---- test の層と依存（CI-15。§3.4・android-check-08） ----

    private val wordSplit = Regex("""[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+""")
    private val testNameExcluded = listOf("Fake", "Stub", "Base")

    private fun words(name: String): List<String> = wordSplit.findAll(name).map { it.value }.toList()

    fun testLayer(testName: String, mainByName: Map<String, Layer>): TestLayer {
        if (!testName.endsWith("Test") || testNameExcluded.any { testName.startsWith(it) }) return TestLayer.None
        val testWords = words(testName)
        val matches = mainByName.mapValues { (name, _) ->
            words(name).zip(testWords).takeWhile { (main, test) -> main == test }.size
        }
        val best = matches.values.maxOrNull() ?: 0
        if (best == 0) return TestLayer.None
        val candidates = mainByName.filterKeys { matches.getValue(it) == best }
        return candidates.values.distinct().singleOrNull()?.let { TestLayer.Resolved(it) } ?: TestLayer.Tie(candidates)
    }

    private fun directoryOf(path: String) = path.substringBeforeLast('/', "")

    fun testLayers(testSources: List<SourceFile>, layerOf: Map<String, Layer>): Map<String, TestLayer> {
        val mainByDirectory = layerOf.entries.groupBy({ directoryOf(it.key) }) {
            it.key.substringAfterLast('/').removeSuffix(".kt") to it.value
        }.mapValues { it.value.toMap() }
        return testSources.associate { source ->
            source.path to testLayer(
                source.path.substringAfterLast('/').removeSuffix(".kt"),
                mainByDirectory[directoryOf(source.path)].orEmpty(),
            )
        }
    }

    fun testDependencyHits(testSources: List<SourceFile>, layerOf: Map<String, Layer>): List<Hit> {
        val domainSide = listOf(Layer.DOMAIN, Layer.APPLICATION, Layer.PORT, Layer.READ_MODEL)
        val forbidden = setOf("android", "androidx", "okhttp3.mockwebserver")
        val hits = HitBuilder()
        for ((path, layer) in testLayers(testSources, layerOf)) {
            if (layer !is TestLayer.Resolved || layer.layer !in domainSide) continue
            val source = testSources.first { it.path == path }
            for ((line, name) in importLines(source.text)) {
                externalGroup(name, forTestV8 = true)?.takeIf { it in forbidden }?.let { hits.add("android-check-08", path, it, line) }
            }
        }
        return hits.build()
    }
}

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.feed.FeedViewModel
import com.rioikeda.newslisten.network.ApiException
import java.net.URL
import org.junit.Assert.fail

// 検査器のテストが共有する道具。検査器そのもの（enumerate・runner・各検査）はここに置かない。

/** 本番の母集団（main の class の根と `LayerMap.byFile`）。 */
internal object ProductionWorld {
    const val PACKAGE_PREFIX = PROJECT_PACKAGE

    val classRoot: URL = FeedViewModel::class.java.protectionDomain!!.codeSource!!.location
    val loader: ClassLoader = FeedViewModel::class.java.classLoader!!

    val population: Population by lazy {
        OperationEnumerator.enumerate(classRoot, PACKAGE_PREFIX, LayerMap.byFile, loader)
    }

    val context: ProbeContext
        get() = ProbeContext(population, OperationLedger.rows, EffectTypeCatalog.production)
}

/**
 * fixture 1 パッケージ分の世界。`packageName` を接頭辞に、`files`（パッケージからの相対 path）を APPLICATION の
 * 母集団ファイルとして、本番と同じ [OperationEnumerator.enumerate] に通す。
 */
internal class FixtureWorld(
    private val anchor: Class<*>,
    val packageName: String,
    val files: List<String>,
    val rows: List<LedgerRow>,
    val catalog: EffectTypeCatalog = EffectTypeCatalog.production,
) {
    val classRoot: URL = anchor.protectionDomain!!.codeSource!!.location
    val loader: ClassLoader = anchor.classLoader!!
    val layerOf: Map<String, Layer> = files.associateWith { Layer.APPLICATION }

    val population: Population by lazy {
        OperationEnumerator.enumerate(classRoot, packageName, layerOf, loader)
    }

    val context: ProbeContext get() = ProbeContext(population, rows, catalog)

    fun input(
        probeSets: List<QueryProbeSet>,
        violations: List<MigrationViolation> = emptyList(),
        known: List<KnownViolation> = emptyList(),
    ): CheckInput = CheckInput(
        classRoot = classRoot,
        packagePrefix = packageName,
        layerOf = layerOf,
        loader = loader,
        ledger = rows,
        catalog = catalog,
        probeSets = probeSets,
        violations = violations,
        knownViolations = known,
        sliceIds = SliceIds.all,
    )

    fun withRows(newRows: List<LedgerRow>): FixtureWorld =
        FixtureWorld(anchor, packageName, files, newRows, catalog)

}

// ---- 台帳の行 ----

internal fun probeQuery(id: String, signatures: Set<String>? = null, aliases: Set<String> = emptySet()): LedgerRow =
    LedgerRow(id, OperationKind.QUERY, QueryEvidence.PROBE, aliases, signatures, reason = "fixture")

internal fun structuralQuery(id: String): LedgerRow =
    LedgerRow(id, OperationKind.QUERY, QueryEvidence.STRUCTURAL, reason = "fixture")

internal fun commandRow(id: String): LedgerRow =
    LedgerRow(id, OperationKind.COMMAND, reason = "fixture")

// ---- scenario ----

internal const val NO_COMMAND_REASON = "fixture: command を持たない"

/** scenario A（読み成功）。warm-up を渡さなければ、command を持たない理由を添える。 */
internal fun scenarioA(
    canned: Map<String, List<Any?>> = emptyMap(),
    initialProperties: Map<String, Any?> = emptyMap(),
    warmUp: (suspend (Any, ProbeEnvironment) -> Unit)? = null,
    name: String = "A",
): ProbeScenario = ProbeScenario(
    name = name,
    canned = canned,
    initialProperties = initialProperties,
    warmUp = warmUp,
    noWarmUpReason = if (warmUp == null) NO_COMMAND_REASON else null,
)

/** scenario B（ApiClient 系の列の読みが `ApiException.HttpError(500)`）。 */
internal fun scenarioB(): ProbeScenario = ProbeScenario(
    name = "B",
    readFailure = ApiException.HttpError(500),
    noWarmUpReason = NO_COMMAND_REASON,
)

// ---- 失敗の照合 ----

internal fun List<Finding>.render(): String = if (isEmpty()) "(なし)" else joinToString("\n") { "  $it" }

/** 見出しが `heading` で、subject と detail の合わせた文字列が `parts` をすべて含む失敗を 1 件返す。無ければ失敗。 */
internal fun List<Finding>.requireFinding(heading: String, vararg parts: String): Finding =
    firstOrNull { f -> f.heading == heading && parts.all { (f.subject + " " + f.detail).contains(it) } }
        ?: throw AssertionError("見出し「$heading」で ${parts.toList()} を名指しする失敗が無い。実際:\n${render()}")

internal fun List<Finding>.withHeading(heading: String): List<Finding> = filter { it.heading == heading }

/** [ProbeRunFailure] の message が見出しと名指しの部品をすべて含むことを確かめる。 */
internal fun assertFailureMessage(failure: ProbeRunFailure, vararg parts: String) {
    val message = failure.message.orEmpty()
    val missing = parts.filterNot { message.contains(it, ignoreCase = true) }
    if (missing.isNotEmpty()) fail("ProbeRunFailure の message が $missing を含まない:\n$message")
}

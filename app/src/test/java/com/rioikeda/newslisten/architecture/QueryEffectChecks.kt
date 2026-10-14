package com.rioikeda.newslisten.architecture

import java.net.URL

/** 検査の入力一式。本番と fixture が同じ関数 [QueryEffectChecks.execute] に通す。 */
data class CheckInput(
    val classRoot: URL,
    val packagePrefix: String,
    val layerOf: Map<String, Layer>,
    val loader: ClassLoader,
    val ledger: List<LedgerRow>,
    val catalog: EffectTypeCatalog,
    val probeSets: List<QueryProbeSet>,
    val violations: List<MigrationViolation>,
    val knownViolations: List<KnownViolation>,
    val sliceIds: Set<String>,
)

data class CheckOutcome(
    val findings: List<Finding>,
    val population: Population?,
    /** `probeSets` と同じ順の `run` の返り値（`run` が失敗した probe set は含まない）。 */
    val results: List<ProbeRunResult>,
) {
    val observed: List<ObservedWrite> get() = results.flatMap { it.observed }

    /** 全 probe set の `run` の返り値を 1 つにまとめたもの（要約の入力）。 */
    fun combinedResult(): ProbeRunResult = ProbeRunResult(
        observed = results.flatMap { it.observed },
        resolutions = results.flatMap { it.resolutions },
        traces = results.flatMap { it.traces },
        backgroundChanges = results.flatMap { it.backgroundChanges },
    )
}

object QueryEffectChecks {
    /**
     * 本番の選別（enumerate）から実行時の検査（run）、違反の表との一致、既知の違反の観測までを通す。
     * fixture の RED / green も、この 1 つの経路で確かめる（違反の表は渡した値だけを使う）。
     */
    fun execute(input: CheckInput): CheckOutcome {
        val population = try {
            OperationEnumerator.enumerate(input.classRoot, input.packagePrefix, input.layerOf, input.loader)
        } catch (e: EnumerationFailure) {
            return CheckOutcome(listOf(Finding(Headings.ENUMERATION, "列挙", e.message.orEmpty())), null, emptyList())
        }

        val findings = mutableListOf<Finding>()
        findings += LedgerChecks.reconcile(population, input.ledger)
        findings += CatalogVerifier.verify(population, input.catalog)
        findings += LedgerChecks.classification(population, input.ledger, input.catalog)

        val context = ProbeContext(population, input.ledger, input.catalog)
        findings += ProbeCoverage.check(input.probeSets, context)
        findings += ProbeCoverage.rowsWithoutProbeSet(input.probeSets, context)

        val results = mutableListOf<ProbeRunResult>()
        for (set in input.probeSets) {
            try {
                results += QueryProbeRunner.run(set, context)
            } catch (e: ProbeRunFailure) {
                findings += Finding(Headings.PROBE_FAILURE, set.owner, e.message.orEmpty())
            }
        }

        val combined = CheckOutcome(findings, population, results)
        findings += ViolationChecks.compare(
            combined.observed, results.flatMap { it.backgroundChanges }, input.violations, input.ledger, population, input.sliceIds,
        )
        findings += ViolationChecks.knownObserved(input.knownViolations, input.violations, combined.observed, input.sliceIds)
        return CheckOutcome(findings, population, results)
    }
}

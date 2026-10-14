package com.rioikeda.newslisten.architecture

/** 解決した呼び方 1 つ。`row` は台帳の行、`operation` は所有者の型が宣言する公開操作（runner が reflection で呼ぶ `Method`）。 */
internal class ResolvedTarget(
    val row: LedgerRow,
    val signature: String,
    val operation: PublicOperation,
    val args: (ProbeEnvironment) -> List<Any?>,
    val source: ResolutionSource,
)

/** `(行, signature)` ごとの呼び方の解決（作者の登録と、getter の既定生成）。 */
internal object CallResolution {

    fun isProbeRow(row: LedgerRow): Boolean = row.kind == OperationKind.QUERY && row.evidence == QueryEvidence.PROBE

    fun resolve(set: QueryProbeSet, context: ProbeContext): Pair<List<ResolvedTarget>, List<Finding>> {
        val findings = mutableListOf<Finding>()
        val ownerOperations = context.population.operations.filter { it.typeName == set.owner }
        if (ownerOperations.isEmpty()) {
            findings += Finding(Headings.SURPLUS_PROBE, set.owner, "所有者の型 ${set.owner} が母集団に無い")
            return emptyList<ResolvedTarget>() to findings
        }

        // 所有者の型が宣言する PROBE 行の (行, signature)
        val required = linkedMapOf<Pair<String, String>, Pair<LedgerRow, PublicOperation>>()
        for (operation in ownerOperations) {
            val row = LedgerChecks.rowsOf(operation.id, context.ledger).firstOrNull() ?: continue
            if (isProbeRow(row)) required.putIfAbsent(row.id to operation.signature, row to operation)
        }

        // 作者の呼び方を (行, signature) に振り分ける
        val authored = linkedMapOf<Pair<String, String>, MutableList<CallSpec>>()
        for (call in set.calls) {
            val row = LedgerChecks.rowsOf(call.operationId, context.ledger).firstOrNull()
            if (row == null) {
                findings += Finding(Headings.SURPLUS_PROBE, call.operationId, "台帳に実在しない行を指す呼び方（所有者 ${set.owner}）")
                continue
            }
            val rowSignatures = LedgerChecks.operationsOf(row, context.population).map { it.signature }.toSet()
            val signature = call.signature ?: if (rowSignatures.size == 1) rowSignatures.single() else {
                findings += Finding(
                    Headings.OMITTED_SIGNATURE, row.id,
                    "signature を省けるのは行の signature が 1 つのときだけ（実在: ${rowSignatures.sorted().joinToString()}）",
                )
                continue
            }
            when {
                signature !in rowSignatures -> findings += Finding(
                    Headings.SURPLUS_PROBE, row.id, "実在しない signature $signature を指す呼び方（実在: ${rowSignatures.sorted().joinToString()}）",
                )
                !isProbeRow(row) -> findings += Finding(Headings.SURPLUS_PROBE, row.id, "PROBE の QUERY 行でない行を指す呼び方")
                (row.id to signature) !in required -> findings += Finding(
                    Headings.SURPLUS_PROBE, row.id, "所有者 ${set.owner} の型が宣言していない signature $signature を指す呼び方",
                )
                else -> authored.getOrPut(row.id to signature) { mutableListOf() }.add(call)
            }
        }

        val targets = mutableListOf<ResolvedTarget>()
        val missing = linkedMapOf<String, MutableList<String>>()
        for ((key, pair) in required) {
            val (row, operation) = pair
            val calls = authored[key].orEmpty()
            when {
                calls.size > 1 -> findings += Finding(
                    Headings.OVERLAPPING_CALL, row.id, "signature ${key.second} に作者の呼び方が ${calls.size} 個ある（高々 1 つ）",
                )
                calls.size == 1 -> targets += ResolvedTarget(row, key.second, operation, calls.single().args, ResolutionSource.AUTHOR)
                LedgerChecks.isGetter(operation) -> targets += ResolvedTarget(row, key.second, operation, { emptyList() }, ResolutionSource.DEFAULT)
                else -> missing.getOrPut(row.id) { mutableListOf() }.add(key.second)
            }
        }
        missing.forEach { (id, signatures) ->
            findings += Finding(Headings.MISSING_CALL, id, "呼び方が無い signature: ${signatures.joinToString()}")
        }
        return targets.sortedWith(compareBy({ it.row.id }, { it.signature })) to findings
    }
}

/** 呼び方の解決と scenario の条件の網羅の検査。登録 list の全 probe set に当てる。 */
object ProbeCoverage {

    fun check(probeSets: List<QueryProbeSet>, context: ProbeContext): List<Finding> {
        val classifier = TypeClassifier(context.population, context.catalog)
        return probeSets.flatMap { set ->
            CallResolution.resolve(set, context).second + scenarioConditions(set, context, classifier)
        }
    }

    private fun scenarioConditions(set: QueryProbeSet, context: ProbeContext, classifier: TypeClassifier): List<Finding> {
        val findings = mutableListOf<Finding>()
        val ownerOperations = context.population.operations.filter { it.typeName == set.owner }
        val names = set.scenarios.map { it.name }
        if ("A" !in names) {
            findings += Finding(Headings.MISSING_SCENARIO, set.owner, "scenario A（読み成功）が無い")
        }
        val ownerClass = ownerOperations.firstOrNull()?.method?.declaringClass
        if (ownerClass != null && hasApiPort(ownerClass, classifier) && "B" !in names) {
            findings += Finding(
                Headings.MISSING_SCENARIO, set.owner, "scenario B（ApiClient 系の列の読みの失敗）が無い。グラフに ApiClient 系の port がある",
            )
        }
        val hasCommand = ownerOperations.any { op ->
            LedgerChecks.rowsOf(op.id, context.ledger).any { it.kind == OperationKind.COMMAND }
        }
        if (hasCommand) {
            val unexplained = set.scenarios.filter { it.warmUp == null && it.noWarmUpReason.isNullOrBlank() }
            if (unexplained.isNotEmpty()) {
                findings += Finding(
                    Headings.MISSING_SCENARIO, set.owner,
                    "所有者に COMMAND の行があるので warm-up か理由が要る（scenario ${unexplained.joinToString { it.name }}）",
                )
            }
        }
        return findings
    }

    /** 所有者の型のグラフ（型の field を辿る）が、ApiClient 系の port を持つか。 */
    internal fun hasApiPort(owner: Class<*>, classifier: TypeClassifier): Boolean {
        val seen = mutableSetOf<Class<*>>()
        fun visit(type: Class<*>): Boolean {
            if (!seen.add(type)) return false
            if (classifier.portNameOf(type) == "ApiClient") return true
            val traverse = type in classifier.populationClasses || classifier.classify(type) == TypeClass.PORT_BACKED
            if (!traverse) return false
            return declaredSites(type).any { site -> typeTree(site.type).any { visit(it) } }
        }
        return visit(owner)
    }

    /** どの probe set の所有者も宣言していない PROBE 行（欠けた呼び方）。登録 list 全体に当てる。 */
    internal fun rowsWithoutProbeSet(probeSets: List<QueryProbeSet>, context: ProbeContext): List<Finding> {
        val owners = probeSets.map { it.owner }.toSet()
        return context.ledger.filter { CallResolution.isProbeRow(it) }.mapNotNull { row ->
            val declaring = LedgerChecks.operationsOf(row, context.population).map { it.typeName }.toSet()
            if (declaring.isNotEmpty() && declaring.none { it in owners }) {
                Finding(Headings.MISSING_CALL, row.id, "この行を宣言する型 ${declaring.sorted().joinToString()} の probe set が登録されていない")
            } else {
                null
            }
        }
    }
}

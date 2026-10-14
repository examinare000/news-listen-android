package com.rioikeda.newslisten.architecture

/** 台帳と列挙の照合と、分類の整合。 */
object LedgerChecks {

    private val getterName = Regex("^(get|is)[A-Z].*")

    /** 公開操作が getter か。引数 0 で suspend でなく、名前が `get[A-Z]`・`is[A-Z]`（メソッドの単位で判定する）。 */
    internal fun isGetter(operation: PublicOperation): Boolean =
        operation.signature == "()" && getterName.matches(operation.id.substringAfter('#'))

    /** 行の `id` と `aliases` に一致する公開操作。 */
    internal fun operationsOf(row: LedgerRow, population: Population): List<PublicOperation> {
        val ids = row.aliases + row.id
        return population.operations.filter { it.id in ids }
    }

    /** 公開操作 1 件に一致する行（0 件・複数件もありうる）。 */
    internal fun rowsOf(operationId: String, rows: List<LedgerRow>): List<LedgerRow> =
        rows.filter { operationId == it.id || operationId in it.aliases }

    fun reconcile(population: Population, rows: List<LedgerRow>): List<Finding> {
        val findings = mutableListOf<Finding>()

        rows.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted().forEach {
            findings += Finding(Headings.DUPLICATE_ROW, it, "同じ id の行が複数ある")
        }

        val operationIds = population.operations.map { it.id }.distinct()
        for (id in operationIds) {
            val matched = rowsOf(id, rows)
            when {
                matched.isEmpty() -> findings += Finding(Headings.MISSING_ROW, id, "台帳の行も alias も無い")
                matched.map { it.id }.distinct().size > 1 ->
                    findings += Finding(Headings.AMBIGUOUS_ROW, id, "一致する行: " + matched.joinToString { it.id })
            }
        }

        val known = operationIds.toSet()
        for (row in rows) {
            (listOf(row.id) + row.aliases.sorted()).filter { it !in known }.forEach {
                findings += Finding(Headings.STALE_ROW, it, "行 ${row.id} が指す操作が列挙に無い")
            }
        }

        for (row in rows) {
            val actual = operationsOf(row, population).map { it.signature }.toSet()
            if (actual.isEmpty()) continue
            val declared = row.signatures
            if (declared == null) {
                if (actual.size != 1) {
                    findings += Finding(
                        Headings.SIGNATURE_MISMATCH, row.id,
                        "signatures の欄が無いのに signature が ${actual.size} つある: ${actual.sorted().joinToString()}",
                    )
                }
                continue
            }
            (actual - declared).sorted().takeIf { it.isNotEmpty() }?.let {
                findings += Finding(Headings.MISSING_SIGNATURE, row.id, "足りない signature: ${it.joinToString()}")
            }
            (declared - actual).sorted().takeIf { it.isNotEmpty() }?.let {
                findings += Finding(Headings.EXTRA_SIGNATURE, row.id, "余分な signature: ${it.joinToString()}")
            }
        }

        findings += splitInterfaceImplementation(population, rows)
        return findings
    }

    /** 母集団の interface のメソッドと、それを実装する母集団の型の同名・同じ引数の型のメソッドは同じ行になければならない。 */
    private fun splitInterfaceImplementation(population: Population, rows: List<LedgerRow>): List<Finding> {
        val findings = mutableListOf<Finding>()
        val interfaceOps = population.operations.filter { it.method.declaringClass.isInterface }
        for (declared in interfaceOps) {
            val declaredRow = rowsOf(declared.id, rows).singleOrNull() ?: continue
            population.operations
                .filter { candidate ->
                    candidate.method.declaringClass != declared.method.declaringClass &&
                        declared.method.declaringClass.isAssignableFrom(candidate.method.declaringClass) &&
                        candidate.method.name == declared.method.name &&
                        candidate.signature == declared.signature
                }
                .forEach { implementation ->
                    val implementationRow = rowsOf(implementation.id, rows).singleOrNull() ?: return@forEach
                    if (implementationRow.id != declaredRow.id) {
                        findings += Finding(
                            Headings.SPLIT_INTERFACE_IMPLEMENTATION,
                            implementation.id,
                            "interface の ${declared.id} は行 ${declaredRow.id}、実装は行 ${implementationRow.id}。同じ行に寄せる",
                        )
                    }
                }
        }
        return findings
    }

    fun classification(population: Population, rows: List<LedgerRow>, catalog: EffectTypeCatalog): List<Finding> {
        val classifier = TypeClassifier(population, catalog)
        val effectful = EffectfulTypes.fixedPoint(classifier)
        val findings = mutableListOf<Finding>()
        for (row in rows) {
            val operations = operationsOf(row, population)
            val getters = operations.filter { isGetter(it) }
            if (row.kind == OperationKind.COMMAND) {
                if (getters.isNotEmpty()) {
                    findings += Finding(Headings.CLASSIFICATION, row.id, "getter（${getters.joinToString { it.id }}）を含む行を COMMAND にできない")
                }
                if (row.reason.isBlank()) {
                    findings += Finding(Headings.CLASSIFICATION, row.id, "COMMAND の行は reason が必須")
                }
                if (row.evidence != null) {
                    findings += Finding(Headings.CLASSIFICATION, row.id, "COMMAND の行に evidence（${row.evidence}）は付けない")
                }
            } else {
                when (row.evidence) {
                    null -> findings += Finding(Headings.CLASSIFICATION, row.id, "QUERY の行は evidence（PROBE か STRUCTURAL）が必須")
                    QueryEvidence.STRUCTURAL -> operations.forEach { operation ->
                        EffectfulTypes.whyNotStructural(operation.method, operation.parameterTypes, classifier, effectful)?.let {
                            findings += Finding(Headings.CLASSIFICATION, "${operation.id} ${operation.signature}", "STRUCTURAL にできない: $it")
                        }
                    }
                    QueryEvidence.PROBE -> Unit
                }
            }
            val specId = row.specId
            if (specId != null) {
                val expected = when {
                    specId.startsWith("android-query-") -> OperationKind.QUERY
                    specId.startsWith("android-cmd-") -> OperationKind.COMMAND
                    else -> null
                }
                if (expected != null && expected != row.kind) {
                    findings += Finding(Headings.CLASSIFICATION, row.id, "specId $specId は $expected だが行は ${row.kind}")
                }
            }
        }
        return findings
    }
}

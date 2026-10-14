package com.rioikeda.newslisten.architecture

/** 移行違反の表との一致と、既知の違反の観測。 */
object ViolationChecks {

    private data class Pair3(val operationId: String, val port: String, val op: String)

    /**
     * 観測した `(操作 ID, port, op)` の集合（scenario・signature を問わない）と表の組の集合が等しいことを確かめる。
     * 「表に無い書込」「書込の無い表の行（縮め忘れ）」「owner 不正」「背景処理の変化」を別の見出しで返す。
     */
    fun compare(
        observed: List<ObservedWrite>,
        backgroundChanges: List<BackgroundChange>,
        table: List<MigrationViolation>,
        ledger: List<LedgerRow>,
        population: Population,
        sliceIds: Set<String>,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        table.groupBy { Pair3(it.operationId, it.port, it.op) }.filterValues { it.size > 1 }.keys.forEach {
            findings += Finding(Headings.DUPLICATE_VIOLATION, it.operationId, "${it.port}#${it.op} の行が複数ある")
        }

        val queryIds = ledger.filter { it.kind == OperationKind.QUERY }.map { it.id }.toSet()
        val populationFiles = population.classesByFile.keys
        for (row in table) {
            val subject = row.operationId
            listOf("owner" to row.owner, "fixTask" to row.fixTask, "removeBy" to row.removeBy).forEach { (name, value) ->
                if (value !in sliceIds) {
                    findings += Finding(Headings.INVALID_OWNER, subject, "$name $value が sliceIds に無い（${row.port}#${row.op}）")
                }
            }
            if (row.rule != MigrationViolation.RULE) {
                findings += Finding(Headings.INVALID_ROW, subject, "rule は ${MigrationViolation.RULE} でなければならない（${row.rule}）")
            }
            if (row.operationId !in queryIds) {
                findings += Finding(Headings.INVALID_ROW, subject, "操作 ID が台帳の QUERY 行の id でない")
            }
            if (row.path !in populationFiles) {
                findings += Finding(Headings.INVALID_ROW, subject, "path ${row.path} が母集団ファイルでない")
            }
        }

        val observedKeys = observed.groupBy { Pair3(it.operationId, it.port, it.op) }
        val tableKeys = table.map { Pair3(it.operationId, it.port, it.op) }.toSet()
        observedKeys.filterKeys { it !in tableKeys }.forEach { (key, writes) ->
            val signatures = writes.map { it.signature }.distinct().sorted().joinToString()
            val scenarios = writes.map { it.scenario }.distinct().sorted().joinToString()
            findings += Finding(
                Headings.UNLISTED_WRITE, key.operationId,
                "${key.port}#${key.op}（signature: $signatures、scenario: $scenarios、${writes.size} 件）",
            )
        }
        (tableKeys - observedKeys.keys).forEach {
            findings += Finding(Headings.STALE_VIOLATION, it.operationId, "${it.port}#${it.op} の書込が観測されない（表の行を縮め忘れている）")
        }

        backgroundChanges.forEach {
            findings += Finding(
                Headings.BACKGROUND_CHANGE, it.operationId,
                "signature ${it.signature}、scenario ${it.scenario}、${it.kind}: ${it.detail}",
            )
        }
        return findings
    }

    /** 既知の違反の各組が、表に含まれ、かつ観測されることを確かめる。観測されなければ「probe の欠陥」。 */
    fun knownObserved(
        known: List<KnownViolation>,
        table: List<MigrationViolation>,
        observed: List<ObservedWrite>,
        sliceIds: Set<String>,
    ): List<Finding> {
        val observedKeys = observed.map { Triple(it.operationId, it.port, it.op) }.toSet()
        val tableKeys = table.map { Triple(it.operationId, it.port, it.op) }.toSet()
        val findings = mutableListOf<Finding>()
        for (violation in known) {
            val key = Triple(violation.operationId, violation.port, violation.op)
            if (key !in tableKeys) {
                findings += Finding(
                    Headings.KNOWN_NOT_IN_TABLE, violation.operationId,
                    "${violation.port}#${violation.op}（${violation.source}）が移行違反の表に無い",
                )
            }
            if (key !in observedKeys) {
                findings += Finding(
                    Headings.PROBE_DEFECT, violation.operationId,
                    "${violation.port}#${violation.op}（${violation.source}）が観測されない。probe の warm-up か呼び方の欠陥",
                )
            }
            if (violation.removeBy !in sliceIds) {
                findings += Finding(
                    Headings.INVALID_OWNER, violation.operationId,
                    "既知の違反の removeBy ${violation.removeBy} が sliceIds に無い",
                )
            }
        }
        return findings
    }
}

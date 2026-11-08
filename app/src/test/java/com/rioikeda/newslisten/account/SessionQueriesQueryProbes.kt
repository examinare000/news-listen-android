package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.account.app.SessionQueries
import com.rioikeda.newslisten.architecture.CallSpec
import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet

/** `SessionQueries` の query は一覧の読み 2 つ。port の書込を呼ばないことを、読みの canned と記録 fake で観測する。 */
internal val sessionQueriesQueryProbes: QueryProbeSet = run {
    val canned = mapOf(
        "AccountApi#listSessions" to ProbeData.repeated(ProbeData.sessionRecords),
        "AccountApi#listPasskeyCredentials" to ProbeData.repeated(ProbeData.passkeyRecords),
    )
    queryProbeSet(
        owner = "SessionQueries",
        scenarios = listOf(ProbeData.readSuccess(canned), ProbeData.readFailure()),
        calls = listOf(
            CallSpec("SessionQueries#sessions"),
            CallSpec("SessionQueries#credentials"),
        ),
    ) { env -> SessionQueries(env.accountApi) }
}

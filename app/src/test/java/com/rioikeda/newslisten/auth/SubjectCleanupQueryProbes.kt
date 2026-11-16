package com.rioikeda.newslisten.auth

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.auth.app.SubjectCleanup
import com.rioikeda.newslisten.auth.domain.CleanupStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** `SubjectCleanup` の query は `cleanupIncomplete` の getter だけ。読むだけで、手順列の起動を要しない。 */
internal val subjectCleanupQueryProbes: QueryProbeSet = queryProbeSet(
    owner = "SubjectCleanup",
    scenarios = listOf(
        ProbeData.readSuccess(noWarmUpReason = "getter は StateFlow を読むだけで、手順列の起動を要しない"),
    ),
    assemble = { env ->
        SubjectCleanup(
            steps = listOf(CleanupStep("probe") { env.preferencesStore.clearSubjectScoped() }),
            scope = CoroutineScope(SupervisorJob() + env.newDispatcher()),
        )
    },
)

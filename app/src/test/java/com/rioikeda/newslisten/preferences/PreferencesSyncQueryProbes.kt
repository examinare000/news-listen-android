package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.architecture.ProbeData
import com.rioikeda.newslisten.architecture.ProbeEnvironment
import com.rioikeda.newslisten.architecture.QueryProbeSet
import com.rioikeda.newslisten.architecture.RecordingDefaults
import com.rioikeda.newslisten.architecture.queryProbeSet
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * AppContainer と同じ形（設定のサーバー往復 port・設定の store・主体の読み・scope）で `PreferencesSync` を組む。
 * `SettingsViewModel` の probe も、自分のグラフの中にこれを組む（実行単位をまたいで共有しない）。
 */
internal fun probePreferencesSync(env: ProbeEnvironment): PreferencesSync = PreferencesSync(
    env.preferencesApi,
    env.preferencesStore,
    env.hooks.read("PreferencesSync#currentSubject") { "probe-subject" },
    CoroutineScope(SupervisorJob() + env.newDispatcher()),
)

/** `PreferencesSync` の query は state の getter だけ。warm-up でサーバーの設定の取り込みを済ませた状態を観測する。 */
internal val preferencesSyncQueryProbes: QueryProbeSet = run {
    val canned = mapOf("PreferencesApi#fetchPreferences" to ProbeData.repeated(RecordingDefaults.serverPreferences))
    val warmUp: suspend (Any, ProbeEnvironment) -> Unit = { owner, _ -> (owner as PreferencesSync).syncFromServer() }
    queryProbeSet(
        owner = "PreferencesSync",
        scenarios = listOf(
            ProbeData.readSuccess(canned, warmUp),
            ProbeData.readFailure(warmUp = warmUp),
        ),
        assemble = ::probePreferencesSync,
    )
}

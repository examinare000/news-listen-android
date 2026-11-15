package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [SettingsViewModel] の Preferences 同期（[com.rioikeda.newslisten.preferences.app.PreferencesSync] 越しに届く
 * `ApiClient`）のテスト専用フェイク。
 *
 * preferences 取得・更新のみ挙動を差し替え可能にする。Sources・おすすめサイト・生成クォータは
 * `SourcesApi` の fake（`settings/app/FakeSourcesApi`）が担う。それ以外はスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できる（auth/FakeApiClient.kt と同じ設計方針）。
 */
class FakeApiClient(
    private val onFetchPreferences: suspend () -> PreferencesResponse =
        { error("fetchPreferences is not stubbed") },
    private val onUpdatePreferences: suspend (defaultDifficulty: String?, defaultPlaybackSpeed: Double?) -> PreferencesResponse =
        { _, _ -> error("updatePreferences is not stubbed") },
    private val onUpdateWeeklyGoalEpisodes: suspend (weeklyGoalEpisodes: Int) -> PreferencesResponse =
        { error("updateWeeklyGoalEpisodes is not stubbed") },
) : BaseFakeApiClient() {

    override suspend fun fetchPreferences(): PreferencesResponse = onFetchPreferences()

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        onUpdatePreferences(defaultDifficulty, defaultPlaybackSpeed)

    override suspend fun updateWeeklyGoalEpisodes(weeklyGoalEpisodes: Int): PreferencesResponse =
        onUpdateWeeklyGoalEpisodes(weeklyGoalEpisodes)
}

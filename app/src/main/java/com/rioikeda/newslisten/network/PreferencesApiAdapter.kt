package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.preferences.app.PreferencesApi
import com.rioikeda.newslisten.preferences.app.ServerPreferences

/**
 * [ApiClient] を包み、設定の往復を [PreferencesApi] で見せる。
 * 例外は捕捉せず透過する。transport の再試行は [OkHttpApiClient] の責務。
 */
internal class PreferencesApiAdapter(private val apiClient: ApiClient) : PreferencesApi {
    override suspend fun fetchPreferences(): ServerPreferences {
        val response = apiClient.fetchPreferences()
        return ServerPreferences(
            defaultDifficulty = response.defaultDifficulty,
            defaultPlaybackSpeed = response.defaultPlaybackSpeed,
            weeklyGoalEpisodes = response.weeklyGoalEpisodes,
        )
    }

    override suspend fun updateDefaultDifficulty(code: String) {
        apiClient.updatePreferences(defaultDifficulty = code, defaultPlaybackSpeed = null)
    }

    override suspend fun updateDefaultPlaybackSpeed(speed: Double) {
        apiClient.updatePreferences(defaultDifficulty = null, defaultPlaybackSpeed = speed)
    }

    override suspend fun updateWeeklyGoalEpisodes(episodes: Int) {
        apiClient.updateWeeklyGoalEpisodes(episodes)
    }
}

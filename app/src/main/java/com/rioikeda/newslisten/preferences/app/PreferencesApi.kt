package com.rioikeda.newslisten.preferences.app

/** サーバーの設定。週の目標は旧サーバーでは欠損しうる。 */
data class ServerPreferences(
    val defaultDifficulty: String,
    val defaultPlaybackSpeed: Double,
    val weeklyGoalEpisodes: Int?,
)

/**
 * 設定のサーバー往復の port。失敗は [com.rioikeda.newslisten.core.ApiException] のまま投げる。
 * 再試行はしない（transport の再試行は adapter の下の層の責務）。
 */
interface PreferencesApi {
    suspend fun fetchPreferences(): ServerPreferences

    suspend fun updateDefaultDifficulty(code: String)

    suspend fun updateDefaultPlaybackSpeed(speed: Double)

    suspend fun updateWeeklyGoalEpisodes(episodes: Int)
}

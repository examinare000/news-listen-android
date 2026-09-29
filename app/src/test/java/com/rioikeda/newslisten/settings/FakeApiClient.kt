package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [SettingsViewModel] のテスト専用フェイク。
 *
 * フェーズ10 P10 Task2（設定タブ）で使う RSS ソース CRUD・おすすめサイト・生成クォータ・
 * 聴取ストリーク・preferences 更新のみ挙動を差し替え可能にする。それ以外はスコープ外のため、
 * 誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （auth/FakeApiClient.kt と同じ設計方針）。
 */
class FakeApiClient(
    private val onFetchSources: suspend () -> RssSourcesResponse =
        { error("fetchSources is not stubbed") },
    private val onCreateSource: suspend (name: String, url: String) -> RssSourcesResponse =
        { _, _ -> error("createSource is not stubbed") },
    private val onUpdateSource: suspend (oldUrl: String, name: String, url: String) -> RssSourcesResponse =
        { _, _, _ -> error("updateSource is not stubbed") },
    private val onDeleteSource: suspend (url: String) -> RssSourcesResponse =
        { error("deleteSource is not stubbed") },
    private val onFetchFeaturedSites: suspend () -> FeaturedSitesResponse =
        { error("fetchFeaturedSites is not stubbed") },
    private val onUpdatePreferences: suspend (defaultDifficulty: String?, defaultPlaybackSpeed: Double?) -> PreferencesResponse =
        { _, _ -> error("updatePreferences is not stubbed") },
    private val onUpdateWeeklyGoalEpisodes: suspend (weeklyGoalEpisodes: Int) -> PreferencesResponse =
        { error("updateWeeklyGoalEpisodes is not stubbed") },
    private val onFetchGenerationQuota: suspend () -> GenerationQuotaResponse =
        { error("fetchGenerationQuota is not stubbed") },
    private val onFetchListeningStreak: suspend () -> ListeningStreakResponse =
        { error("fetchListeningStreak is not stubbed") },
) : BaseFakeApiClient() {

    /** [updateSource] が呼ばれた回数。admin ガードで API 未呼び出しであることの検証に使う。 */
    var updateSourceCallCount = 0
        private set

    override suspend fun fetchSources(): RssSourcesResponse = onFetchSources()

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        onCreateSource(name, url)

    override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse {
        updateSourceCallCount++
        return onUpdateSource(oldUrl, name, url)
    }

    override suspend fun deleteSource(url: String): RssSourcesResponse = onDeleteSource(url)

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse = onFetchFeaturedSites()

    override suspend fun updatePreferences(defaultDifficulty: String?, defaultPlaybackSpeed: Double?): PreferencesResponse =
        onUpdatePreferences(defaultDifficulty, defaultPlaybackSpeed)

    override suspend fun updateWeeklyGoalEpisodes(weeklyGoalEpisodes: Int): PreferencesResponse =
        onUpdateWeeklyGoalEpisodes(weeklyGoalEpisodes)

    override suspend fun fetchGenerationQuota(): GenerationQuotaResponse = onFetchGenerationQuota()

    override suspend fun fetchListeningStreak(): ListeningStreakResponse = onFetchListeningStreak()
}

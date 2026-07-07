package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsViewModel] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Settings/SettingsViewModel.swift のミラー。
 * フェーズ10 P10 Task2。
 */
class SettingsViewModelTest {

    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        isAdminProvider: () -> Boolean = { false },
    ): SettingsViewModel =
        SettingsViewModel(
            apiClient = apiClient,
            preferencesStore = preferencesStore,
            dispatcher = StandardTestDispatcher(testScheduler),
            isAdminProvider = isAdminProvider,
        )

    // --- RSS ソース ---

    @Test
    fun loadSources成功でsourcesが更新される() = runTest {
        val sources = listOf(RssSource(name = "TechCrunch", url = "https://tc.example.com/rss"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchSources = { RssSourcesResponse(sources) }),
        )

        viewModel.loadSources()

        assertEquals(sources, viewModel.sources.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun loadSources失敗でerrorMessageが設定される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchSources = { throw ApiException.HttpError(500) }),
        )

        viewModel.loadSources()

        assertTrue(viewModel.sources.value.isEmpty())
        assertEquals(false, viewModel.errorMessage.value == null)
    }

    @Test
    fun addSource成功でサーバ最新一覧がsourcesに反映される() = runTest {
        val updated = listOf(RssSource(name = "New", url = "https://new.example.com/rss"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCreateSource = { _, _ -> RssSourcesResponse(updated) }),
        )

        viewModel.addSource(name = "New", url = "https://new.example.com/rss")

        assertEquals(updated, viewModel.sources.value)
    }

    @Test
    fun addSource失敗でerrorMessageが設定されsourcesは変わらない() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCreateSource = { _, _ -> throw ApiException.HttpError(400) }),
        )

        viewModel.addSource(name = "New", url = "https://new.example.com/rss")

        assertTrue(viewModel.sources.value.isEmpty())
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun updateSourceはadminなら成功しsourcesが更新される() = runTest {
        val updated = listOf(RssSource(name = "Renamed", url = "https://tc.example.com/rss"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdateSource = { _, _, _ -> RssSourcesResponse(updated) }),
            isAdminProvider = { true },
        )

        viewModel.updateSource(oldUrl = "https://tc.example.com/rss", name = "Renamed", url = "https://tc.example.com/rss")

        assertEquals(updated, viewModel.sources.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun updateSourceは非adminならAPIを呼ばずerrorMessageを設定する() = runTest {
        val apiClient = FakeApiClient(onUpdateSource = { _, _, _ -> error("must not be called") })
        val viewModel = newViewModel(apiClient = apiClient, isAdminProvider = { false })

        viewModel.updateSource(oldUrl = "https://tc.example.com/rss", name = "Renamed", url = "https://tc.example.com/rss")

        assertEquals(0, apiClient.updateSourceCallCount)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun removeSource成功でsourcesから取り除かれる() = runTest {
        val remaining = RssSource(name = "Keep", url = "https://keep.example.com/rss")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onFetchSources = {
                    RssSourcesResponse(
                        listOf(remaining, RssSource(name = "Remove", url = "https://remove.example.com/rss")),
                    )
                },
                onDeleteSource = { RssSourcesResponse(listOf(remaining)) },
            ),
        )
        viewModel.loadSources()

        viewModel.removeSource(url = "https://remove.example.com/rss")

        assertEquals(listOf(remaining), viewModel.sources.value)
    }

    @Test
    fun removeSource失敗でerrorMessageが設定されsourcesは変わらない() = runTest {
        val existing = listOf(RssSource(name = "Keep", url = "https://keep.example.com/rss"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onFetchSources = { RssSourcesResponse(existing) },
                onDeleteSource = { throw ApiException.HttpError(500) },
            ),
        )
        viewModel.loadSources()

        viewModel.removeSource(url = "https://keep.example.com/rss")

        assertEquals(existing, viewModel.sources.value)
        assertTrue(viewModel.errorMessage.value != null)
    }

    // --- おすすめサイト ---

    @Test
    fun loadFeaturedSites成功でfeaturedSitesが更新される() = runTest {
        val sites = listOf(FeaturedSite(id = "1", name = "Site", url = "https://site.example.com/rss"))
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchFeaturedSites = { com.rioikeda.newslisten.model.FeaturedSitesResponse(sites) }),
        )

        viewModel.loadFeaturedSites()

        assertEquals(sites, viewModel.featuredSites.value)
        assertFalse(viewModel.featuredSitesLoadFailed.value)
    }

    @Test
    fun loadFeaturedSites失敗でfeaturedSitesLoadFailedが立つ() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchFeaturedSites = { throw ApiException.HttpError(500) }),
        )

        viewModel.loadFeaturedSites()

        assertTrue(viewModel.featuredSites.value.isEmpty())
        assertTrue(viewModel.featuredSitesLoadFailed.value)
        // 完全サイレントにしない一方、アラート用の errorMessage は汚さない（iOS 同型仕様）。
        assertNull(viewModel.errorMessage.value)
    }

    // --- 生成クォータ ---

    @Test
    fun loadGenerationQuota成功でgenerationQuotaが更新される() = runTest {
        val quota = GenerationQuotaResponse(limit = 5, used = 2, remaining = 3, resetAt = "2026-07-09T00:00:00Z")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchGenerationQuota = { quota }),
        )

        viewModel.loadGenerationQuota()

        assertEquals(quota, viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)
    }

    @Test
    fun loadGenerationQuota無制限はlimitゼロremainingNullのまま反映される() = runTest {
        val unlimited = GenerationQuotaResponse(limit = 0, used = 10, remaining = null, resetAt = "2026-07-09T00:00:00Z")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchGenerationQuota = { unlimited }),
        )

        viewModel.loadGenerationQuota()

        assertEquals(unlimited, viewModel.generationQuota.value)
    }

    @Test
    fun loadGenerationQuotaは404でgracefulにnullとなりLoadFailedは立たない() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchGenerationQuota = { throw ApiException.HttpError(404) }),
        )

        viewModel.loadGenerationQuota()

        assertNull(viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)
    }

    @Test
    fun loadGenerationQuotaは404以外の失敗でLoadFailedが立つ() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchGenerationQuota = { throw ApiException.HttpError(500) }),
        )

        viewModel.loadGenerationQuota()

        assertNull(viewModel.generationQuota.value)
        assertTrue(viewModel.generationQuotaLoadFailed.value)
    }

    // --- 聴取ストリーク ---

    @Test
    fun loadListeningStreak成功でlisteningStreakが更新される() = runTest {
        val streak = ListeningStreakResponse(currentStreakDays = 3, todayListened = true, lastListenedDay = "2026-07-08")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchListeningStreak = { streak }),
        )

        viewModel.loadListeningStreak()

        assertEquals(streak, viewModel.listeningStreak.value)
        assertFalse(viewModel.listeningStreakLoadFailed.value)
    }

    @Test
    fun loadListeningStreakは404でgracefulにnullとなりLoadFailedは立たない() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchListeningStreak = { throw ApiException.HttpError(404) }),
        )

        viewModel.loadListeningStreak()

        assertNull(viewModel.listeningStreak.value)
        assertFalse(viewModel.listeningStreakLoadFailed.value)
    }

    @Test
    fun loadListeningStreakは404以外の失敗でLoadFailedが立つ() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchListeningStreak = { throw ApiException.HttpError(500) }),
        )

        viewModel.loadListeningStreak()

        assertNull(viewModel.listeningStreak.value)
        assertTrue(viewModel.listeningStreakLoadFailed.value)
    }

    // --- 難易度・再生速度の PUT 同期 ---

    @Test
    fun syncDefaultDifficulty成功でtrueを返しPreferencesStoreへ書き戻す() = runTest {
        val preferencesStore = InMemoryPreferencesStore()
        var receivedDifficulty: String? = null
        var receivedSpeed: Double? = null
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onUpdatePreferences = { difficulty, speed ->
                    receivedDifficulty = difficulty
                    receivedSpeed = speed
                    PreferencesResponse(
                        defaultDifficulty = difficulty ?: "toeic_600",
                        defaultPlaybackSpeed = 1.0,
                        digestEnabled = false,
                        digestArticleCount = 0,
                    )
                },
            ),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultDifficulty("toeic_900")

        assertTrue(result)
        assertEquals("toeic_900", receivedDifficulty)
        assertNull(receivedSpeed)
        assertEquals("toeic_900", preferencesStore.defaultDifficulty.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun syncDefaultDifficulty失敗でfalseを返しPreferencesStoreは変わらない() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialDefaultDifficulty = "toeic_600")
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdatePreferences = { _, _ -> throw ApiException.HttpError(500) }),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultDifficulty("toeic_900")

        assertFalse(result)
        assertEquals("toeic_600", preferencesStore.defaultDifficulty.value)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun syncDefaultPlaybackSpeed成功でtrueを返しPreferencesStoreへ書き戻す() = runTest {
        val preferencesStore = InMemoryPreferencesStore()
        var receivedDifficulty: String? = null
        var receivedSpeed: Double? = null
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onUpdatePreferences = { difficulty, speed ->
                    receivedDifficulty = difficulty
                    receivedSpeed = speed
                    PreferencesResponse(
                        defaultDifficulty = "toeic_600",
                        defaultPlaybackSpeed = speed ?: 1.0,
                        digestEnabled = false,
                        digestArticleCount = 0,
                    )
                },
            ),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultPlaybackSpeed(1.5)

        assertTrue(result)
        assertNull(receivedDifficulty)
        assertEquals(1.5, receivedSpeed)
        assertEquals(1.5, preferencesStore.defaultPlaybackSpeed.value, 0.0)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun syncDefaultPlaybackSpeed失敗でfalseを返しPreferencesStoreは変わらない() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialDefaultPlaybackSpeed = 1.0)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdatePreferences = { _, _ -> throw ApiException.HttpError(500) }),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultPlaybackSpeed(2.0)

        assertFalse(result)
        assertEquals(1.0, preferencesStore.defaultPlaybackSpeed.value, 0.0)
        assertTrue(viewModel.errorMessage.value != null)
    }
}

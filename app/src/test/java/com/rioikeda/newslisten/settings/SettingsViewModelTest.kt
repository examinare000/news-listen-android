package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.podcast.PlaybackConstants
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.network.PreferencesApiAdapter
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.preferences.app.PreferencesView
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [SettingsViewModel] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Settings/SettingsViewModel.swift のミラー。
 * フェーズ10 P10 Task2。
 */
class SettingsViewModelTest {

    /** 実物の [PreferencesSync]。サーバー往復は [PreferencesApiAdapter] 越しに [ApiClient] の fake へ届く。 */
    private fun TestScope.newPreferencesSync(
        apiClient: ApiClient,
        preferencesStore: PreferencesStore,
    ): PreferencesSync =
        PreferencesSync(PreferencesApiAdapter(apiClient), preferencesStore, { "u-1" }, backgroundScope)

    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        preferencesSync: PreferencesSync = newPreferencesSync(apiClient, preferencesStore),
        isAdminProvider: () -> Boolean = { false },
    ): SettingsViewModel =
        SettingsViewModel(
            apiClient = apiClient,
            preferencesSync = preferencesSync,
            dispatcher = StandardTestDispatcher(testScheduler),
            isAdminProvider = isAdminProvider,
        )

    /** 書込だけが `IOException` で失敗する store（DataStore の書込失敗の模擬）。 */
    private class FailingWriteStore(delegate: InMemoryPreferencesStore) : PreferencesStore by delegate {
        override suspend fun setDefaultPlaybackSpeed(speed: Double) {
            throw IOException("disk full")
        }
    }

    /** 効果音の書込だけが `IOException` で失敗する store（端末だけの設定の保存失敗の模擬）。 */
    private class FailingSfxWriteStore(delegate: InMemoryPreferencesStore) : PreferencesStore by delegate {
        override suspend fun setSfxEnabled(enabled: Boolean) {
            throw IOException("disk full")
        }
    }

    // --- 端末だけの設定（画面は ViewModel 越しに読み書きする） ---

    @Test
    fun `android-query-prefs-01 preferencesは構築直後に非既定のstoreの値を公開する`() = runTest {
        // verifies: android-query-prefs-01
        val store = InMemoryPreferencesStore(
            initialDefaultDifficulty = Difficulty.TOEIC_900,
            initialDefaultPlaybackSpeed = PlaybackSpeed.of(1.5)!!,
            initialArticleOpenMode = ArticleOpenMode.EXTERNAL,
            initialTimeFormat = TimeFormat.RELATIVE,
            initialSfxEnabled = false,
            initialHapticsEnabled = false,
            initialWeeklyGoalEpisodes = WeeklyGoal.of(7)!!,
            initialSeenAchievementIds = setOf("a"),
        )

        val viewModel = newViewModel(preferencesStore = store)

        val expected = PreferencesView(
            defaultDifficulty = Difficulty.TOEIC_900,
            defaultPlaybackSpeed = PlaybackSpeed.of(1.5)!!,
            weeklyGoal = WeeklyGoal.of(7)!!,
            articleOpenMode = ArticleOpenMode.EXTERNAL,
            timeFormat = TimeFormat.RELATIVE,
            sfxEnabled = false,
            hapticsEnabled = false,
            seenAchievementIds = setOf("a"),
        )
        assertEquals(expected, viewModel.preferences.value)
    }

    @Test
    fun `android-query-prefs-01 storeの端末だけの設定の変更がpreferencesに反映される`() = runTest {
        // verifies: android-query-prefs-01
        val store = InMemoryPreferencesStore()
        val viewModel = newViewModel(preferencesStore = store)
        assertTrue(viewModel.preferences.value.sfxEnabled)

        store.setSfxEnabled(false)
        runCurrent()

        assertFalse(viewModel.preferences.value.sfxEnabled)
    }

    @Test
    fun `android-cmd-prefs-02 setArticleOpenModeの中継はstoreに届きpreferencesに反映される`() = runTest {
        // verifies: android-cmd-prefs-02
        val store = InMemoryPreferencesStore()
        val viewModel = newViewModel(preferencesStore = store)
        assertEquals(ArticleOpenMode.IN_APP, store.articleOpenMode.value)

        viewModel.setArticleOpenMode(ArticleOpenMode.EXTERNAL)
        runCurrent()

        assertEquals(ArticleOpenMode.EXTERNAL, store.articleOpenMode.value)
        assertEquals(ArticleOpenMode.EXTERNAL, viewModel.preferences.value.articleOpenMode)
    }

    @Test
    fun `android-cmd-prefs-02 setTimeFormatの中継はstoreに届きpreferencesに反映される`() = runTest {
        // verifies: android-cmd-prefs-02
        val store = InMemoryPreferencesStore()
        val viewModel = newViewModel(preferencesStore = store)
        assertEquals(TimeFormat.ABSOLUTE, store.timeFormat.value)

        viewModel.setTimeFormat(TimeFormat.RELATIVE)
        runCurrent()

        assertEquals(TimeFormat.RELATIVE, store.timeFormat.value)
        assertEquals(TimeFormat.RELATIVE, viewModel.preferences.value.timeFormat)
    }

    @Test
    fun `android-cmd-prefs-02 setSfxEnabledの中継はstoreに届きpreferencesに反映される`() = runTest {
        // verifies: android-cmd-prefs-02
        val store = InMemoryPreferencesStore()
        val viewModel = newViewModel(preferencesStore = store)
        assertTrue(store.sfxEnabled.value)

        viewModel.setSfxEnabled(false)
        runCurrent()

        assertFalse(store.sfxEnabled.value)
        assertFalse(viewModel.preferences.value.sfxEnabled)
    }

    @Test
    fun `android-cmd-prefs-02 setHapticsEnabledの中継はstoreに届きpreferencesに反映される`() = runTest {
        // verifies: android-cmd-prefs-02
        val store = InMemoryPreferencesStore()
        val viewModel = newViewModel(preferencesStore = store)
        assertTrue(store.hapticsEnabled.value)

        viewModel.setHapticsEnabled(false)
        runCurrent()

        assertFalse(store.hapticsEnabled.value)
        assertFalse(viewModel.preferences.value.hapticsEnabled)
    }

    @Test
    fun `android-dec-82 端末だけの設定の保存失敗のIOExceptionは中継で捕まえず呼び手へ伝わる`() = runTest {
        // verifies: android-dec-82
        val viewModel = newViewModel(preferencesStore = FailingSfxWriteStore(InMemoryPreferencesStore()))

        val thrown = runCatching { viewModel.setSfxEnabled(false) }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-82 構築直後のpreferencesの効果音とハプティクスはstoreの値と等しい`() = runTest {
        // verifies: android-dec-82
        val store = InMemoryPreferencesStore(initialSfxEnabled = false, initialHapticsEnabled = false)

        val viewModel = newViewModel(preferencesStore = store)

        assertFalse(viewModel.preferences.value.sfxEnabled)
        assertFalse(viewModel.preferences.value.hapticsEnabled)
    }

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
            apiClient = FakeApiClient(onFetchGenerationQuota = { throw ApiException.NotFound() }),
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
            apiClient = FakeApiClient(onFetchListeningStreak = { throw ApiException.NotFound() }),
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

        assertEquals(SelectionAfterSave.Keep, result)
        assertEquals("toeic_900", receivedDifficulty)
        assertNull(receivedSpeed)
        assertEquals(Difficulty.TOEIC_900, preferencesStore.defaultDifficulty.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun syncDefaultDifficulty失敗でfalseを返しPreferencesStoreは変わらない() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialDefaultDifficulty = Difficulty.TOEIC_600)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdatePreferences = { _, _ -> throw ApiException.HttpError(500) }),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultDifficulty("toeic_900")

        assertEquals(SelectionAfterSave.Revert, result)
        assertEquals(Difficulty.TOEIC_600, preferencesStore.defaultDifficulty.value)
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

        assertEquals(SelectionAfterSave.Keep, result)
        assertNull(receivedDifficulty)
        assertEquals(1.5, receivedSpeed)
        assertEquals(1.5, preferencesStore.defaultPlaybackSpeed.value.multiplier, 0.0)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun syncDefaultPlaybackSpeed失敗でfalseを返しPreferencesStoreは変わらない() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialDefaultPlaybackSpeed = PlaybackSpeed.DEFAULT)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onUpdatePreferences = { _, _ -> throw ApiException.HttpError(500) }),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncDefaultPlaybackSpeed(2.0)

        assertEquals(SelectionAfterSave.Revert, result)
        assertEquals(1.0, preferencesStore.defaultPlaybackSpeed.value.multiplier, 0.0)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun `android-rule-playback-05 既定速度はPlaybackConstantsの8値すべてで同期に成功しPreferencesStoreがその値になる`() = runTest {
        // verifies: android-rule-playback-05
        for (speed in PlaybackConstants.speeds) {
            val preferencesStore = InMemoryPreferencesStore()
            val viewModel = newViewModel(
                apiClient = FakeApiClient(
                    onUpdatePreferences = { _, sent ->
                        PreferencesResponse(
                            defaultDifficulty = "toeic_600",
                            defaultPlaybackSpeed = sent ?: 1.0,
                            digestEnabled = false,
                            digestArticleCount = 0,
                        )
                    },
                ),
                preferencesStore = preferencesStore,
            )

            val result = viewModel.syncDefaultPlaybackSpeed(speed)

            assertEquals("speed=$speed", SelectionAfterSave.Keep, result)
            assertEquals("speed=$speed", speed, preferencesStore.defaultPlaybackSpeed.value.multiplier, 0.0)
        }
        assertEquals(8, PlaybackConstants.speeds.size)
    }

    @Test
    fun `週次目標の更新成功時だけPreferencesStoreへ書き戻す`() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialWeeklyGoalEpisodes = WeeklyGoal.DEFAULT)
        var receivedGoal = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onUpdateWeeklyGoalEpisodes = { goal ->
                    receivedGoal = goal
                    PreferencesResponse(
                        defaultDifficulty = "toeic_600",
                        defaultPlaybackSpeed = 1.0,
                        digestEnabled = false,
                        digestArticleCount = 3,
                        weeklyGoalEpisodes = goal,
                    )
                },
            ),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncWeeklyGoalEpisodes(7)

        assertEquals(SelectionAfterSave.Keep, result)
        assertEquals(7, receivedGoal)
        assertEquals(7, preferencesStore.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `許容外の週次目標はAPIへ送らず保持する`() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialWeeklyGoalEpisodes = WeeklyGoal.DEFAULT)
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onUpdateWeeklyGoalEpisodes = {
                    throw AssertionError("許容外の目標でAPIを呼んではならない")
                },
            ),
            preferencesStore = preferencesStore,
        )

        val result = viewModel.syncWeeklyGoalEpisodes(4)

        assertEquals(SelectionAfterSave.Revert, result)
        assertEquals(3, preferencesStore.weeklyGoalEpisodes.value.episodes)
    }

    @Test
    fun `古いリクエストの失敗応答は最新の成功を上書きしない`() = runTest {
        val preferencesStore = InMemoryPreferencesStore(initialWeeklyGoalEpisodes = WeeklyGoal.DEFAULT)
        val firstGate = CompletableDeferred<Unit>()
        var callCount = 0
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onUpdateWeeklyGoalEpisodes = { goal ->
                    callCount += 1
                    if (callCount == 1) {
                        // 先発リクエストを保留し、後発成功の後に失敗として届かせる
                        firstGate.await()
                        throw ApiException.HttpError(500, "stale failure")
                    }
                    PreferencesResponse(
                        defaultDifficulty = "toeic_600",
                        defaultPlaybackSpeed = 1.0,
                        digestEnabled = false,
                        digestArticleCount = 3,
                        weeklyGoalEpisodes = goal,
                    )
                },
            ),
            preferencesStore = preferencesStore,
        )

        val first = async { viewModel.syncWeeklyGoalEpisodes(5) }
        runCurrent()
        val second = async { viewModel.syncWeeklyGoalEpisodes(7) }
        runCurrent()
        assertEquals(SelectionAfterSave.Keep, second.await())
        assertEquals(7, preferencesStore.weeklyGoalEpisodes.value.episodes)

        firstGate.complete(Unit)
        // stale な失敗は Keep（ロールバックしない）・エラー表示なし・最新値を保持
        assertEquals(SelectionAfterSave.Keep, first.await())
        assertEquals(7, preferencesStore.weeklyGoalEpisodes.value.episodes)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-80 端末への保存失敗はRevertを返し保存失敗の文言を出す`() = runTest {
        // verifies: android-dec-80
        val apiClient = FakeApiClient(
            onUpdatePreferences = { _, speed ->
                PreferencesResponse(
                    defaultDifficulty = "toeic_600",
                    defaultPlaybackSpeed = speed ?: 1.0,
                    digestEnabled = false,
                    digestArticleCount = 0,
                )
            },
        )
        val viewModel = newViewModel(
            apiClient = apiClient,
            preferencesStore = FailingWriteStore(InMemoryPreferencesStore()),
        )

        val result = viewModel.syncDefaultPlaybackSpeed(1.5)

        assertEquals(SelectionAfterSave.Revert, result)
        assertEquals("設定の保存に失敗しました", viewModel.errorMessage.value)
    }

    @Test
    fun preferencesSyncFailedはPreferencesSyncの取り込み失敗を公開する() = runTest {
        // verifies: android-cmd-prefs-03
        val apiClient = FakeApiClient(onFetchPreferences = { throw ApiException.HttpError(500) })
        val preferencesSync = newPreferencesSync(apiClient, InMemoryPreferencesStore())
        val viewModel = newViewModel(apiClient = apiClient, preferencesSync = preferencesSync)
        assertFalse(viewModel.preferencesSyncFailed.value)

        preferencesSync.syncFromServer()

        assertTrue(viewModel.preferencesSyncFailed.value)
    }
}

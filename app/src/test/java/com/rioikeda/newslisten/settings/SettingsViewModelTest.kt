package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.learning.app.FakeLearningApi
import com.rioikeda.newslisten.learning.app.StreakQuery
import com.rioikeda.newslisten.learning.app.StreakRecord
import com.rioikeda.newslisten.model.PreferencesResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.podcast.domain.PlaybackConstants
import com.rioikeda.newslisten.preferences.domain.ArticleOpenMode
import com.rioikeda.newslisten.preferences.InMemoryPreferencesStore
import com.rioikeda.newslisten.network.PreferencesApiAdapter
import com.rioikeda.newslisten.preferences.app.PreferencesStore
import com.rioikeda.newslisten.preferences.domain.TimeFormat
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import com.rioikeda.newslisten.preferences.app.PreferencesView
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import com.rioikeda.newslisten.settings.app.FakeSourcesApi
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.app.SourcesState
import com.rioikeda.newslisten.settings.app.FeaturedSiteGroup
import com.rioikeda.newslisten.settings.app.featuredSite
import com.rioikeda.newslisten.settings.app.techCrunch
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import com.rioikeda.newslisten.settings.ui.SelectionAfterSave
import com.rioikeda.newslisten.settings.ui.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    /** Sources の入口の組。同じ [SourcesState] を [SourcesCommands] と [SourcesQueries] が共有する（android-dec-114）。 */
    private class SourcesHarness(scheduler: TestCoroutineScheduler, isAdminProvider: () -> Boolean) {
        val api = FakeSourcesApi()
        val state = SourcesState(StandardTestDispatcher(scheduler))
        val commands = SourcesCommands(api, state, isAdminProvider)
        val queries = SourcesQueries(api, state)
    }

    private fun TestScope.newViewModel(
        apiClient: ApiClient = FakeApiClient(),
        preferencesStore: PreferencesStore = InMemoryPreferencesStore(),
        preferencesSync: PreferencesSync = newPreferencesSync(apiClient, preferencesStore),
        sources: SourcesHarness = SourcesHarness(testScheduler) { false },
        learningApi: FakeLearningApi = FakeLearningApi(),
    ): SettingsViewModel =
        SettingsViewModel(
            sourcesCommands = sources.commands,
            sourcesQueries = sources.queries,
            preferencesSync = preferencesSync,
            dispatcher = StandardTestDispatcher(testScheduler),
            streakQuery = StreakQuery(learningApi, StandardTestDispatcher(testScheduler)),
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

    private val tcUrl = "https://tc.example.com/rss"
    private val failureMessage = "RSSソースの操作に失敗しました"
    private val adminOnlyMessage = "この操作には管理者権限が必要です"

    @Test
    fun loadSources失敗でerrorMessageが設定される() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchSources = { throw ApiException.HttpError(500) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadSources()

        assertTrue(viewModel.sources.value.isEmpty())
        assertEquals(false, viewModel.errorMessage.value == null)
    }

    @Test
    fun addSource失敗でerrorMessageが設定されsourcesは変わらない() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onAddSource = { _, _ -> throw ApiException.HttpError(400) }
        val viewModel = newViewModel(sources = sources)

        viewModel.addSource(name = "New", url = "https://new.example.com/rss")

        assertTrue(viewModel.sources.value.isEmpty())
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun updateSourceは非adminならAPIを呼ばずerrorMessageを設定する() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        val viewModel = newViewModel(sources = sources)

        viewModel.updateSource(oldUrl = tcUrl, name = "Renamed", url = tcUrl)

        assertEquals(0, sources.api.updateSourceCallCount)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun removeSource失敗でerrorMessageが設定されsourcesは変わらない() = runTest {
        val existing = listOf(RssSourceEntry("Keep", "https://keep.example.com/rss"))
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchSources = { existing }
        sources.api.onRemoveSource = { throw ApiException.HttpError(500) }
        val viewModel = newViewModel(sources = sources)
        viewModel.loadSources()

        viewModel.removeSource(url = "https://keep.example.com/rss")

        assertEquals(existing, viewModel.sources.value)
        assertTrue(viewModel.errorMessage.value != null)
    }

    @Test
    fun `android-dec-115 sourcesはSourcesQueriesをそのまま渡す`() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        val viewModel = newViewModel(sources = sources)

        assertSame(sources.queries.sources, viewModel.sources)
    }

    @Test
    fun `android-dec-115 loadSourcesは取得の間だけisLoadingを立て開始時にerrorMessageを消す`() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onAddSource = { _, _ -> throw ApiException.HttpError(400) }
        val viewModel = newViewModel(sources = sources)
        viewModel.addSource(name = "New", url = "https://new.example.com/rss")
        assertTrue(viewModel.errorMessage.value != null)
        var loadingDuringFetch: Boolean? = null
        var messageDuringFetch: String? = "unset"
        sources.api.onFetchSources = {
            loadingDuringFetch = viewModel.isLoading.value
            messageDuringFetch = viewModel.errorMessage.value
            listOf(techCrunch)
        }

        viewModel.loadSources()

        assertEquals(true, loadingDuringFetch)
        assertNull(messageDuringFetch)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `android-dec-115 addSourceとupdateSourceの成功はerrorMessageを消しremoveSourceの成功は消さない`() = runTest {
        val sources = SourcesHarness(testScheduler) { true }
        sources.api.onFetchSources = { listOf(techCrunch) }
        sources.api.onAddSource = { _, _ -> listOf(techCrunch) }
        sources.api.onUpdateSource = { _, _, _ -> listOf(techCrunch) }
        val viewModel = newViewModel(sources = sources)
        viewModel.loadSources()

        // addSource の成功は失敗の文言を消す
        sources.api.onRemoveSource = { throw ApiException.HttpError(500) }
        viewModel.removeSource(tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)
        viewModel.addSource("TechCrunch", tcUrl)
        assertNull(viewModel.errorMessage.value)

        // updateSource の成功は失敗の文言を消す
        viewModel.removeSource(tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)
        viewModel.updateSource(tcUrl, "TechCrunch", tcUrl)
        assertNull(viewModel.errorMessage.value)

        // removeSource の成功は失敗の文言を消さない（正方向の対照: 失敗は設定する）
        viewModel.removeSource(tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)
        sources.api.onRemoveSource = {}
        viewModel.removeSource(tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-115 updateSourceが管理者のみのときerrorMessageは管理者権限の文面になる`() = runTest {
        var admin = false
        val sources = SourcesHarness(testScheduler) { admin }
        val viewModel = newViewModel(sources = sources)

        viewModel.updateSource(tcUrl, "Renamed", tcUrl)
        assertEquals(adminOnlyMessage, viewModel.errorMessage.value)

        admin = true
        sources.api.onUpdateSource = { _, _, _ -> throw ApiException.HttpError(500) }
        viewModel.updateSource(tcUrl, "Renamed", tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)

        admin = false
        viewModel.updateSource(tcUrl, "Renamed", tcUrl)
        assertEquals(adminOnlyMessage, viewModel.errorMessage.value)

        sources.api.onAddSource = { _, _ -> throw ApiException.HttpError(500) }
        viewModel.addSource("New", "https://new.example.com/rss")
        assertEquals(failureMessage, viewModel.errorMessage.value)

        viewModel.updateSource(tcUrl, "Renamed", tcUrl)
        assertEquals(adminOnlyMessage, viewModel.errorMessage.value)

        sources.api.onRemoveSource = { throw ApiException.HttpError(500) }
        viewModel.removeSource(tcUrl)
        assertEquals(failureMessage, viewModel.errorMessage.value)
    }

    // --- おすすめサイト ---

    @Test
    fun loadFeaturedSites成功でfeaturedSitesが更新される() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchFeaturedSites = { listOf(featuredSite) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadFeaturedSites()

        assertEquals(listOf(FeaturedSiteGroup("tech", listOf(featuredSite))), viewModel.featuredSites.value)
        assertFalse(viewModel.featuredSitesLoadFailed.value)
    }

    @Test
    fun loadFeaturedSites失敗でfeaturedSitesLoadFailedが立つ() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadFeaturedSites()

        assertTrue(viewModel.featuredSites.value.isEmpty())
        assertTrue(viewModel.featuredSitesLoadFailed.value)
        // 完全サイレントにしない一方、アラート用の errorMessage は汚さない（iOS 同型仕様）。
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-115 loadFeaturedSitesはerrorMessageを変えない`() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchSources = { throw ApiException.HttpError(500) }
        val viewModel = newViewModel(sources = sources)
        viewModel.loadSources()
        assertEquals(failureMessage, viewModel.errorMessage.value)

        sources.api.onFetchFeaturedSites = { listOf(featuredSite) }
        viewModel.loadFeaturedSites()
        assertEquals(failureMessage, viewModel.errorMessage.value)

        sources.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        viewModel.loadFeaturedSites()
        assertEquals(failureMessage, viewModel.errorMessage.value)
    }

    @Test
    fun `android-dec-115 loadFeaturedSites失敗は前の一覧を空にして失敗のフラグを立てる`() = runTest {
        val siteA = featuredSite
        val siteB = FeaturedSiteEntry("2", "SiteB", "https://b.example.com/rss", null, "business")
        val sources = SourcesHarness(testScheduler) { false }
        val viewModel = newViewModel(sources = sources)

        sources.api.onFetchFeaturedSites = { listOf(siteA) }
        viewModel.loadFeaturedSites()
        assertEquals(listOf(FeaturedSiteGroup("tech", listOf(siteA))), viewModel.featuredSites.value)

        sources.api.onFetchFeaturedSites = { listOf(siteB) }
        viewModel.loadFeaturedSites()
        assertEquals(listOf(FeaturedSiteGroup("business", listOf(siteB))), viewModel.featuredSites.value)

        sources.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        viewModel.loadFeaturedSites()
        assertTrue(viewModel.featuredSites.value.isEmpty())
        assertTrue(viewModel.featuredSitesLoadFailed.value)

        sources.api.onFetchFeaturedSites = { listOf(siteA) }
        viewModel.loadFeaturedSites()
        assertEquals(listOf(FeaturedSiteGroup("tech", listOf(siteA))), viewModel.featuredSites.value)
        assertFalse(viewModel.featuredSitesLoadFailed.value)
    }

    @Test
    fun `android-dec-115 loadFeaturedSitesはカテゴリの順の束を公開する`() = runTest {
        val business = FeaturedSiteEntry("b", "B", "https://b.example.com/rss", null, "business")
        val tech = FeaturedSiteEntry("t", "T", "https://t.example.com/rss", null, "tech")
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchFeaturedSites = { listOf(business, tech) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadFeaturedSites()

        assertEquals(
            listOf(FeaturedSiteGroup("tech", listOf(tech)), FeaturedSiteGroup("business", listOf(business))),
            viewModel.featuredSites.value,
        )
    }

    // --- 生成クォータ ---

    @Test
    fun loadGenerationQuota成功でgenerationQuotaが更新される() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 2, 3) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadGenerationQuota()

        assertEquals(GenerationQuota.Limited(5, 2, 3), viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)
    }

    @Test
    fun loadGenerationQuota無制限はlimitゼロremainingNullのまま反映される() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchGenerationQuota = { GenerationQuota.Unlimited(10) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadGenerationQuota()

        assertEquals(GenerationQuota.Unlimited(10), viewModel.generationQuota.value)
    }

    @Test
    fun loadGenerationQuotaは404でgracefulにnullとなりLoadFailedは立たない() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchGenerationQuota = { throw ApiException.NotFound() }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadGenerationQuota()

        assertNull(viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)
    }

    @Test
    fun loadGenerationQuotaは404以外の失敗でLoadFailedが立つ() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        sources.api.onFetchGenerationQuota = { throw ApiException.HttpError(500) }
        val viewModel = newViewModel(sources = sources)

        viewModel.loadGenerationQuota()

        assertNull(viewModel.generationQuota.value)
        assertTrue(viewModel.generationQuotaLoadFailed.value)
    }

    @Test
    fun `android-dec-115 loadGenerationQuotaは結果ごとに上限とフラグを書き直す`() = runTest {
        val sources = SourcesHarness(testScheduler) { false }
        val viewModel = newViewModel(sources = sources)

        sources.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 2, 3) }
        viewModel.loadGenerationQuota()
        assertEquals(GenerationQuota.Limited(5, 2, 3), viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 4, 1) }
        viewModel.loadGenerationQuota()
        assertEquals(GenerationQuota.Limited(5, 4, 1), viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { throw ApiException.HttpError(500) }
        viewModel.loadGenerationQuota()
        assertNull(viewModel.generationQuota.value)
        assertTrue(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 2, 3) }
        viewModel.loadGenerationQuota()
        assertEquals(GenerationQuota.Limited(5, 2, 3), viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { throw ApiException.NotFound() }
        viewModel.loadGenerationQuota()
        assertNull(viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { throw ApiException.HttpError(500) }
        viewModel.loadGenerationQuota()
        assertNull(viewModel.generationQuota.value)
        assertTrue(viewModel.generationQuotaLoadFailed.value)

        sources.api.onFetchGenerationQuota = { throw ApiException.NotFound() }
        viewModel.loadGenerationQuota()
        assertNull(viewModel.generationQuota.value)
        assertFalse(viewModel.generationQuotaLoadFailed.value)
    }

    // --- 聴取ストリーク ---

    @Test
    fun loadListeningStreak成功でlisteningStreakが更新される() = runTest {
        val viewModel = newViewModel(
            learningApi = FakeLearningApi().apply { onFetchStreak = { StreakRecord(3, true, "2026-07-08") } },
        )

        viewModel.loadListeningStreak()

        val streak = checkNotNull(viewModel.listeningStreak.value)
        assertEquals(3, streak.days)
        assertTrue(streak.todayListened)
        assertEquals("2026-07-08", streak.lastListenedDay)
        assertFalse(viewModel.listeningStreakLoadFailed.value)
    }

    @Test
    fun loadListeningStreakは404でgracefulにnullとなりLoadFailedは立たない() = runTest {
        val viewModel = newViewModel(
            learningApi = FakeLearningApi().apply { onFetchStreak = { throw ApiException.NotFound() } },
        )

        viewModel.loadListeningStreak()

        assertNull(viewModel.listeningStreak.value)
        assertFalse(viewModel.listeningStreakLoadFailed.value)
    }

    @Test
    fun loadListeningStreakは404以外の失敗でLoadFailedが立つ() = runTest {
        val viewModel = newViewModel(
            learningApi = FakeLearningApi().apply { onFetchStreak = { throw ApiException.HttpError(500) } },
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

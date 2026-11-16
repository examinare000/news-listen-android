package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.onboarding.app.OnboardingCommands
import com.rioikeda.newslisten.onboarding.ui.OnboardingViewModel
import com.rioikeda.newslisten.settings.app.FakeSourcesApi
import com.rioikeda.newslisten.settings.app.FeaturedSiteGroup
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.app.SourcesState
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingViewModel] の挙動検証。ViewModel は入口（[SourcesCommands]・[SourcesQueries]・[OnboardingCommands]）
 * への中継と、読み込み中・失敗の文言・取得した一覧の持ち方だけを持つ（android-dec-09・android-dec-115）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Onboarding/OnboardingSourcesViewModel.swift、
 * ios/NewsListenApp/NewsListenApp/AppState.swift:180-200（refreshOnboardingStatus/completeOnboarding）。
 */
class OnboardingViewModelTest {

    private class Harness(scheduler: TestCoroutineScheduler) {
        val api = FakeSourcesApi()
        val state = SourcesState(StandardTestDispatcher(scheduler))
        val sourcesCommands = SourcesCommands(api, state, { false })
        val sourcesQueries = SourcesQueries(api, state)
        val onboardingCommands = OnboardingCommands(api, StandardTestDispatcher(scheduler))
        val viewModel = OnboardingViewModel(
            sourcesCommands = sourcesCommands,
            sourcesQueries = sourcesQueries,
            onboardingCommands = onboardingCommands,
            dispatcher = StandardTestDispatcher(scheduler),
        )
    }

    private val site = FeaturedSiteEntry(
        id = "1",
        name = "Site",
        url = "https://site.example.com/rss",
        description = null,
        category = null,
    )
    private val otherSite = FeaturedSiteEntry(
        id = "2",
        name = "Other",
        url = "https://other.example.com/rss",
        description = null,
        category = "business",
    )
    private val subscribeFailure = "購読に失敗しました。もう一度お試しください。"

    // --- load（おすすめサイト取得） ---

    @Test
    fun load成功でfeaturedSitesが更新される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { listOf(site) }

        h.viewModel.load()

        assertEquals(listOf(FeaturedSiteGroup("tech", listOf(site))), h.viewModel.featuredSites.value)
        assertNull(h.viewModel.loadError.value)
    }

    @Test
    fun load失敗でloadErrorが設定される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }

        h.viewModel.load()

        assertEquals("おすすめサイトの取得に失敗しました", h.viewModel.loadError.value)
        assertFalse(h.viewModel.isLoading.value)
    }

    @Test
    fun `android-dec-115 load失敗は前の一覧を保ちloadErrorを立てる`() = runTest {
        val h = Harness(testScheduler)
        val siteB = FeaturedSiteEntry("3", "SiteB", "https://b.example.com/rss", null, "sports")

        h.api.onFetchFeaturedSites = { listOf(site) }
        h.viewModel.load()
        h.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        h.viewModel.load()
        assertEquals(listOf(FeaturedSiteGroup("tech", listOf(site))), h.viewModel.featuredSites.value)
        assertTrue(h.viewModel.loadError.value != null)

        h.api.onFetchFeaturedSites = { listOf(siteB) }
        h.viewModel.load()
        assertEquals(listOf(FeaturedSiteGroup("sports", listOf(siteB))), h.viewModel.featuredSites.value)
        assertNull(h.viewModel.loadError.value)
    }

    @Test
    fun `android-dec-115 loadFeaturedSitesはカテゴリの順の束を公開する`() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { listOf(otherSite, site) }

        h.viewModel.load()

        assertEquals(
            listOf(FeaturedSiteGroup("tech", listOf(site)), FeaturedSiteGroup("business", listOf(otherSite))),
            h.viewModel.featuredSites.value,
        )
    }

    @Test
    fun `android-dec-115 loadは取得の間だけisLoadingを立て開始時にloadErrorを消す`() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        h.viewModel.load()
        assertTrue(h.viewModel.loadError.value != null)
        var loadingDuringFetch: Boolean? = null
        var errorDuringFetch: String? = "unset"
        h.api.onFetchFeaturedSites = {
            loadingDuringFetch = h.viewModel.isLoading.value
            errorDuringFetch = h.viewModel.loadError.value
            listOf(site)
        }

        h.viewModel.load()

        assertEquals(true, loadingDuringFetch)
        assertNull(errorDuringFetch)
        assertFalse(h.viewModel.isLoading.value)
    }

    // --- subscribe（購読） ---

    @Test
    fun subscribe成功でaddedIdsに追加される() = runTest {
        val h = Harness(testScheduler)

        h.viewModel.subscribe(site)

        assertEquals(setOf("1"), h.viewModel.addedIds.value)
        assertNull(h.viewModel.subscribeError.value)
    }

    @Test
    fun subscribeで409はaddedIds扱いになる() = runTest {
        val h = Harness(testScheduler)
        h.api.onAddSource = { _, _ -> throw ApiException.Conflict() }

        h.viewModel.subscribe(site)

        assertEquals(setOf("1"), h.viewModel.addedIds.value)
        assertNull(h.viewModel.subscribeError.value)
    }

    @Test
    fun subscribeで409以外はsubscribeErrorが設定されaddedIdsに追加されない() = runTest {
        val h = Harness(testScheduler)
        h.api.onAddSource = { _, _ -> throw ApiException.HttpError(500) }

        h.viewModel.subscribe(site)

        assertTrue(h.viewModel.addedIds.value.isEmpty())
        assertEquals(subscribeFailure, h.viewModel.subscribeError.value)
    }

    @Test
    fun `android-dec-115 subscribeの成功と409はそれまでのaddedIdsに足す`() = runTest {
        val success = Harness(testScheduler)
        success.viewModel.subscribe(site)
        success.viewModel.subscribe(otherSite)
        assertEquals(setOf("1", "2"), success.viewModel.addedIds.value)

        val conflict = Harness(testScheduler)
        conflict.viewModel.subscribe(site)
        conflict.api.onAddSource = { _, _ -> throw ApiException.Conflict() }
        conflict.viewModel.subscribe(otherSite)
        assertEquals(setOf("1", "2"), conflict.viewModel.addedIds.value)
    }

    @Test
    fun `android-dec-115 subscribeの失敗はそれまでのaddedIdsを保つ`() = runTest {
        val h = Harness(testScheduler)
        h.viewModel.subscribe(site)
        assertEquals(setOf("1"), h.viewModel.addedIds.value)
        h.api.onAddSource = { _, _ -> throw ApiException.HttpError(500) }

        h.viewModel.subscribe(otherSite)

        assertEquals(setOf("1"), h.viewModel.addedIds.value)
        assertEquals(subscribeFailure, h.viewModel.subscribeError.value)
    }

    @Test
    fun `android-dec-115 subscribeは送信の前にsubscribeErrorを消す`() = runTest {
        val h = Harness(testScheduler)
        h.api.onAddSource = { _, _ -> throw ApiException.HttpError(500) }
        h.viewModel.subscribe(site)
        assertTrue(h.viewModel.subscribeError.value != null)
        var errorDuringSend: String? = "unset"
        h.api.onAddSource = { _, _ ->
            errorDuringSend = h.viewModel.subscribeError.value
            emptyList()
        }

        h.viewModel.subscribe(site)

        assertNull(errorDuringSend)
    }

    // --- 入口への受け渡しと中継 ---

    @Test
    fun `android-dec-115 onboardingCompletedは入口の同名と同一で入口の操作を中継する`() = runTest {
        val h = Harness(testScheduler)
        assertSame(h.onboardingCommands.onboardingCompleted, h.viewModel.onboardingCompleted)
        assertNull(h.viewModel.onboardingCompleted.value)

        h.api.onFetchOnboardingStatus = { false }
        h.viewModel.refreshOnboardingStatus()
        assertEquals(false, h.viewModel.onboardingCompleted.value)

        h.viewModel.finish()
        assertEquals(1, h.api.completeOnboardingCallCount)
        assertEquals(true, h.viewModel.onboardingCompleted.value)
    }
}

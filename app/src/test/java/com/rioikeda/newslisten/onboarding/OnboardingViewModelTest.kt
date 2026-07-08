package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingViewModel] の挙動検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Onboarding/OnboardingSourcesViewModel.swift、
 * ios/NewsListenApp/NewsListenApp/AppState.swift:180-200（refreshOnboardingStatus/completeOnboarding）。
 * フェーズ13（issue #140 P13）。
 */
class OnboardingViewModelTest {

    private fun TestScope.newViewModel(apiClient: ApiClient = FakeApiClient()): OnboardingViewModel =
        OnboardingViewModel(
            apiClient = apiClient,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private val site = FeaturedSite(id = "site-1", name = "NHK", url = "https://example.com/nhk.xml")

    // --- load（おすすめサイト取得） ---

    @Test
    fun load成功でfeaturedSitesが更新される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchFeaturedSites = { FeaturedSitesResponse(listOf(site)) }),
        )

        viewModel.load()

        assertEquals(listOf(site), viewModel.featuredSites.value)
        assertNull(viewModel.loadError.value)
    }

    @Test
    fun load失敗でloadErrorが設定される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchFeaturedSites = { throw ApiException.HttpError(500) }),
        )

        viewModel.load()

        assertTrue(viewModel.featuredSites.value.isEmpty())
        assertTrue(viewModel.loadError.value != null)
    }

    // --- subscribe（購読） ---

    @Test
    fun subscribe成功でaddedIdsに追加される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCreateSource = { _, _ -> RssSourcesResponse(emptyList()) }),
        )

        viewModel.subscribe(site)

        assertEquals(setOf("site-1"), viewModel.addedIds.value)
        assertNull(viewModel.subscribeError.value)
    }

    @Test
    fun subscribeで409はaddedIds扱いになる() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCreateSource = { _, _ -> throw ApiException.HttpError(409) }),
        )

        viewModel.subscribe(site)

        assertEquals(setOf("site-1"), viewModel.addedIds.value)
        assertNull(viewModel.subscribeError.value)
    }

    @Test
    fun subscribeで409以外はsubscribeErrorが設定されaddedIdsに追加されない() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCreateSource = { _, _ -> throw ApiException.HttpError(500) }),
        )

        viewModel.subscribe(site)

        assertTrue(viewModel.addedIds.value.isEmpty())
        assertTrue(viewModel.subscribeError.value != null)
    }

    // --- refreshOnboardingStatus（起動時の状態取得） ---

    @Test
    fun refreshOnboardingStatus成功でonboardingCompletedにサーバー値が反映される() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchOnboardingStatus = { OnboardingStatusResponse(onboardingCompleted = false) }),
        )

        viewModel.refreshOnboardingStatus()

        assertEquals(false, viewModel.onboardingCompleted.value)
    }

    @Test
    fun refreshOnboardingStatus失敗時は行き止まり防止のためtrue扱いになる() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onFetchOnboardingStatus = { throw ApiException.HttpError(500) }),
        )

        viewModel.refreshOnboardingStatus()

        assertEquals(true, viewModel.onboardingCompleted.value)
    }

    @Test
    fun 取得前のonboardingCompletedはnull() = runTest {
        val viewModel = newViewModel()

        assertNull(viewModel.onboardingCompleted.value)
    }

    // --- finish（完了/スキップ） ---

    @Test
    fun finish成功でcompleteOnboardingが呼ばれonboardingCompletedがtrueになる() = runTest {
        var completeCalled = false
        val viewModel = newViewModel(
            apiClient = FakeApiClient(
                onCompleteOnboarding = {
                    completeCalled = true
                    OnboardingStatusResponse(onboardingCompleted = true)
                },
            ),
        )

        viewModel.finish()

        assertTrue(completeCalled)
        assertEquals(true, viewModel.onboardingCompleted.value)
    }

    @Test
    fun finishはcompleteOnboarding失敗でもベストエフォートでonboardingCompletedをtrueにする() = runTest {
        val viewModel = newViewModel(
            apiClient = FakeApiClient(onCompleteOnboarding = { throw ApiException.HttpError(500) }),
        )

        viewModel.finish()

        assertEquals(true, viewModel.onboardingCompleted.value)
    }
}

package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** `SourcesApiAdapter` は `ApiClient` の DTO を domain の型へ写し、例外は捕まえず透過する（android-dec-73・android-dec-116）。 */
class SourcesApiAdapterTest {

    private class StubApiClient : BaseFakeApiClient() {
        var sources = RssSourcesResponse(emptyList())
        var featured = FeaturedSitesResponse(emptyList())
        var quota = GenerationQuotaResponse(limit = 0, used = 0, remaining = null, resetAt = "2026-07-09T00:00:00Z")
        var onboarding = OnboardingStatusResponse(onboardingCompleted = false)
        var failure: Throwable? = null

        var fetchSourcesCount = 0
        var fetchFeaturedCount = 0
        var fetchQuotaCount = 0
        var fetchOnboardingCount = 0
        var completeOnboardingCount = 0
        val created = mutableListOf<Pair<String, String>>()
        val updated = mutableListOf<Triple<String, String, String>>()
        val deleted = mutableListOf<String>()

        override suspend fun fetchSources(): RssSourcesResponse {
            fetchSourcesCount++
            failure?.let { throw it }
            return sources
        }

        override suspend fun createSource(name: String, url: String): RssSourcesResponse {
            created += name to url
            failure?.let { throw it }
            return sources
        }

        override suspend fun updateSource(oldUrl: String, name: String, url: String): RssSourcesResponse {
            updated += Triple(oldUrl, name, url)
            failure?.let { throw it }
            return sources
        }

        override suspend fun deleteSource(url: String): RssSourcesResponse {
            deleted += url
            failure?.let { throw it }
            return sources
        }

        override suspend fun fetchFeaturedSites(): FeaturedSitesResponse {
            fetchFeaturedCount++
            failure?.let { throw it }
            return featured
        }

        override suspend fun fetchGenerationQuota(): GenerationQuotaResponse {
            fetchQuotaCount++
            failure?.let { throw it }
            return quota
        }

        override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse {
            fetchOnboardingCount++
            failure?.let { throw it }
            return onboarding
        }

        override suspend fun completeOnboarding(): OnboardingStatusResponse {
            completeOnboardingCount++
            failure?.let { throw it }
            return onboarding
        }
    }

    @Test
    fun fetchSourcesはnameとurlをRssSourceEntryへ順序を保って写し1回呼ぶ() = runTest {
        val client = StubApiClient().apply {
            sources = RssSourcesResponse(listOf(RssSource("A", "https://a.example.com"), RssSource("B", "https://b.example.com")))
        }

        val result = SourcesApiAdapter(client).fetchSources()

        assertEquals(listOf(RssSourceEntry("A", "https://a.example.com"), RssSourceEntry("B", "https://b.example.com")), result)
        assertEquals(1, client.fetchSourcesCount)
    }

    @Test
    fun fetchFeaturedSitesはidとnameとurlとdescriptionと生のcategoryの5項目を写し1回呼ぶ() = runTest {
        val client = StubApiClient().apply {
            featured = FeaturedSitesResponse(
                listOf(
                    FeaturedSite("1", "NHK", "https://nhk.example.com", thumbnailUrl = "https://t.example.com/1.png", description = "desc", order = 3, category = "Unknown-Raw"),
                    FeaturedSite("2", "BBC", "https://bbc.example.com", category = null),
                ),
            )
        }

        val result = SourcesApiAdapter(client).fetchFeaturedSites()

        assertEquals(
            listOf(
                FeaturedSiteEntry("1", "NHK", "https://nhk.example.com", "desc", "Unknown-Raw"),
                FeaturedSiteEntry("2", "BBC", "https://bbc.example.com", null, null),
            ),
            result,
        )
        assertEquals(1, client.fetchFeaturedCount)
    }

    @Test
    fun fetchGenerationQuotaは上限0をUnlimitedへ写す() = runTest {
        val client = StubApiClient().apply {
            quota = GenerationQuotaResponse(limit = 0, used = 10, remaining = null, resetAt = "2026-07-09T00:00:00Z")
        }

        assertEquals(GenerationQuota.Unlimited(10), SourcesApiAdapter(client).fetchGenerationQuota())
        assertEquals(1, client.fetchQuotaCount)
    }

    @Test
    fun fetchGenerationQuotaは上限つきをLimitedへ写しremainingがnullなら0にする() = runTest {
        val client = StubApiClient()
        val adapter = SourcesApiAdapter(client)

        client.quota = GenerationQuotaResponse(limit = 5, used = 2, remaining = 3, resetAt = "2026-07-09T00:00:00Z")
        assertEquals(GenerationQuota.Limited(5, 2, 3), adapter.fetchGenerationQuota())

        client.quota = GenerationQuotaResponse(limit = 5, used = 5, remaining = null, resetAt = "2026-07-09T00:00:00Z")
        assertEquals(GenerationQuota.Limited(5, 5, 0), adapter.fetchGenerationQuota())
    }

    @Test
    fun fetchOnboardingStatusはonboardingCompletedを写し1回呼ぶ() = runTest {
        val client = StubApiClient().apply { onboarding = OnboardingStatusResponse(onboardingCompleted = true) }

        assertEquals(true, SourcesApiAdapter(client).fetchOnboardingStatus())
        assertEquals(1, client.fetchOnboardingCount)
    }

    @Test
    fun addSourceとupdateSourceは引数を渡し応答の一覧を写す() = runTest {
        val client = StubApiClient().apply { sources = RssSourcesResponse(listOf(RssSource("New", "https://new.example.com"))) }
        val adapter = SourcesApiAdapter(client)

        val added = adapter.addSource("New", "https://new.example.com")
        val updated = adapter.updateSource("https://old.example.com", "New", "https://new.example.com")

        assertEquals(listOf(RssSourceEntry("New", "https://new.example.com")), added)
        assertEquals(listOf(RssSourceEntry("New", "https://new.example.com")), updated)
        assertEquals(listOf("New" to "https://new.example.com"), client.created)
        assertEquals(listOf(Triple("https://old.example.com", "New", "https://new.example.com")), client.updated)
    }

    @Test
    fun removeSourceはdeleteSourceを1回呼び応答を捨てる() = runTest {
        val client = StubApiClient()

        val result: Unit = SourcesApiAdapter(client).removeSource("https://x.example.com")

        assertEquals(Unit, result)
        assertEquals(listOf("https://x.example.com"), client.deleted)
    }

    @Test
    fun completeOnboardingは1回呼ぶ() = runTest {
        val client = StubApiClient()

        SourcesApiAdapter(client).completeOnboarding()

        assertEquals(1, client.completeOnboardingCount)
    }

    @Test
    fun 八操作とも例外は捕まえずに同じinstanceを投げる() = runTest {
        val failure = ApiException.HttpError(500)
        val client = StubApiClient().apply { this.failure = failure }
        val adapter = SourcesApiAdapter(client)

        val operations: List<suspend () -> Unit> = listOf(
            { adapter.fetchSources() },
            { adapter.addSource("n", "u") },
            { adapter.updateSource("o", "n", "u") },
            { adapter.removeSource("u") },
            { adapter.fetchFeaturedSites() },
            { adapter.fetchGenerationQuota() },
            { adapter.fetchOnboardingStatus() },
            { adapter.completeOnboarding() },
        )
        for ((index, operation) in operations.withIndex()) {
            try {
                operation()
                fail("操作 $index の例外が透過されるはず")
            } catch (e: ApiException) {
                assertSame("操作 $index", failure, e)
            }
        }
    }
}

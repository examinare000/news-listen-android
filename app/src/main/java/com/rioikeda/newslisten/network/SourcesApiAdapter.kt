package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.settings.app.SourcesApi
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry

/** [ApiClient] の Sources 系の操作を包み、DTO を domain の型に変える。例外は呼び手へそのまま伝える。 */
internal class SourcesApiAdapter(private val apiClient: ApiClient) : SourcesApi {
    override suspend fun fetchSources(): List<RssSourceEntry> = apiClient.fetchSources().sources.map(::entryOf)

    override suspend fun addSource(name: String, url: String): List<RssSourceEntry> =
        apiClient.createSource(name, url).sources.map(::entryOf)

    override suspend fun updateSource(oldUrl: String, name: String, url: String): List<RssSourceEntry> =
        apiClient.updateSource(oldUrl, name, url).sources.map(::entryOf)

    override suspend fun removeSource(url: String) {
        apiClient.deleteSource(url)
    }

    override suspend fun fetchFeaturedSites(): List<FeaturedSiteEntry> =
        apiClient.fetchFeaturedSites().sites.map {
            FeaturedSiteEntry(it.id, it.name, it.url, it.description, it.category)
        }

    override suspend fun fetchGenerationQuota(): GenerationQuota =
        apiClient.fetchGenerationQuota().let { GenerationQuota.of(it.limit, it.used, it.remaining) }

    override suspend fun fetchOnboardingStatus(): Boolean = apiClient.fetchOnboardingStatus().onboardingCompleted

    override suspend fun completeOnboarding() {
        apiClient.completeOnboarding()
    }

    private fun entryOf(source: RssSource) = RssSourceEntry(source.name, source.url)
}

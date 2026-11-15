package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry

/** RSS ソース・おすすめサイト・生成の上限・オンボーディングの状態の port。失敗は `ApiException` のまま伝える。 */
interface SourcesApi {
    suspend fun fetchSources(): List<RssSourceEntry>

    suspend fun addSource(name: String, url: String): List<RssSourceEntry>

    suspend fun updateSource(oldUrl: String, name: String, url: String): List<RssSourceEntry>

    suspend fun removeSource(url: String)

    suspend fun fetchFeaturedSites(): List<FeaturedSiteEntry>

    suspend fun fetchGenerationQuota(): GenerationQuota

    suspend fun fetchOnboardingStatus(): Boolean

    suspend fun completeOnboarding()
}

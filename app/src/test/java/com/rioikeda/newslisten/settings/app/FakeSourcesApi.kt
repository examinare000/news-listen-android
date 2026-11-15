package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry

/**
 * 呼出を記録する [SourcesApi] の fake。応答と失敗は差し替えられる。
 * 記録は応答の lambda を呼ぶ前に行うので、失敗した呼出も残る。
 */
class FakeSourcesApi : SourcesApi {
    var onFetchSources: suspend () -> List<RssSourceEntry> = { emptyList() }
    var onAddSource: suspend (String, String) -> List<RssSourceEntry> = { _, _ -> emptyList() }
    var onUpdateSource: suspend (String, String, String) -> List<RssSourceEntry> = { _, _, _ -> emptyList() }
    var onRemoveSource: suspend (String) -> Unit = {}
    var onFetchFeaturedSites: suspend () -> List<FeaturedSiteEntry> = { emptyList() }
    var onFetchGenerationQuota: suspend () -> GenerationQuota = { GenerationQuota.Unlimited(0) }
    var onFetchOnboardingStatus: suspend () -> Boolean = { true }
    var onCompleteOnboarding: suspend () -> Unit = {}

    val addCalls: MutableList<Pair<String, String>> = mutableListOf()
    val removeCalls: MutableList<String> = mutableListOf()

    var updateSourceCallCount: Int = 0
        private set
    var completeOnboardingCallCount: Int = 0
        private set

    /** 書込（`addSource`・`updateSource`・`removeSource`・`completeOnboarding`）の呼出の順の記録。query が書かないことの確認に使う。 */
    val writeCalls: MutableList<String> = mutableListOf()

    override suspend fun fetchSources(): List<RssSourceEntry> = onFetchSources()

    override suspend fun addSource(name: String, url: String): List<RssSourceEntry> {
        addCalls += name to url
        writeCalls += "addSource"
        return onAddSource(name, url)
    }

    override suspend fun updateSource(oldUrl: String, name: String, url: String): List<RssSourceEntry> {
        updateSourceCallCount++
        writeCalls += "updateSource"
        return onUpdateSource(oldUrl, name, url)
    }

    override suspend fun removeSource(url: String) {
        removeCalls += url
        writeCalls += "removeSource"
        onRemoveSource(url)
    }

    override suspend fun fetchFeaturedSites(): List<FeaturedSiteEntry> = onFetchFeaturedSites()

    override suspend fun fetchGenerationQuota(): GenerationQuota = onFetchGenerationQuota()

    override suspend fun fetchOnboardingStatus(): Boolean = onFetchOnboardingStatus()

    override suspend fun completeOnboarding() {
        completeOnboardingCallCount++
        writeCalls += "completeOnboarding"
        onCompleteOnboarding()
    }
}

/** order §8 の fixture。 */
val techCrunch = RssSourceEntry("TechCrunch", "https://tc.example.com/rss")

/** order §8 の fixture。 */
val featuredSite = FeaturedSiteEntry(
    id = "1",
    name = "Site",
    url = "https://site.example.com/rss",
    description = null,
    category = null,
)

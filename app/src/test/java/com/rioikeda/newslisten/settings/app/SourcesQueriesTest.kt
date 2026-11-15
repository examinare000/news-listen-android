package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SourcesQueries] の挙動（android-query-sources-01）。query は port の書込を呼ばない。 */
class SourcesQueriesTest {

    private class Harness(scheduler: TestCoroutineScheduler) {
        val api = FakeSourcesApi()
        val state = SourcesState(StandardTestDispatcher(scheduler))
        val commands = SourcesCommands(api, state, { true })
        val queries = SourcesQueries(api, state)
    }

    private fun site(id: String, category: String?) =
        FeaturedSiteEntry(id, "Site-$id", "https://site.example.com/$id", description = null, category = category)

    @Test
    fun loadSources成功でsourcesが更新される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }

        val load = h.queries.loadSources()

        assertEquals(SourcesLoad.Loaded, load)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun loadSourcesは応答で置き換え足さない() = runTest {
        val h = Harness(testScheduler)
        val a = RssSourceEntry("A", "https://a.example.com/rss")
        val b = RssSourceEntry("B", "https://b.example.com/rss")
        val c = RssSourceEntry("C", "https://c.example.com/rss")
        h.api.onFetchSources = { listOf(a, b) }
        h.queries.loadSources()
        h.api.onFetchSources = { listOf(c) }

        val load = h.queries.loadSources()

        assertEquals(SourcesLoad.Loaded, load)
        assertEquals(listOf(c), h.queries.sources.value)
    }

    @Test
    fun loadSources失敗はFailedで一覧に触らない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onFetchSources = { throw ApiException.HttpError(500) }

        val load = h.queries.loadSources()

        assertEquals(SourcesLoad.Failed, load)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun loadFeaturedSites成功でfeaturedSitesが更新される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { listOf(featuredSite) }

        val load = h.queries.loadFeaturedSites()

        assertEquals(FeaturedSitesLoad.Loaded(listOf(FeaturedSiteGroup("tech", listOf(featuredSite)))), load)
    }

    @Test
    fun loadFeaturedSitesはカテゴリの順で束ね同じカテゴリ内は返却順でnullはtechに入れ空のカテゴリを出さない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        val b = site("b", "business")
        val t = site("t", "tech")
        val n = site("n", null)
        val s = site("s", "sports")
        val t2 = site("t2", "tech")
        h.api.onFetchFeaturedSites = { listOf(b, t, n, s, t2) }

        val load = h.queries.loadFeaturedSites()

        assertEquals(
            FeaturedSitesLoad.Loaded(
                listOf(
                    FeaturedSiteGroup("tech", listOf(t, n, t2)),
                    FeaturedSiteGroup("business", listOf(b)),
                    FeaturedSiteGroup("sports", listOf(s)),
                ),
            ),
            load,
        )
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun loadFeaturedSites失敗はFailedを返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }

        assertEquals(FeaturedSitesLoad.Failed, h.queries.loadFeaturedSites())
    }

    @Test
    fun loadGenerationQuotaは上限つきをLoadedで返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 2, 3) }

        assertEquals(QuotaLoad.Loaded(GenerationQuota.Limited(5, 2, 3)), h.queries.loadGenerationQuota())
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun loadGenerationQuotaは無制限をLoadedで返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchGenerationQuota = { GenerationQuota.Unlimited(10) }

        assertEquals(QuotaLoad.Loaded(GenerationQuota.Unlimited(10)), h.queries.loadGenerationQuota())
    }

    @Test
    fun loadGenerationQuotaは404でHiddenを返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchGenerationQuota = { throw ApiException.NotFound() }

        assertEquals(QuotaLoad.Hidden, h.queries.loadGenerationQuota())
    }

    @Test
    fun loadGenerationQuotaは404以外の失敗でFailedを返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchGenerationQuota = { throw ApiException.HttpError(500) }

        assertEquals(QuotaLoad.Failed, h.queries.loadGenerationQuota())
    }

    @Test
    fun どのloadも成功と失敗のどちらでもportの書込を呼ばない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.api.onFetchFeaturedSites = { listOf(featuredSite) }
        h.api.onFetchGenerationQuota = { GenerationQuota.Limited(5, 2, 3) }
        h.queries.loadSources()
        h.queries.loadFeaturedSites()
        h.queries.loadGenerationQuota()
        h.api.onFetchSources = { throw ApiException.HttpError(500) }
        h.api.onFetchFeaturedSites = { throw ApiException.HttpError(500) }
        h.api.onFetchGenerationQuota = { throw ApiException.HttpError(500) }
        h.queries.loadSources()
        h.queries.loadFeaturedSites()
        h.queries.loadGenerationQuota()

        assertTrue("query が書込を呼んだ: ${h.api.writeCalls}", h.api.writeCalls.isEmpty())

        // 正方向の対照: 同じ fake でも command は書込を記録する。
        h.commands.addSource("New", "https://new.example.com/rss")
        assertEquals(listOf("addSource"), h.api.writeCalls)
    }
}

package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SourcesCommands] の挙動（android-cmd-sources-01・android-rule-sources-01・android-rule-sources-02）。
 * 一覧は同じ [SourcesState] を共有する [SourcesQueries] から読む（android-dec-114）。
 */
class SourcesCommandsTest {

    private class Harness(scheduler: TestCoroutineScheduler, isAdminProvider: () -> Boolean = { true }) {
        val api = FakeSourcesApi()
        val state = SourcesState(StandardTestDispatcher(scheduler))
        val commands = SourcesCommands(api, state, isAdminProvider)
        val queries = SourcesQueries(api, state)
    }

    private val tcUrl = "https://tc.example.com/rss"
    private val keep = RssSourceEntry("Keep", "https://keep.example.com/rss")
    private val remove = RssSourceEntry("Remove", "https://remove.example.com/rss")

    @Test
    fun addSource成功でサーバ最新一覧がsourcesに反映される() = runTest {
        val h = Harness(testScheduler)
        val url = "https://new.example.com/rss"
        h.api.onAddSource = { _, _ -> listOf(RssSourceEntry("New", url)) }

        val outcome = h.commands.addSource("New", url)

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(RssSourceEntry("New", url)), h.queries.sources.value)
    }

    @Test
    fun addSource失敗は一覧を変えずFailedを返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onAddSource = { _, _ -> throw ApiException.HttpError(400) }

        val outcome = h.commands.addSource("New", "https://new.example.com/rss")

        assertEquals(SourcesOutcome.Failed, outcome)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun addSourceは渡した引数でportを呼び応答の一覧で置き換える() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        val site = RssSourceEntry("Site", "https://site.example.com/rss")
        h.api.onAddSource = { _, _ -> listOf(site) }

        h.commands.addSource("Site", site.url)

        assertEquals(listOf("Site" to site.url), h.api.addCalls)
        assertEquals(listOf(site), h.queries.sources.value)
    }

    @Test
    fun updateSourceはadminなら成功しsourcesが更新される() = runTest {
        val h = Harness(testScheduler, isAdminProvider = { true })
        h.api.onFetchSources = { listOf(RssSourceEntry("Old", tcUrl)) }
        h.queries.loadSources()
        val renamed = RssSourceEntry("Renamed", tcUrl)
        h.api.onUpdateSource = { _, _, _ -> listOf(renamed) }

        val outcome = h.commands.updateSource(tcUrl, "Renamed", tcUrl)

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(renamed), h.queries.sources.value)
    }

    @Test
    fun updateSourceは非adminならAPIを呼ばずerrorMessageを設定する() = runTest {
        val h = Harness(testScheduler, isAdminProvider = { false })
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()

        val outcome = h.commands.updateSource(tcUrl, "Renamed", tcUrl)

        assertEquals(SourcesOutcome.AdminOnly, outcome)
        assertEquals(0, h.api.updateSourceCallCount)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun updateSourceの失敗は一覧を変えずFailedを返す() = runTest {
        val h = Harness(testScheduler, isAdminProvider = { true })
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onUpdateSource = { _, _, _ -> throw ApiException.HttpError(500) }

        val outcome = h.commands.updateSource(tcUrl, "Renamed", tcUrl)

        assertEquals(SourcesOutcome.Failed, outcome)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun updateSourceは管理者かどうかを呼出の時点で評価する() = runTest {
        var admin = false
        val h = Harness(testScheduler, isAdminProvider = { admin })
        h.api.onUpdateSource = { _, _, _ -> listOf(RssSourceEntry("Renamed", tcUrl)) }

        val first = h.commands.updateSource(tcUrl, "Renamed", tcUrl)
        assertEquals(SourcesOutcome.AdminOnly, first)
        assertEquals(0, h.api.updateSourceCallCount)

        admin = true
        val second = h.commands.updateSource(tcUrl, "Renamed", tcUrl)

        assertEquals(SourcesOutcome.Done, second)
        assertEquals(1, h.api.updateSourceCallCount)
    }

    @Test
    fun removeSource成功でsourcesから取り除かれる() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(keep, remove) }
        h.queries.loadSources()

        val outcome = h.commands.removeSource(remove.url)

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(keep), h.queries.sources.value)
    }

    @Test
    fun removeSource失敗は一覧を変えずFailedを返す() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(keep, remove) }
        h.queries.loadSources()
        h.api.onRemoveSource = { throw ApiException.HttpError(500) }

        val outcome = h.commands.removeSource(keep.url)

        assertEquals(SourcesOutcome.Failed, outcome)
        assertEquals(listOf(keep, remove), h.queries.sources.value)
    }

    @Test
    fun removeSourceは一覧に無いurlの成功で一覧を変えない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(keep, remove) }
        h.queries.loadSources()

        val outcome = h.commands.removeSource("https://absent.example.com/rss")

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(keep, remove), h.queries.sources.value)
    }

    @Test
    fun subscribeFeatured成功はDoneでsourcesを書かない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onAddSource = { _, _ -> listOf(RssSourceEntry("Other", "https://other.example.com/rss")) }

        val outcome = h.commands.subscribeFeatured(featuredSite)

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(featuredSite.name to featuredSite.url), h.api.addCalls)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun subscribeFeaturedで409はDoneでsourcesを書かない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onAddSource = { _, _ -> throw ApiException.Conflict() }

        val outcome = h.commands.subscribeFeatured(featuredSite)

        assertEquals(SourcesOutcome.Done, outcome)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun subscribeFeaturedで409以外はFailedでsourcesを書かない() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()
        h.api.onAddSource = { _, _ -> throw ApiException.HttpError(500) }

        val outcome = h.commands.subscribeFeatured(featuredSite)

        assertEquals(SourcesOutcome.Failed, outcome)
        assertEquals(listOf(techCrunch), h.queries.sources.value)
    }

    @Test
    fun 入口の戻り値はSourcesOutcomeで固定される() = runTest {
        val h = Harness(testScheduler)
        h.api.onFetchSources = { listOf(techCrunch) }
        h.queries.loadSources()

        val added: SourcesOutcome = h.commands.addSource("New", "https://new.example.com/rss")
        val updated: SourcesOutcome = h.commands.updateSource(tcUrl, "Renamed", tcUrl)
        val removed: SourcesOutcome = h.commands.removeSource(tcUrl)
        val subscribed: SourcesOutcome = h.commands.subscribeFeatured(featuredSite)

        assertEquals(SourcesOutcome.Done, added)
        assertEquals(SourcesOutcome.Done, updated)
        assertEquals(SourcesOutcome.Done, removed)
        assertEquals(SourcesOutcome.Done, subscribed)
    }
}

package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面が読む [SourcesQueries] の値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 読んだ list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class SourcesQueriesEncapsulationTest {

    @Suppress("UNCHECKED_CAST")
    private fun List<*>.asMutable(): MutableList<Any?> = this as MutableList<Any?>

    private val tc = RssSourceEntry("TechCrunch", "https://tc.example.com/rss")
    private val other = RssSourceEntry("Other", "https://other.example.com/rss")

    @Test
    fun android_check_06_loadSourcesの後にsourcesをMutableListへcastして書き換えても次の読みは変わらない() = runTest {
        val api = FakeSourcesApi().apply { onFetchSources = { mutableListOf(techCrunch, other) } }
        val queries = SourcesQueries(api, SourcesState(StandardTestDispatcher(testScheduler)))
        queries.loadSources()

        // 2 要素以上にする（1 要素の toList() は書き換えられない list で、clear() が例外になる）。書き換えられる list を返す adapter と同じ条件。例外を握ると、コピーが無くても通る空振りになる。
        queries.sources.value.asMutable().clear()

        assertEquals(listOf(tc, other), queries.sources.value)
    }

    @Test
    fun android_check_06_addSourceの後にsourcesをMutableListへcastして書き換えても次の読みは変わらない() = runTest {
        val api = FakeSourcesApi().apply { onAddSource = { _, _ -> mutableListOf(techCrunch, other) } }
        val state = SourcesState(StandardTestDispatcher(testScheduler))
        val queries = SourcesQueries(api, state)
        SourcesCommands(api, state, { true }).addSource("TechCrunch", "https://tc.example.com/rss")

        // 2 要素以上にする（1 要素の toList() は書き換えられない list で、clear() が例外になる）。書き換えられる list を返す adapter と同じ条件。例外を握ると、コピーが無くても通る空振りになる。
        queries.sources.value.asMutable().clear()

        assertEquals(listOf(tc, other), queries.sources.value)
    }

    @Test
    fun android_check_06_loadFeaturedSitesが返した束と束の中のlistを書き換えても次の結果は変わらない() = runTest {
        val api = FakeSourcesApi().apply { onFetchFeaturedSites = { listOf(featuredSite) } }
        val queries = SourcesQueries(api, SourcesState(StandardTestDispatcher(testScheduler)))
        val first = queries.loadFeaturedSites() as FeaturedSitesLoad.Loaded

        runCatching { first.groups.asMutable().clear() }
        runCatching { first.groups.firstOrNull()?.sites?.asMutable()?.clear() }

        val expected = FeaturedSitesLoad.Loaded(
            listOf(
                FeaturedSiteGroup(
                    "tech",
                    listOf(FeaturedSiteEntry("1", "Site", "https://site.example.com/rss", null, null)),
                ),
            ),
        )
        assertEquals(expected, queries.loadFeaturedSites())
    }
}

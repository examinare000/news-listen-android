package com.rioikeda.newslisten.feed.app

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画面が読む [FeedQueries.articles] の値の不変（android-check-06）。比較元は必ずリテラルで書く。
 * 読んだ list を比較元にすると、書き換えと同じインスタンスを見比べて空振りの green になるため。
 */
class FeedQueriesEncapsulationTest {

    @Suppress("UNCHECKED_CAST")
    private fun List<*>.asMutable(): MutableList<Any?> = this as MutableList<Any?>

    @Test
    fun `android-check-06 loadFeedの後にarticlesをMutableListへcastして書き換えても次の読みは変わらない`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) } }
        val state = FeedState(StandardTestDispatcher(testScheduler))
        val queries = FeedQueries(api, state)
        queries.loadFeed()

        runCatching { queries.articles.value.asMutable().clear() }

        assertEquals(listOf("1", "2"), queries.articles.value.map { it.id })
    }

    @Test
    fun `android-check-06 starで保留を積んだ後にarticlesをMutableListへcastして書き換えても次の読みは変わらない`() = runTest {
        val api = FakeFeedApi().apply { onFetchFeed = { listOf(feedArticle("1"), feedArticle("2")) } }
        val state = FeedState(StandardTestDispatcher(testScheduler))
        val queries = FeedQueries(api, state)
        val commands = FeedCommands(api, state)
        queries.loadFeed()
        commands.star("1")

        runCatching { queries.articles.value.asMutable().clear() }

        assertEquals(listOf("2"), queries.articles.value.map { it.id })
    }
}

package com.rioikeda.newslisten.catalog

import com.rioikeda.newslisten.catalog.app.EpisodeCatalog
import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.podcast.FakePodcastApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EpisodeCatalog]（一覧の取得・写し・失敗の保持・取得後の hook）の検証。 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeCatalogTest {

    private fun episode(
        id: String,
        status: String = "completed",
        title: String = "",
        errorMessage: String? = null,
    ): Episode = EpisodeDecoder.decode(
        PodcastResponse(
            id = id,
            type = "daily",
            title = title,
            articleIds = listOf("a1"),
            difficulty = "toeic_600",
            audioUrl = "https://example.com/$id.mp3",
            japaneseIntroText = "intro-$id",
            durationSeconds = 120,
            status = status,
            errorMessage = errorMessage,
            playbackPositionSeconds = 0.0,
            createdAt = "2026-07-01T00:00:00Z",
        ),
    )

    private class Fixture(val catalog: EpisodeCatalog, val synced: MutableList<List<Episode>>)

    private fun TestScope.fixture(api: FakePodcastApi): Fixture {
        val synced = mutableListOf<List<Episode>>()
        val catalog = EpisodeCatalog(
            podcastApi = api,
            dispatcher = StandardTestDispatcher(testScheduler),
            afterRefresh = { synced += it },
        )
        return Fixture(catalog, synced)
    }

    @Test
    fun `android-dec-68 refreshが成功するとepisodesに写りafterRefreshが一覧を1回受ける`() = runTest {
        val list = listOf(episode("1"), episode("2"))
        val f = fixture(FakePodcastApi(onFetchPodcasts = { list }))

        f.catalog.refresh()

        assertEquals(listOf("1", "2"), f.catalog.episodes.value.map { it.episodeId })
        assertEquals(1, f.synced.size)
        assertEquals(list, f.synced.single())
        assertNull(f.catalog.errorMessage.value)
        assertFalse(f.catalog.isLoading.value)
    }

    @Test
    fun `android-dec-68 ApiExceptionで失敗するとerrorMessageに反映され前の一覧とfindが残りafterRefreshは呼ばれない`() = runTest {
        val first = listOf(episode("1"))
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        var fail = false
        val f = fixture(FakePodcastApi(onFetchPodcasts = { if (fail) throw exception else first }))
        f.catalog.refresh()
        fail = true

        f.catalog.refresh()

        assertEquals(exception.message, f.catalog.errorMessage.value)
        assertEquals(listOf("1"), f.catalog.episodes.value.map { it.episodeId })
        assertSame(first.single(), f.catalog.find("1"))
        assertEquals(1, f.synced.size) // 失敗では呼ばれない（最初の成功の 1 回のまま）
        assertFalse(f.catalog.isLoading.value)

        f.catalog.clearError()

        assertNull(f.catalog.errorMessage.value)
        assertEquals(listOf("1"), f.catalog.episodes.value.map { it.episodeId }) // clearError は一覧に触れない
    }

    @Test
    fun `android-dec-68 refreshの実行中はisLoadingが真で終わると偽になり再取得で自分の失敗が消える`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fail = true
        val api = FakePodcastApi(onFetchPodcasts = {
            gate.await()
            if (fail) throw ApiException.NetworkError(RuntimeException("offline")) else listOf(episode("1"))
        })
        val f = fixture(api)
        assertFalse(f.catalog.isLoading.value)

        launch(start = CoroutineStart.UNDISPATCHED) { f.catalog.refresh() }
        runCurrent()
        assertTrue(f.catalog.isLoading.value)

        gate.complete(Unit)
        runCurrent()
        assertFalse(f.catalog.isLoading.value)
        assertTrue(f.catalog.errorMessage.value != null)

        fail = false
        f.catalog.refresh()

        assertNull(f.catalog.errorMessage.value) // 開始時に自分の失敗を消す
        assertEquals(listOf("1"), f.catalog.episodes.value.map { it.episodeId })
    }

    @Test
    fun `Episodeの状態は型でPlayableとGeneratingとFailedに写りcanDownloadはPlayableのときだけ真`() = runTest {
        val f = fixture(
            FakePodcastApi(
                onFetchPodcasts = {
                    listOf(
                        episode("ok"),
                        episode("gen", status = "processing"),
                        episode("ng", status = "failed", errorMessage = "boom"),
                    )
                },
            ),
        )

        f.catalog.refresh()

        val rows = f.catalog.episodes.value.associateBy { it.episodeId }
        assertEquals("Playable", rows.getValue("ok").status.name)
        assertEquals("Generating", rows.getValue("gen").status.name)
        assertEquals("Failed", rows.getValue("ng").status.name)
        assertTrue(rows.getValue("ok").canDownload)
        assertFalse(rows.getValue("gen").canDownload)
        assertFalse(rows.getValue("ng").canDownload)
        assertEquals("toeic_600", rows.getValue("ok").difficultyCode)
        assertEquals(120, rows.getValue("ok").durationSeconds)
        assertEquals("2026-07-01T00:00:00Z", rows.getValue("ok").createdAt)
    }

    @Test
    fun `android-dec-66 EpisodeRowのdisplayTitleは題名の先頭60文字`() = runTest {
        val long = "あ".repeat(61) + "い"
        val f = fixture(FakePodcastApi(onFetchPodcasts = { listOf(episode("long", title = long), episode("short", title = "短い題名")) }))

        f.catalog.refresh()

        val rows = f.catalog.episodes.value.associateBy { it.episodeId }
        assertEquals(60, rows.getValue("long").displayTitle.length)
        assertEquals(long.take(60), rows.getValue("long").displayTitle)
        assertEquals("短い題名", rows.getValue("short").displayTitle)
    }

    @Test
    fun `findは最初の取得の前はnullで成功の後は一覧の要素を返し2回目の成功で入れ替わる`() = runTest {
        val first = listOf(episode("1"), episode("2"))
        val second = listOf(episode("3"))
        var call = 0
        val f = fixture(FakePodcastApi(onFetchPodcasts = { if (call++ == 0) first else second }))
        assertNull(f.catalog.find("1"))

        f.catalog.refresh()
        assertSame(first[0], f.catalog.find("1"))
        assertSame(first[1], f.catalog.find("2"))
        assertNull(f.catalog.find("unknown"))

        f.catalog.refresh()
        assertNull(f.catalog.find("1"))
        assertSame(second[0], f.catalog.find("3"))
    }
}

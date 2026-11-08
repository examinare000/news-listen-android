package com.rioikeda.newslisten.podcast

import com.rioikeda.newslisten.catalog.domain.Episode
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.AudioCacheManager
import com.rioikeda.newslisten.network.EpisodeDecoder
import com.rioikeda.newslisten.network.FakeFileSystem
import com.rioikeda.newslisten.podcast.app.AudioStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineLibrary]（音声キャッシュの主体固定・起動時回収・cancel を所有する capsule）の挙動検証。
 *
 * `download` / `removeDownload` / `cancelDownloads` などの実体は、もとは `PodcastViewModelTest` で
 * 検証していた。ここでは主体（`user_id`）ごとの置き場と、離脱後に完了した書込の扱いを加えて検証する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineLibraryTest {

    private fun podcast(
        id: String = "p1",
        status: String = "completed",
        errorMessage: String? = null,
    ): Episode = EpisodeDecoder.decode(
        PodcastResponse(
            id = id,
            type = "daily",
            articleIds = listOf("a1"),
            difficulty = "toeic_600",
            audioUrl = "https://example.com/$id.mp3",
            japaneseIntroText = "intro",
            durationSeconds = 300,
            status = status,
            errorMessage = errorMessage,
            playbackPositionSeconds = 0.0,
            createdAt = "2026-07-01T00:00:00Z",
        ),
    )

    // 呼出の直前に使う Episode を登録する（android-dec-64: download は id を受け、解決は lookup が行う）。
    private val lookup = FakeEpisodeLookup()

    private suspend fun OfflineLibrary.download(episode: Episode) {
        lookup.register(episode)
        download(episode.id)
    }

    private fun TestScope.newLibrary(
        apiClient: FakePodcastApiClient,
        cacheManager: AudioStore = AudioCacheManager(FakeFileSystem(), baseDir = "/cache"),
        currentSubject: () -> String? = { "u1" },
    ): OfflineLibrary =
        OfflineLibrary(cacheManager, apiClient, lookup, currentSubject, StandardTestDispatcher(testScheduler))

    @Test
    fun `android-dec-64 未知のidのdownloadは保存庫もダウンロード状態も通信も変えずlastErrorはnull`() = runTest {
        val apiClient = FakePodcastApiClient(onFetchPodcast = { error("呼ばれてはならない") })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download("unknown")

        assertTrue(library.downloadingIds.value.isEmpty())
        assertTrue(library.downloadedIds.value.isEmpty())
        assertFalse(cacheManager.isCached("u1", "unknown"))
        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertTrue(apiClient.downloadAudioCalls.isEmpty())
        assertNull(library.lastError.value)
    }

    @Test
    fun `android-dec-64 未知のidのdownloadは直前の失敗を消し既知のidでは保存される`() = runTest {
        val p1 = podcast(id = "p1")
        var fetched: Episode = podcast(id = "p1", status = "processing")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fetched },
            onDownloadAudio = { byteArrayOf(1, 2, 3) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)
        library.download(p1) // 取り直した値が生成中なので、失敗の文言が lastError に残る
        assertNotNull(library.lastError.value)

        library.download("unknown") // 冒頭で lastError を初期化した後に何もしない
        assertNull(library.lastError.value)
        assertTrue(library.downloadedIds.value.isEmpty())

        fetched = p1 // 正方向の対照
        library.download("p1")
        assertTrue(cacheManager.isCached("u1", "p1"))
        assertEquals(setOf("p1"), library.downloadedIds.value)
    }

    /**
     * fetchPodcast を id ごとに止める double。[arm] した時点の gate を、呼ばれたときに待つ。
     * [uncancellable] なら通信の中断を模して、待機中は cancel を受け付けず、抜けた直後に [ensureActive] で確かめる。
     */
    private class FetchGates(private val uncancellable: Boolean = false) {
        private val gates = mutableMapOf<String, CompletableDeferred<Episode>>()

        fun arm(id: String): CompletableDeferred<Episode> =
            CompletableDeferred<Episode>().also { gates[id] = it }

        suspend fun await(id: String): Episode {
            val gate = gates.getValue(id)
            if (!uncancellable) return gate.await()
            val episode = withContext(NonCancellable) { gate.await() }
            currentCoroutineContext().ensureActive()
            return episode
        }
    }

    private fun gatedApi(gates: FetchGates): FakePodcastApiClient = FakePodcastApiClient(
        onFetchPodcast = { id -> gates.await(id) },
        onDownloadAudio = { byteArrayOf(1, 2, 3) },
    )

    // --- 移したテスト（もとは PodcastViewModelTest） ---

    @Test
    fun downloadは開始時にdownloadingIdsへ追加し完了後に除去する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { delay(1_000); p1 },
            onDownloadAudio = { byteArrayOf(1, 2, 3) },
        )
        val library = newLibrary(apiClient)

        launch { library.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), library.downloadingIds.value)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(emptySet<String>(), library.downloadingIds.value)
    }

    @Test
    fun download成功でキャッシュに保存されdownloadedIdsに追加される() = runTest {
        val p1 = podcast(id = "p1")
        val fresh = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { fresh },
            onDownloadAudio = { byteArrayOf(9, 9) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download(p1)

        assertEquals(listOf("p1"), apiClient.fetchPodcastCalls)
        assertEquals(listOf("https://example.com/p1.mp3"), apiClient.downloadAudioCalls)
        assertTrue(cacheManager.isCached("u1", "p1"))
        assertEquals(setOf("p1"), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertNull(library.lastError.value)
    }

    @Test
    fun download失敗でlastErrorに反映されキャッシュされずdownloadingIdsから除去される() = runTest {
        val exception = ApiException.NetworkError(RuntimeException("offline"))
        val apiClient = FakePodcastApiClient(onFetchPodcast = { throw exception })
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download(podcast(id = "p1"))

        assertEquals(exception.message, library.lastError.value)
        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    @Test
    fun downloadは既にdownloadingまたはdownloaded中なら何もしない() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val library = newLibrary(apiClient)

        library.download(p1)
        assertEquals(1, apiClient.fetchPodcastCalls.size)

        // 既に downloadedIds に含まれるため、再度呼んでも fetchPodcast は増えない（二重起動防止）。
        library.download(p1)

        assertEquals(1, apiClient.fetchPodcastCalls.size)
    }

    @Test
    fun removeDownloadはキャッシュを削除しdownloadedIdsから除去する() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)
        library.download(p1)

        library.removeDownload("p1")

        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    @Test
    fun removeDownloadは不正なidの場合lastErrorに反映する() = runTest {
        val library = newLibrary(FakePodcastApiClient())

        library.removeDownload("../evil")

        assertNotNull(library.lastError.value)
    }

    @Test
    fun downloadはキャッシュ書き込みのIOExceptionでもクラッシュせずdownloadingIdsから除去される() = runTest {
        // AudioCacheManager.cache() が投げる IOException をラップせず素通しにすると、downloadingIds は
        // finally で除去されるものの、例外がコルーチンを未捕捉のまま抜けクラッシュしうる。
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val fileSystem = FakeFileSystem().apply { writeException = IOException("disk full") }
        val cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download(p1)

        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertNotNull(library.lastError.value)
    }

    @Test
    fun `android-dec-58 保存の失敗はlastErrorに固定の文言を出しdownloadedIdsへ入れない`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val fileSystem = FakeFileSystem().apply { writeException = IOException("disk full") }
        val cacheManager = AudioCacheManager(fileSystem, baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download(p1)

        // 旧は保存庫の英語の技術文言（"Failed to write cache for id=…"）がそのまま出ていた。
        assertEquals("音声を保存できませんでした", library.lastError.value)
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertFalse(cacheManager.isCached("u1", "p1"))
    }

    @Test
    fun `android-dec-58 保存の成功はlastErrorをnullのままdownloadedIdsへ入れる`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val library = newLibrary(apiClient)

        library.download(p1)

        assertNull(library.lastError.value)
        assertEquals(setOf("p1"), library.downloadedIds.value)
    }

    @Test
    fun `android-dec-58 不正なidの削除はlastErrorに固定の文言を出しdownloadedIdsと保存済みの音声を変えない`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)
        library.download(p1)

        library.removeDownload("../evil")

        assertEquals("音声を削除できませんでした", library.lastError.value)
        assertEquals(setOf("p1"), library.downloadedIds.value)
        assertTrue(cacheManager.isCached("u1", "p1"))
    }

    @Test
    fun `android-dec-58 不正な主体の削除もlastErrorに固定の文言を出す`() = runTest {
        val library = newLibrary(FakePodcastApiClient()) { "bad subject" }

        library.removeDownload("p1")

        assertEquals("音声を削除できませんでした", library.lastError.value)
    }

    @Test
    fun `android-deriv-17 downloadで取り直した値がFailedならcacheせずlastErrorを出す`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { podcast(id = "p1", status = "failed", errorMessage = "E") },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        library.download(p1)

        assertTrue(apiClient.downloadAudioCalls.isEmpty())
        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertEquals("E", library.lastError.value)
    }

    @Test
    fun `shared-leave-06 cancelDownloadsはfetchPodcast中の進行中downloadを中断しjoinせずに戻り書込を残さない`() = runTest {
        // 通信は cancel を受け付けない待機の後で ensureActive する double。join / cancelAndJoin する実装は
        // gate を完了させるまで戻らず、withTimeout で落ちる。
        val p1 = podcast(id = "p1")
        val gates = FetchGates(uncancellable = true)
        val gate = gates.arm("p1")
        val apiClient = gatedApi(gates)
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)

        launch { library.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), library.downloadingIds.value)

        withTimeout(1_000) {
            library.cancelDownloads("u1")
            library.removeSubject("u1")
        }

        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)

        // 中断された download の続きが、遅れて書込を復活させないことを確かめる。
        gate.complete(podcast(id = "p1"))
        runCurrent()
        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertTrue(apiClient.downloadAudioCalls.isEmpty())
    }

    @Test
    fun `shared-leave-06 cancelDownloadsとremoveSubjectは完了済みのダウンロードキャッシュも削除しdownloadedIdsを空にする`() = runTest {
        val p1 = podcast(id = "p1")
        val apiClient = FakePodcastApiClient(
            onFetchPodcast = { p1 },
            onDownloadAudio = { byteArrayOf(1) },
        )
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(apiClient, cacheManager)
        library.download(p1)
        assertTrue(cacheManager.isCached("u1", "p1"))

        library.cancelDownloads("u1")
        library.removeSubject("u1")

        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    // --- 離脱後に完了した書込（shared-leave-06・決定 9） ---

    @Test
    fun `shared-leave-06 cancelが間に合わず書込が完了しても書込は離脱主体のディレクトリに入り現在の主体のdownloadedIdsを書き換えない`() = runTest {
        val p1 = podcast(id = "p1")
        var subject: String? = "A"
        val gates = FetchGates()
        val gate = gates.arm("p1")
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(gatedApi(gates), cacheManager) { subject }

        launch { library.download(p1) }
        runCurrent()
        subject = "B"
        val downloadingBefore = library.downloadingIds.value

        gate.complete(p1)
        runCurrent()

        assertTrue(cacheManager.isCached("A", "p1"))
        assertFalse(cacheManager.isCached("B", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
        assertEquals(downloadingBefore, library.downloadingIds.value)

        library.reclaim("B")

        assertFalse(cacheManager.isCached("A", "p1"))
    }

    @Test
    fun `shared-leave-06 離脱中に成功した書込は同じ主体の同期でdownloadedIdsに入る`() = runTest {
        val x = podcast(id = "X")
        var subject: String? = "A"
        val gates = FetchGates()
        val gate = gates.arm("X")
        val library = newLibrary(gatedApi(gates)) { subject }

        launch { library.download(x) }
        runCurrent()
        subject = null
        gate.complete(x)
        runCurrent()

        subject = "A"
        library.syncDownloadedState(listOf(x))

        assertEquals(setOf("X"), library.downloadedIds.value)
        assertEquals(emptySet<String>(), library.downloadingIds.value)
    }

    @Test
    fun `shared-leave-06 syncDownloadedStateは前の主体の進行中idをdownloadingIdsから除き現在の主体の進行中idを残す`() = runTest {
        val x = podcast(id = "X")
        val y = podcast(id = "Y")

        // 離脱中に失敗で完了した X は、離脱主体の画面には出さない更新規則のため downloadingIds に残る。
        run {
            var subject: String? = "A"
            val gates = FetchGates()
            val gateFirst = gates.arm("X")
            val apiClient = gatedApi(gates)
            val library = newLibrary(apiClient) { subject }

            launch { library.download(x) }
            runCurrent()
            subject = null
            gateFirst.completeExceptionally(ApiException.NetworkError(RuntimeException("offline")))
            runCurrent()
            assertEquals(setOf("X"), library.downloadingIds.value)

            subject = "A"
            library.syncDownloadedState(listOf(x))

            assertEquals(emptySet<String>(), library.downloadingIds.value)
            assertEquals(emptySet<String>(), library.downloadedIds.value)

            // 同じ id の download が再び取得を呼べる（塞がれていない）。
            val gateSecond = gates.arm("X")
            launch { library.download(x) }
            runCurrent()
            assertEquals(2, apiClient.fetchPodcastCalls.size)
            assertEquals(setOf("X"), library.downloadingIds.value)
            gateSecond.complete(x)
            runCurrent()
        }

        // A の X が取得待ちのまま現在が B に替わり、B の Y を開始する。
        run {
            var subject: String? = "A"
            val gates = FetchGates()
            val gateX = gates.arm("X")
            val library = newLibrary(gatedApi(gates)) { subject }

            launch { library.download(x) }
            runCurrent()
            subject = "B"
            val gateY = gates.arm("Y")
            launch { library.download(y) }
            runCurrent()

            library.syncDownloadedState(listOf(x, y))

            assertEquals(setOf("Y"), library.downloadingIds.value)
            gateX.complete(x)
            gateY.complete(y)
            runCurrent()
        }
    }

    @Test
    fun `shared-leave-06 主体の捕捉の後に離脱したdownloadは現在の主体のdownloadingIdsへ出さない`() = runTest {
        val p1 = podcast(id = "p1")
        val gates = FetchGates()
        val gate = gates.arm("p1")
        var subject: String? = "A"
        // 最初の読み（捕捉）だけ A を返し、以後は離脱後の null を返す。
        val library = newLibrary(gatedApi(gates)) { subject.also { subject = null } }

        launch { library.download(p1) }
        runCurrent()
        assertEquals(emptySet<String>(), library.downloadingIds.value)

        gate.complete(p1)
        runCurrent()
        assertEquals(emptySet<String>(), library.downloadingIds.value)
    }

    @Test
    fun `shared-leave-06 cancelDownloadsは指定主体のJobだけを中断し他の主体のJobは完了する`() = runTest {
        val x = podcast(id = "X")
        var subject: String? = "A"
        val gates = FetchGates()
        val gateA = gates.arm("X")
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(gatedApi(gates), cacheManager) { subject }

        launch { library.download(x) }
        runCurrent()
        // 現在を B にして同期する。ここで A の X による阻害が解け、B が X を開始できる。
        subject = "B"
        library.syncDownloadedState(listOf(x))
        val gateB = gates.arm("X")
        launch { library.download(x) }
        runCurrent()

        library.cancelDownloads("A")
        library.cancelDownloads("A")
        gateA.complete(x)
        gateB.complete(x)
        runCurrent()

        assertTrue(cacheManager.isCached("B", "X"))
        assertFalse(cacheManager.isCached("A", "X"))
        assertTrue("X" in library.downloadedIds.value)
    }

    @Test
    fun `shared-leave-06 cancelDownloadsの後はcancelされたJobの終了前に同期してもdownloadingIdsへ戻らない`() = runTest {
        val p1 = podcast(id = "p1")
        val gates = FetchGates(uncancellable = true)
        val gate = gates.arm("p1")
        val library = newLibrary(gatedApi(gates))

        launch { library.download(p1) }
        runCurrent()

        withTimeout(1_000) { library.cancelDownloads("u1") }
        // cancel された Job はまだ終了していない（gate 待ち）。この間の同期が進行中扱いに戻してはならない。
        library.syncDownloadedState(listOf(p1))

        assertEquals(emptySet<String>(), library.downloadingIds.value)
        gate.complete(p1)
        runCurrent()
        assertEquals(emptySet<String>(), library.downloadingIds.value)
    }

    @Test
    fun `shared-leave-06 cancelの後に同じ主体で開始したJobは前のJobの終了で追跡から消えない`() = runTest {
        val p1 = podcast(id = "p1")
        val gates = FetchGates(uncancellable = true)
        val gateOld = gates.arm("p1")
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(gatedApi(gates), cacheManager)

        launch { library.download(p1) }
        runCurrent()
        withTimeout(1_000) { library.cancelDownloads("u1") }

        val gateNew = gates.arm("p1")
        launch { library.download(p1) }
        runCurrent()
        assertEquals(setOf("p1"), library.downloadingIds.value)

        // 前の Job が終わっても、新しい Job の追跡と進行中の表示は残る。
        gateOld.complete(p1)
        runCurrent()
        assertEquals(setOf("p1"), library.downloadingIds.value)

        // 追跡が残っているので、続けて cancel すると新しい Job も止まり、保存されない。
        withTimeout(1_000) { library.cancelDownloads("u1") }
        gateNew.complete(p1)
        runCurrent()
        assertFalse(cacheManager.isCached("u1", "p1"))
        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    @Test
    fun `shared-leave-06 cancelDownloadsはlastErrorをnullに戻す`() = runTest {
        val library = newLibrary(FakePodcastApiClient())
        library.removeDownload("../evil")
        assertNotNull(library.lastError.value)

        library.cancelDownloads("u1")

        assertNull(library.lastError.value)
    }

    // --- 主体 null（user_id の欠落・形式不正。決定 16） ---

    @Test
    fun `shared-leave-10 決定16 主体nullではdownloadは何もせずdownloadingIdsに入らない`() = runTest {
        val apiClient = FakePodcastApiClient()
        val library = newLibrary(apiClient) { null }

        library.download(podcast(id = "p1"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    @Test
    fun `shared-leave-10 決定16 主体nullではisCachedがfalseでcachedFileUriがnullでinvalidateは何もしない`() = runTest {
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1))
        val library = newLibrary(FakePodcastApiClient(), cacheManager)

        assertFalse(library.isCached(null, "p1"))
        assertNull(library.cachedFileUri(null, "p1"))
        library.invalidate(null, "p1")

        assertTrue(cacheManager.isCached("u1", "p1"))
        // 正方向の対照: 主体があれば同じ id を見つけられ、invalidate で消える。
        assertTrue(library.isCached("u1", "p1"))
        assertEquals("file:///cache/audio/u1/p1.mp3", library.cachedFileUri("u1", "p1"))
        library.invalidate("u1", "p1")
        assertFalse(cacheManager.isCached("u1", "p1"))
    }

    @Test
    fun `shared-leave-10 決定16 主体nullではsyncDownloadedStateがdownloadedIdsを空にする`() = runTest {
        val p1 = podcast(id = "p1")
        var subject: String? = "u1"
        val apiClient = FakePodcastApiClient(onFetchPodcast = { p1 }, onDownloadAudio = { byteArrayOf(1) })
        val library = newLibrary(apiClient) { subject }
        library.download(p1)
        assertEquals(setOf("p1"), library.downloadedIds.value)

        subject = null
        library.syncDownloadedState(listOf(p1))

        assertEquals(emptySet<String>(), library.downloadedIds.value)
    }

    @Test
    fun `shared-leave-10 決定16 主体nullではsyncDownloadedStateがdownloadingIdsも空にする`() = runTest {
        val x = podcast(id = "X")
        var subject: String? = "A"
        val gates = FetchGates()
        val gateX = gates.arm("X")
        val library = newLibrary(gatedApi(gates)) { subject }
        launch { library.download(x) }
        runCurrent()
        assertEquals(setOf("X"), library.downloadingIds.value)

        subject = null
        library.syncDownloadedState(listOf(x))

        assertEquals(emptySet<String>(), library.downloadingIds.value)
        assertEquals(emptySet<String>(), library.downloadedIds.value)

        // 正方向の対照: 主体が A に戻れば、同じ追跡中の X が downloadingIds に出る。
        subject = "A"
        library.syncDownloadedState(listOf(x))
        assertEquals(setOf("X"), library.downloadingIds.value)
        gateX.complete(x)
        runCurrent()
    }

    @Test
    fun `shared-leave-10 決定16 主体nullではremoveDownloadは何もしない`() = runTest {
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("A", "p1", byteArrayOf(1))
        val library = newLibrary(FakePodcastApiClient(), cacheManager) { null }

        library.removeDownload("p1")

        assertTrue(cacheManager.isCached("A", "p1"))
    }

    @Test
    fun downloadは引数がPlayableでなければ何もしない() = runTest {
        val apiClient = FakePodcastApiClient()
        val library = newLibrary(apiClient)

        library.download(podcast(id = "g1", status = "processing"))

        assertTrue(apiClient.fetchPodcastCalls.isEmpty())
        assertEquals(emptySet<String>(), library.downloadingIds.value)
    }

    @Test
    fun downloadとremoveDownloadは冒頭でlastErrorをnullに戻す() = runTest {
        var subject: String? = "u1"
        val library = newLibrary(FakePodcastApiClient()) { subject }
        library.removeDownload("../evil")
        assertNotNull(library.lastError.value)
        subject = null

        library.download(podcast(id = "p1"))
        assertNull(library.lastError.value)

        subject = "u1"
        library.removeDownload("../evil")
        assertNotNull(library.lastError.value)
        subject = null

        library.removeDownload("p1")
        assertNull(library.lastError.value)
    }

    // --- 主体の置き場・同期・公開面 ---

    @Test
    fun invalidateはAudioCacheExceptionを握る() = runTest {
        val library = newLibrary(FakePodcastApiClient())

        library.invalidate("u1", "a.b")
    }

    @Test
    fun `android-dec-58 invalidateは主体か_idが不正なら黙って何もせず保存済みの音声に触れない`() = runTest {
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("u1", "p1", byteArrayOf(1))
        val library = newLibrary(FakePodcastApiClient(), cacheManager)

        library.invalidate("u1", "a.b")
        library.invalidate("bad subject", "p1")
        library.invalidate("u1", "")

        assertTrue(cacheManager.isCached("u1", "p1"))
        // 正の対照: 正しい主体と id なら無効化される。
        library.invalidate("u1", "p1")
        assertFalse(cacheManager.isCached("u1", "p1"))
    }

    @Test
    fun syncDownloadedStateは現在の主体のキャッシュだけをdownloadedIdsにする() = runTest {
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        cacheManager.cache("A", "pA", byteArrayOf(1))
        cacheManager.cache("B", "pB", byteArrayOf(1))
        var subject: String? = "A"
        val library = newLibrary(FakePodcastApiClient(), cacheManager) { subject }
        val all = listOf(podcast(id = "pA"), podcast(id = "pB"))

        library.syncDownloadedState(all)
        assertEquals(setOf("pA"), library.downloadedIds.value)

        subject = "B"
        library.syncDownloadedState(all)
        assertEquals(setOf("pB"), library.downloadedIds.value)
    }

    @Test
    fun `android-check-06 downloadingIdsとdownloadedIdsが返したSetを書き換えても次の読みと保存は変わらない`() = runTest {
        val p1 = podcast(id = "p1")
        val p2 = podcast(id = "p2")
        val gates = FetchGates()
        val cacheManager = AudioCacheManager(FakeFileSystem(), baseDir = "/cache")
        val library = newLibrary(gatedApi(gates), cacheManager)
        gates.arm("p2").complete(p2)
        library.download(p2) // 完了した download（downloadedIds が非空）
        val gate1 = gates.arm("p1")
        launch { library.download(p1) } // 取得待ちの download（downloadingIds が非空）
        runCurrent()
        assertEquals(setOf("p1"), library.downloadingIds.value)
        assertEquals(setOf("p2"), library.downloadedIds.value)

        attemptWrite(library.downloadingIds.value)
        attemptWrite(library.downloadedIds.value)

        assertEquals(setOf("p1"), library.downloadingIds.value)
        assertEquals(setOf("p2"), library.downloadedIds.value)
        assertTrue(cacheManager.isCached("u1", "p2"))
        assertFalse(cacheManager.isCached("u1", "x"))
        gate1.complete(p1)
        runCurrent()
    }

    /** 書き換えを試みる。読み取り専用なら [UnsupportedOperationException] で弾かれるので握って次へ進む。 */
    private fun attemptWrite(set: Set<String>) {
        @Suppress("UNCHECKED_CAST")
        val mutable = set as MutableSet<String>
        try { mutable.add("x") } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
        try { mutable.clear() } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
    }
}

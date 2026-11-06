package com.rioikeda.newslisten.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [AudioCacheManager] の挙動検証。実ディスクに触れず [FakeFileSystem] を使う。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/AudioCacheManager.swift のミラー
 * （iOS は cacheSize() がスタブ 0 実装だが、Android は [FileSystem.listFiles] を使い実計算する）。
 */
class AudioCacheManagerTest {
    private val fileSystem = FakeFileSystem()
    private val manager = AudioCacheManager(fileSystem, baseDir = "/cache")

    // --- validateId（path traversal 防御。AudioCacheManager.swift:95-100 / ADR-027:23 相当） ---

    @Test
    fun validateIdは英数字ハイフンアンダースコアのみのidをtrueとする() {
        assertTrue(manager.validateId("abc123_-XYZ"))
    }

    @Test
    fun validateIdは空文字をfalseとする() {
        assertFalse(manager.validateId(""))
    }

    @Test
    fun validateIdはpath_traversal文字列をfalseとする() {
        assertFalse(manager.validateId("../../etc/passwd"))
    }

    @Test
    fun validateIdはスラッシュを含むidをfalseとする() {
        assertFalse(manager.validateId("a/b"))
    }

    @Test
    fun validateIdはドットを含むidをfalseとする() {
        assertFalse(manager.validateId("a.b"))
    }

    // --- cache: 不正idはAudioCacheException.InvalidIdをthrowする ---

    @Test
    fun cacheは不正なidに対してInvalidIdをthrowしファイルを書き込まない() {
        val thrown = assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.cache("u1", "../evil", byteArrayOf(1))
        }

        assertEquals("../evil", thrown.id)
        // 新しい listFiles は直下の通常ファイルだけなので、traversal 先は exists で確かめる。
        assertFalse(fileSystem.exists("/cache/audio/u1/../evil.mp3"))
        assertTrue(fileSystem.ensureDirectoryCalls.isEmpty())
    }

    @Test
    fun removeは不正なidに対してInvalidIdをthrowする() {
        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.remove("u1", "bad id")
        }
    }

    @Test
    fun cacheとremoveとinvalidateとremoveSubjectは不正なsubjectに対してInvalidIdをthrowしファイルに触れない() {
        manager.cache("u1", "p1", byteArrayOf(1))

        listOf("", "../u1", "a b", "u1/..", "/u1").forEach { bad ->
            val ops: List<() -> Unit> = listOf(
                { manager.cache(bad, "p1", byteArrayOf(2)) },
                { manager.remove(bad, "p1") },
                { manager.invalidate(bad, "p1") },
                { manager.removeSubject(bad) },
            )
            ops.forEach { op ->
                val thrown = assertThrows(AudioCacheException.InvalidId::class.java) { op() }
                assertEquals(bad, thrown.id)
            }
        }

        assertTrue(manager.isCached("u1", "p1"))
        assertEquals(1L, manager.cacheSize("u1"))
        assertEquals(1, fileSystem.ensureDirectoryCalls.size)
    }

    // --- cache: ファイル書き込みのIOExceptionはAudioCacheException.WriteFailedにラップする ---

    @Test
    fun cacheはIOException発生時にWriteFailedへラップしてthrowしファイルを残さない() {
        fileSystem.writeException = IOException("disk full")

        val thrown = assertThrows(AudioCacheException.WriteFailed::class.java) {
            manager.cache("u1", "p1", byteArrayOf(1))
        }

        assertEquals("p1", thrown.id)
        assertFalse(manager.isCached("u1", "p1"))
    }

    // --- cache → isCached → cachedFileUri → remove のラウンドトリップ ---

    @Test
    fun cacheしたidはisCachedがtrueになりcachedFileUriが取得できる() {
        assertFalse(manager.isCached("u1", "p1"))
        assertNull(manager.cachedFileUri("u1", "p1"))

        manager.cache("u1", "p1", byteArrayOf(1, 2, 3))

        assertTrue(manager.isCached("u1", "p1"))
        assertEquals("file:///cache/audio/u1/p1.mp3", manager.cachedFileUri("u1", "p1"))
    }

    @Test
    fun isCachedは別の主体のキャッシュを見ない() {
        manager.cache("u1", "p1", byteArrayOf(1))

        assertFalse(manager.isCached("u2", "p1"))
        assertNull(manager.cachedFileUri("u2", "p1"))
        // 検証せず投げない（android-dec-47(a)）
        assertFalse(manager.isCached("bad subject", "a.b"))
        assertNull(manager.cachedFileUri("bad subject", "a.b"))
    }

    @Test
    fun removeするとisCachedがfalseに戻りcachedFileUriがnullになる() {
        manager.cache("u1", "p1", byteArrayOf(1))

        manager.remove("u1", "p1")

        assertFalse(manager.isCached("u1", "p1"))
        assertNull(manager.cachedFileUri("u1", "p1"))
    }

    @Test
    fun removeは存在しないidに対して例外を投げない冪等操作() {
        manager.remove("u1", "never-cached")
    }

    // --- invalidate: remove と同じ削除。再生失敗によるキャッシュの無効化を表す ---

    @Test
    fun invalidateするとisCachedがfalseになりcachedFileUriがnullになる() {
        // verifies: android-contract-11
        manager.cache("u1", "p1", byteArrayOf(1))

        manager.invalidate("u1", "p1")

        assertFalse(manager.isCached("u1", "p1"))
        assertNull(manager.cachedFileUri("u1", "p1"))
    }

    @Test
    fun invalidateはほかのidのキャッシュを残す() {
        // verifies: android-contract-11
        manager.cache("u1", "p1", byteArrayOf(1))
        manager.cache("u1", "p2", byteArrayOf(2))

        manager.invalidate("u1", "p1")

        assertFalse(manager.isCached("u1", "p1"))
        assertTrue(manager.isCached("u1", "p2"))
    }

    @Test
    fun invalidateは不正なidに対してInvalidIdをthrowする() {
        // verifies: android-contract-11
        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.invalidate("u1", "bad id")
        }
    }

    @Test
    fun invalidateは不正なidに対してInvalidIdをthrowしファイルに触れない() {
        // verifies: android-contract-11
        // "/p1" は File(dir, "/p1.mp3") で p1 と同じパスに解決されるため、
        // 検証より先に削除する実装は p1 のキャッシュを消してこのテストで落ちる。
        manager.cache("u1", "p1", byteArrayOf(1))

        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.invalidate("u1", "/p1")
        }

        assertTrue(manager.isCached("u1", "p1"))
    }

    @Test
    fun invalidateは存在しないidや2回目の呼出に対して例外を投げない冪等操作() {
        // verifies: android-contract-11
        manager.invalidate("u1", "never-cached")
        manager.invalidate("u1", "never-cached")
    }

    // --- removeSubject ---

    @Test
    fun removeSubjectは指定主体のキャッシュだけを削除し他の主体を残す() {
        manager.cache("u1", "p1", byteArrayOf(1))
        manager.cache("u1", "p2", byteArrayOf(2, 2))
        manager.cache("u2", "p1", byteArrayOf(3))

        manager.removeSubject("u1")
        manager.removeSubject("u1")
        manager.removeSubject("never-cached-subject")

        assertFalse(manager.isCached("u1", "p1"))
        assertFalse(manager.isCached("u1", "p2"))
        assertTrue(manager.isCached("u2", "p1"))
    }

    // --- reclaim（起動時回収。決定 7・8・16） ---

    @Test
    fun `shared-leave-06 決定8 reclaimは現在の主体以外のディレクトリを削除する`() {
        manager.cache("u1", "p1", byteArrayOf(1))
        manager.cache("u2", "p1", byteArrayOf(2))
        manager.cache("u3", "p9", byteArrayOf(3))

        manager.reclaim("u2")
        val afterFirst = fileSystem.listFiles("/cache/audio/u2") + fileSystem.listDirectories("/cache/audio")
        manager.reclaim("u2")

        assertFalse(manager.isCached("u1", "p1"))
        assertTrue(manager.isCached("u2", "p1"))
        assertFalse(manager.isCached("u3", "p9"))
        assertEquals(afterFirst, fileSystem.listFiles("/cache/audio/u2") + fileSystem.listDirectories("/cache/audio"))
    }

    @Test
    fun `shared-leave-06 決定7 reclaimは平置きの旧キャッシュを全削除する`() {
        fileSystem.write("/cache/audio/old1.mp3", byteArrayOf(1))
        fileSystem.write("/cache/audio/old2.mp3", byteArrayOf(2))
        manager.cache("u1", "p1", byteArrayOf(3))

        manager.reclaim("u1")

        assertFalse(fileSystem.exists("/cache/audio/old1.mp3"))
        assertFalse(fileSystem.exists("/cache/audio/old2.mp3"))
        assertTrue(manager.isCached("u1", "p1"))
    }

    @Test
    fun `shared-leave-10 決定16 reclaimのnullまたは形式不正は全ディレクトリを削除する`() {
        listOf<String?>(null, "", "a b", "../u1", "u1/x").forEach { current ->
            val fs = FakeFileSystem()
            val m = AudioCacheManager(fs, baseDir = "/cache")
            m.cache("u1", "p1", byteArrayOf(1))
            m.cache("u2", "p1", byteArrayOf(1))
            fs.write("/cache/audio/old.mp3", byteArrayOf(9))

            m.reclaim(current)

            assertEquals("current=$current", emptyList<String>(), fs.listDirectories("/cache/audio"))
            assertEquals("current=$current", emptyList<String>(), fs.listFiles("/cache/audio"))
        }
    }

    // --- cacheSize: 実計算（Android の計画決定。iOS はスタブ0） ---

    @Test
    fun cacheSizeはキャッシュ済み全ファイルサイズの合計を返す() {
        assertEquals(0L, manager.cacheSize("u1"))

        manager.cache("u1", "p1", byteArrayOf(1, 2, 3))
        manager.cache("u1", "p2", byteArrayOf(1, 2))
        manager.cache("u2", "p1", byteArrayOf(1, 2, 3, 4))

        assertEquals(5L, manager.cacheSize("u1"))
    }

    @Test
    fun cacheSizeはremove後に減算される() {
        manager.cache("u1", "p1", byteArrayOf(1, 2, 3))
        manager.cache("u1", "p2", byteArrayOf(1, 2))

        manager.remove("u1", "p1")

        assertEquals(2L, manager.cacheSize("u1"))
    }
}

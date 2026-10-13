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
        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.cache("../evil", byteArrayOf(1))
        }
        assertTrue(fileSystem.listFiles("/cache/audio").isEmpty())
    }

    @Test
    fun removeは不正なidに対してInvalidIdをthrowする() {
        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.remove("bad id")
        }
    }

    // --- cache: ファイル書き込みのIOExceptionはAudioCacheException.WriteFailedにラップする ---

    @Test
    fun cacheはIOException発生時にWriteFailedへラップしてthrowしファイルを残さない() {
        fileSystem.writeException = IOException("disk full")

        val thrown = assertThrows(AudioCacheException.WriteFailed::class.java) {
            manager.cache("p1", byteArrayOf(1))
        }

        assertEquals("p1", thrown.id)
        assertFalse(manager.isCached("p1"))
    }

    // --- cache → isCached → cachedFileUri → remove のラウンドトリップ ---

    @Test
    fun cacheしたidはisCachedがtrueになりcachedFileUriが取得できる() {
        assertFalse(manager.isCached("p1"))
        assertNull(manager.cachedFileUri("p1"))

        manager.cache("p1", byteArrayOf(1, 2, 3))

        assertTrue(manager.isCached("p1"))
        assertEquals("file:///cache/audio/p1.mp3", manager.cachedFileUri("p1"))
    }

    @Test
    fun removeするとisCachedがfalseに戻りcachedFileUriがnullになる() {
        manager.cache("p1", byteArrayOf(1))

        manager.remove("p1")

        assertFalse(manager.isCached("p1"))
        assertNull(manager.cachedFileUri("p1"))
    }

    @Test
    fun removeは存在しないidに対して例外を投げない冪等操作() {
        manager.remove("never-cached")
    }

    // --- invalidate: remove と同じ削除。再生失敗によるキャッシュの無効化を表す ---

    @Test
    fun invalidateするとisCachedがfalseになりcachedFileUriがnullになる() {
        // verifies: android-contract-11
        manager.cache("p1", byteArrayOf(1))

        manager.invalidate("p1")

        assertFalse(manager.isCached("p1"))
        assertNull(manager.cachedFileUri("p1"))
    }

    @Test
    fun invalidateはほかのidのキャッシュを残す() {
        // verifies: android-contract-11
        manager.cache("p1", byteArrayOf(1))
        manager.cache("p2", byteArrayOf(2))

        manager.invalidate("p1")

        assertFalse(manager.isCached("p1"))
        assertTrue(manager.isCached("p2"))
    }

    @Test
    fun invalidateは不正なidに対してInvalidIdをthrowする() {
        // verifies: android-contract-11
        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.invalidate("bad id")
        }
    }

    @Test
    fun invalidateは不正なidに対してInvalidIdをthrowしファイルに触れない() {
        // verifies: android-contract-11
        // "/p1" は File(cacheDir, "/p1.mp3") で p1 と同じパスに解決されるため、
        // 検証より先に削除する実装は p1 のキャッシュを消してこのテストで落ちる。
        manager.cache("p1", byteArrayOf(1))

        assertThrows(AudioCacheException.InvalidId::class.java) {
            manager.invalidate("/p1")
        }

        assertTrue(manager.isCached("p1"))
    }

    @Test
    fun invalidateは存在しないidや2回目の呼出に対して例外を投げない冪等操作() {
        // verifies: android-contract-11
        manager.invalidate("never-cached")
        manager.invalidate("never-cached")
    }

    // --- removeAll ---

    @Test
    fun removeAllはキャッシュした全ファイルを削除する() {
        manager.cache("p1", byteArrayOf(1))
        manager.cache("p2", byteArrayOf(2, 2))

        manager.removeAll()

        assertFalse(manager.isCached("p1"))
        assertFalse(manager.isCached("p2"))
    }

    // --- cacheSize: 実計算（Android の計画決定。iOS はスタブ0） ---

    @Test
    fun cacheSizeはキャッシュ済み全ファイルサイズの合計を返す() {
        assertEquals(0L, manager.cacheSize())

        manager.cache("p1", byteArrayOf(1, 2, 3))
        manager.cache("p2", byteArrayOf(1, 2))

        assertEquals(5L, manager.cacheSize())
    }

    @Test
    fun cacheSizeはremove後に減算される() {
        manager.cache("p1", byteArrayOf(1, 2, 3))
        manager.cache("p2", byteArrayOf(1, 2))

        manager.remove("p1")

        assertEquals(2L, manager.cacheSize())
    }
}

package com.rioikeda.newslisten.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [JavaFileSystem]（[FileSystem] の実 I/O 実装）の挙動検証。
 *
 * 実ディスク（一時ディレクトリ）に対して検証する。[AudioCacheManager] のテストは
 * [FakeFileSystem] を使うため、ここでは java.io.File への委譲が正しいことのみ確認する。
 */
class JavaFileSystemTest {
    private lateinit var tempDir: File
    private val fileSystem = JavaFileSystem()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("audio-cache-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun 存在しないパスはexistsがfalseを返す() {
        val path = File(tempDir, "missing.mp3").path
        assertFalse(fileSystem.exists(path))
    }

    @Test
    fun writeしたファイルはexistsがtrueになりバイト列を読み戻せる() {
        val path = File(tempDir, "a.mp3").path
        fileSystem.write(path, byteArrayOf(1, 2, 3))

        assertTrue(fileSystem.exists(path))
        assertEquals(listOf<Byte>(1, 2, 3), File(path).readBytes().toList())
    }

    @Test
    fun deleteは存在するファイルを消してtrueを返す() {
        val path = File(tempDir, "a.mp3").path
        fileSystem.write(path, byteArrayOf(1))

        val result = fileSystem.delete(path)

        assertTrue(result)
        assertFalse(fileSystem.exists(path))
    }

    @Test
    fun deleteは存在しないファイルに対してfalseを返す冪等操作() {
        val path = File(tempDir, "missing.mp3").path
        assertFalse(fileSystem.delete(path))
    }

    @Test
    fun listFilesはディレクトリ内の全ファイルの絶対パスを返す() {
        val dir = File(tempDir, "audio")
        fileSystem.ensureDirectory(dir.path)
        fileSystem.write(File(dir, "a.mp3").path, byteArrayOf(1))
        fileSystem.write(File(dir, "b.mp3").path, byteArrayOf(1, 2))

        val result = fileSystem.listFiles(dir.path)

        assertEquals(setOf(File(dir, "a.mp3").path, File(dir, "b.mp3").path), result.toSet())
    }

    @Test
    fun listFilesは存在しないディレクトリに対して空リストを返す() {
        val dir = File(tempDir, "missing-dir")
        assertEquals(emptyList<String>(), fileSystem.listFiles(dir.path))
    }

    @Test
    fun fileSizeは書き込んだバイト数を返す() {
        val path = File(tempDir, "a.mp3").path
        fileSystem.write(path, byteArrayOf(1, 2, 3, 4))

        assertEquals(4L, fileSystem.fileSize(path))
    }

    @Test
    fun fileSizeは存在しないファイルに対してnullを返す() {
        val path = File(tempDir, "missing.mp3").path
        assertNull(fileSystem.fileSize(path))
    }

    @Test
    fun ensureDirectoryは中間ディレクトリも含めて作成する() {
        val dir = File(tempDir, "nested/audio")

        fileSystem.ensureDirectory(dir.path)

        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    @Test
    fun ensureDirectoryは既存ディレクトリに対してno_opで例外を投げない() {
        val dir = File(tempDir, "audio")
        fileSystem.ensureDirectory(dir.path)

        fileSystem.ensureDirectory(dir.path)

        assertTrue(dir.exists())
    }
}

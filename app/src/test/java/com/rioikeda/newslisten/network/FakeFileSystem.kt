package com.rioikeda.newslisten.network

import java.io.IOException

/**
 * [AudioCacheManager] のテスト専用フェイク [FileSystem]。実ディスクに触れず、
 * インメモリの `Map<String, ByteArray>` で状態を管理する。
 */
class FakeFileSystem : FileSystem {
    private val files: MutableMap<String, ByteArray> = mutableMapOf()

    /** [ensureDirectory] に渡されたパスの呼び出し履歴。 */
    val ensureDirectoryCalls: MutableList<String> = mutableListOf()

    /** 設定すると次回以降の [write] 呼び出しでこの例外を投げる（ディスク書き込み失敗の再現用）。 */
    var writeException: IOException? = null

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun write(path: String, bytes: ByteArray) {
        writeException?.let { throw it }
        files[path] = bytes
    }

    override fun delete(path: String): Boolean = files.remove(path) != null

    override fun listFiles(dirPath: String): List<String> =
        files.keys.filter { it.startsWith("$dirPath/") }

    override fun fileSize(path: String): Long? = files[path]?.size?.toLong()

    override fun ensureDirectory(dirPath: String) {
        ensureDirectoryCalls.add(dirPath)
    }
}

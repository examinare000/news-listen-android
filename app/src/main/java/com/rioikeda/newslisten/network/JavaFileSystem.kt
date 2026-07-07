package com.rioikeda.newslisten.network

import java.io.File

/**
 * [FileSystem] の実 I/O 実装。`java.io.File` にそのまま委譲する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/FileManagerProtocol.swift の
 * `extension FileManager: FileManagerProtocol` 相当。
 */
class JavaFileSystem : FileSystem {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun write(path: String, bytes: ByteArray) {
        File(path).writeBytes(bytes)
    }

    override fun delete(path: String): Boolean = File(path).delete()

    override fun listFiles(dirPath: String): List<String> =
        File(dirPath).listFiles()?.map { it.path } ?: emptyList()

    override fun fileSize(path: String): Long? =
        File(path).takeIf { it.exists() }?.length()

    override fun ensureDirectory(dirPath: String) {
        File(dirPath).mkdirs()
    }
}

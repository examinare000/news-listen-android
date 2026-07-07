package com.rioikeda.newslisten.network

import java.io.File
import java.io.IOException

/**
 * 音声キャッシュ操作で発生し得るエラー。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/AudioCacheManager.swift の `AudioCacheError`。
 */
sealed class AudioCacheException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Podcast ID が不正形式（`[A-Za-z0-9_-]` 以外の文字を含む、または空）。 */
    class InvalidId(val id: String) : AudioCacheException("Invalid podcast ID format: $id")

    /**
     * ディスク書き込み失敗（容量不足・権限エラー等の [IOException]）。
     * 呼び出し元（[cache]）は生の [IOException] を投げず、この型にラップして
     * 呼び出し側（PodcastViewModel）が既存の catch (e: AudioCacheException) で捕捉できるようにする。
     */
    class WriteFailed(val id: String, cause: Throwable) :
        AudioCacheException("Failed to write cache for id=$id: ${cause.message}", cause)
}

/**
 * Podcast 音声のローカルキャッシュを管理する（保存専任・ダウンロードは行わない）。
 *
 * `{baseDir}/audio/{id}.mp3` にキャッシュを保存する。安全性のため、id は `[A-Za-z0-9_-]` のみ
 * 許可し path traversal を防ぐ（ADR-027:23）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/AudioCacheManager.swift のミラー。
 * iOS は `cacheSize()` がスタブ 0 実装だが、Android は [FileSystem.listFiles] を使い実計算する
 * （フェーズ8-B 計画決定）。
 *
 * @param fileSystem ファイル I/O（テスト注入可能）。
 * @param baseDir キャッシュの起点ディレクトリ（実アプリでは `Context.cacheDir` の絶対パスを渡す）。
 */
class AudioCacheManager(
    private val fileSystem: FileSystem,
    baseDir: String,
) {
    private val cacheDir: String = File(baseDir, "audio").path

    /**
     * ID が安全形式（英数字・ハイフン・アンダースコアのみ、かつ非空）かどうかを検証する。
     * 副作用のない純粋関数で、[cache]/[remove] からも呼ばれる。
     */
    fun validateId(id: String): Boolean = id.isNotEmpty() && id.all { it in VALID_ID_CHARS }

    /**
     * 音声データをキャッシュに保存する（既存ファイルは上書き）。
     * @throws AudioCacheException.InvalidId id が不正な場合。
     * @throws AudioCacheException.WriteFailed ディスク書き込みが [IOException] で失敗した場合。
     */
    fun cache(id: String, bytes: ByteArray) {
        requireValidId(id)
        fileSystem.ensureDirectory(cacheDir)
        try {
            fileSystem.write(filePath(id), bytes)
        } catch (e: IOException) {
            throw AudioCacheException.WriteFailed(id, e)
        }
    }

    /** 指定 Podcast ID のキャッシュが存在するかどうか。 */
    fun isCached(id: String): Boolean = fileSystem.exists(filePath(id))

    /** キャッシュ済みなら `file://` URI を返す。未キャッシュなら `null`。 */
    fun cachedFileUri(id: String): String? =
        if (isCached(id)) "file://${filePath(id)}" else null

    /**
     * キャッシュを削除する（存在しない場合は no-op・冪等）。
     * @throws AudioCacheException.InvalidId id が不正な場合。
     */
    fun remove(id: String) {
        requireValidId(id)
        fileSystem.delete(filePath(id))
    }

    /** キャッシュディレクトリ内の全ファイルを削除する。 */
    fun removeAll() {
        fileSystem.listFiles(cacheDir).forEach { fileSystem.delete(it) }
    }

    /** キャッシュディレクトリ内の全ファイルサイズ合計（バイト）。 */
    fun cacheSize(): Long =
        fileSystem.listFiles(cacheDir).sumOf { fileSystem.fileSize(it) ?: 0L }

    private fun requireValidId(id: String) {
        if (!validateId(id)) throw AudioCacheException.InvalidId(id)
    }

    private fun filePath(id: String): String = File(cacheDir, "$id.mp3").path

    private companion object {
        val VALID_ID_CHARS: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('_', '-')).toSet()
    }
}

package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.podcast.app.AudioStore
import java.io.File
import java.io.IOException

/**
 * 音声キャッシュ操作で発生し得るエラー。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/AudioCacheManager.swift の `AudioCacheError`。
 */
sealed class AudioCacheException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 主体または Podcast ID が不正形式（`[A-Za-z0-9_-]` 以外の文字を含む、または空）。[id] は不正だった文字列。 */
    class InvalidId(val id: String) : AudioCacheException("Invalid podcast ID format: $id")

    /**
     * ディスク書き込み失敗（容量不足・権限エラー等の [IOException]）。
     * 呼び出し元（[cache]）は生の [IOException] を投げず、この型にラップして
     * 呼び出し側が catch (e: AudioCacheException) で捕捉できるようにする。
     */
    class WriteFailed(val id: String, cause: Throwable) :
        AudioCacheException("Failed to write cache for id=$id: ${cause.message}", cause)
}

/**
 * Podcast 音声のローカルキャッシュを主体（`user_id`）ごとに管理する（保存専任・ダウンロードは行わない）。
 *
 * `{baseDir}/audio/{subject}/{id}.mp3` にキャッシュを保存する。安全性のため、subject と id は
 * `[A-Za-z0-9_-]` のみ許可し path traversal を防ぐ（ADR-027:23）。主体を持たない全削除は無い（ADR-104 決定 2）。
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
) : AudioStore {
    private val cacheDir: String = File(baseDir, "audio").path

    /**
     * ID が安全形式（英数字・ハイフン・アンダースコアのみ、かつ非空）かどうかを検証する。
     * 副作用のない純粋関数で、主体と Podcast ID の両方の検証に使う。
     */
    override fun validateId(id: String): Boolean = id.isNotEmpty() && id.all { it in VALID_ID_CHARS }

    /** [cache] の失敗（形式の不正・書込の失敗）を `false` に写す。port から見える保存の入口。 */
    override fun save(subject: String, id: String, bytes: ByteArray): Boolean =
        try {
            cache(subject, id, bytes)
            true
        } catch (_: AudioCacheException) {
            false
        }

    /**
     * 音声データを主体の置き場に保存する（既存ファイルは上書き）。
     * @throws AudioCacheException.InvalidId subject または id が不正な場合（ファイルには触れない）。
     * @throws AudioCacheException.WriteFailed ディスク書き込みが [IOException] で失敗した場合。
     */
    fun cache(subject: String, id: String, bytes: ByteArray) {
        requireValid(subject, id)
        fileSystem.ensureDirectory(subjectDir(subject))
        try {
            fileSystem.write(filePath(subject, id), bytes)
        } catch (e: IOException) {
            throw AudioCacheException.WriteFailed(id, e)
        }
    }

    /** その主体の置き場に指定 Podcast ID のキャッシュが存在するか。検証はせず、例外を投げない。 */
    override fun isCached(subject: String, id: String): Boolean = fileSystem.exists(filePath(subject, id))

    /** キャッシュ済みなら `file://` URI を返す。未キャッシュなら `null`。検証はせず、例外を投げない。 */
    override fun cachedFileUri(subject: String, id: String): String? =
        if (isCached(subject, id)) "file://${filePath(subject, id)}" else null

    /**
     * キャッシュを削除する（存在しない場合は no-op・冪等）。
     * @throws AudioCacheException.InvalidId subject または id が不正な場合。
     */
    override fun remove(subject: String, id: String) {
        requireValid(subject, id)
        fileSystem.delete(filePath(subject, id))
    }

    /**
     * 再生に失敗したキャッシュを無効化する。削除そのものは [remove] と同じで、意味だけを分ける。
     * @throws AudioCacheException.InvalidId subject または id が不正な場合（ファイルには触れない）。
     */
    override fun invalidate(subject: String, id: String) = remove(subject, id)

    /**
     * 指定主体のディレクトリを中身ごと削除する（他の主体は残す。無くても例外にしない）。
     * @throws AudioCacheException.InvalidId subject が不正な場合（ファイルには触れない）。
     */
    override fun removeSubject(subject: String) {
        requireValidId(subject)
        fileSystem.deleteRecursively(subjectDir(subject))
    }

    /**
     * 起動時回収。`audio/` 直下の通常ファイル（旧平置き）を全削除し、[currentSubject] 以外のディレクトリを削除する。
     * [currentSubject] が `null` または形式不正なら全ディレクトリを削除する（例外にしない。冪等）。
     */
    override fun reclaim(currentSubject: String?) {
        fileSystem.listFiles(cacheDir).forEach { fileSystem.delete(it) }
        val keep = currentSubject?.takeIf { validateId(it) }
        fileSystem.listDirectories(cacheDir)
            .filter { keep == null || File(it).name != keep }
            .forEach { fileSystem.deleteRecursively(it) }
    }

    /** 指定主体の置き場の全ファイルサイズ合計（バイト）。 */
    fun cacheSize(subject: String): Long =
        fileSystem.listFiles(subjectDir(subject)).sumOf { fileSystem.fileSize(it) ?: 0L }

    private fun requireValid(subject: String, id: String) {
        requireValidId(subject)
        requireValidId(id)
    }

    private fun requireValidId(id: String) {
        if (!validateId(id)) throw AudioCacheException.InvalidId(id)
    }

    private fun subjectDir(subject: String): String = File(cacheDir, subject).path

    private fun filePath(subject: String, id: String): String = File(subjectDir(subject), "$id.mp3").path

    private companion object {
        val VALID_ID_CHARS: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('_', '-')).toSet()
    }
}

package com.rioikeda.newslisten.network

/**
 * ファイル I/O の抽象。テストで実ディスクに触れず [AudioCacheManager] を検証可能にする。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/FileManagerProtocol.swift のミラー。
 * iOS 版は列挙 API を持たず `cacheSize()` がスタブ実装（常に 0）だったが、Android では
 * [listFiles] を追加し、`cacheSize(subject)` と主体のディレクトリの削除・回収を実計算にする（フェーズ8-B 計画決定）。
 */
interface FileSystem {
    /** 指定パスにファイルが存在するかどうか。 */
    fun exists(path: String): Boolean

    /** バイト列をファイルに書き込む（既存ファイルは上書き）。 */
    fun write(path: String, bytes: ByteArray)

    /** 指定パスのファイルを削除する。存在しない場合は no-op で `false` を返す（冪等）。 */
    fun delete(path: String): Boolean

    /** 指定ディレクトリ直下の**通常ファイル**の絶対パス一覧を返す（サブディレクトリは含まない）。存在しなければ空リスト。 */
    fun listFiles(dirPath: String): List<String>

    /** 指定ディレクトリ直下のディレクトリの絶対パス一覧を返す。存在しなければ空リスト。 */
    fun listDirectories(dirPath: String): List<String>

    /** 指定パスをその中身ごと削除する。存在しなくても例外にしない。消せなかった分は残し、例外にしない。 */
    fun deleteRecursively(path: String)

    /** 指定パスのファイルサイズ（バイト）。存在しない場合は `null`。 */
    fun fileSize(path: String): Long?

    /** ディレクトリが存在しなければ、中間ディレクトリも含めて作成する（既存なら no-op）。 */
    fun ensureDirectory(dirPath: String)
}

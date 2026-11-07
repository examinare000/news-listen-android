package com.rioikeda.newslisten.podcast.app

/**
 * 端末内の音声の保存庫（port）。主体（`user_id`）ごとの置き場に Podcast の音声を保存する。
 *
 * 引数と戻り値は主体と id の文字列・バイト列・真偽・URI の文字列だけで、保存の実装の例外の型を署名に出さない。
 * 保存の失敗は [save] の戻り値で表し、主体や id の形式は [validateId] で先に確かめられる。
 */
interface AudioStore {
    /** 主体と Podcast id に使える形式（英数字・ハイフン・アンダースコアのみ、非空）か。 */
    fun validateId(id: String): Boolean

    /** 音声を保存する。保存できたら `true`。主体や id が不正、または書込に失敗したら `false`。 */
    fun save(subject: String, id: String, bytes: ByteArray): Boolean

    /** その主体の置き場に保存済みか。検証はせず、例外を出さない。 */
    fun isCached(subject: String, id: String): Boolean

    /** 保存済みなら再生に渡せる URI、未保存なら `null`。 */
    fun cachedFileUri(subject: String, id: String): String?

    /** 保存済みの音声を削除する。無ければ何もしない。呼ぶ前に [validateId] で確かめる。 */
    fun remove(subject: String, id: String)

    /** 再生に失敗した保存済みの音声を無効化する。呼ぶ前に [validateId] で確かめる。 */
    fun invalidate(subject: String, id: String)

    /**
     * 指定した主体の置き場を中身ごと削除する。
     * 主体の形式が不正なら例外を投げる（呼び手の後始末が手順の失敗として数える: android-dec-58 (b)）。
     */
    fun removeSubject(subject: String)

    /** 起動時の回収。現在の主体以外の置き場を削除する。`null` なら全部。 */
    fun reclaim(currentSubject: String?)
}

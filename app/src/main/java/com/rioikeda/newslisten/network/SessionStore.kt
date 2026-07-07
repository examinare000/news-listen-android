package com.rioikeda.newslisten.network

/**
 * セッショントークンの読み書きを抽象化する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift のミラー。
 * iOS は `var token: String?` の get/set のみで表現するが、Kotlin 版は操作の意図を
 * 型シグネチャで明示するため save/load/clear の3メソッドに分解する
 * （iOS の get 相当 = [load]、`token = value` 相当 = [save]、`token = nil` 相当 = [clear]）。
 */
interface SessionStore {
    /** トークンを保存する。既存の値があれば上書きする。 */
    fun save(token: String)

    /** 保存中のトークンを返す。未保存・削除済み・復号失敗時は `null`。 */
    fun load(): String?

    /** 保存中のトークンを削除する。 */
    fun clear()
}

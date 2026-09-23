package com.rioikeda.newslisten.network

/**
 * セッショントークンの読み書きを抽象化する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift のミラー。
 * iOS は `var token: String?` の get/set のみで表現するが、Kotlin 版は操作の意図を
 * 型シグネチャで明示するため save/load/clear の3メソッドに分解する
 * （iOS の get 相当 = [load]、`token = value` 相当 = [save]、`token = nil` 相当 = [clear]）。
 *
 * android S0（CI-T14）: [save] は失敗を例外ではなく戻り値で呼出元へ返す。呼出元
 * （[com.rioikeda.newslisten.auth.AuthViewModel]）は保存失敗時に `Authenticated` へ遷移させない。
 *
 * android S0（CI-S0-11、#14）: 3 操作はスレッド安全で線形化可能であること。
 * [com.rioikeda.newslisten.network.AuthInterceptor] の `tokenProvider` から任意のスレッド
 * （OkHttp のコールバックスレッドを含む）で [load] が呼ばれる。
 */
interface SessionStore {
    /** トークンを保存する。既存の値があれば上書きする。成功で `true`、失敗で `false` を返す。 */
    fun save(token: String): Boolean

    /** 保存中のトークンを返す。未保存・削除済み・復号失敗時は `null`。 */
    fun load(): String?

    /** 保存中のトークンを削除する。 */
    fun clear()
}

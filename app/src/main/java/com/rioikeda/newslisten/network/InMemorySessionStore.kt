package com.rioikeda.newslisten.network

/**
 * テスト用のインメモリ実装。Keystore/SharedPreferences に触れずに状態遷移を検証できる。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift:19-22
 * （InMemorySessionStore）のミラー。iOS 側は SessionStore.swift 本体（テストターゲット外）に
 * 置かれているため、Kotlin 版も main ソースセットに配置する。
 */
class InMemorySessionStore(
    initialToken: String? = null,
    /** テスト用: 真なら [save] が保存を行わず `false` を返す（CI-T14 の失敗注入）。 */
    private val saveFails: Boolean = false,
) : SessionStore {
    private var token: String? = initialToken

    @Synchronized
    override fun save(token: String): Boolean {
        if (saveFails) return false
        this.token = token
        return true
    }

    @Synchronized
    override fun load(): String? = token

    @Synchronized
    override fun clear() {
        token = null
    }
}

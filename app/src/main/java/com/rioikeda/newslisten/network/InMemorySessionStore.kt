package com.rioikeda.newslisten.network

/**
 * テスト用のインメモリ実装。Keystore/SharedPreferences に触れずに状態遷移を検証できる。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift:19-22
 * （InMemorySessionStore）のミラー。iOS 側は SessionStore.swift 本体（テストターゲット外）に
 * 置かれているため、Kotlin 版も main ソースセットに配置する。
 */
class InMemorySessionStore(initialToken: String? = null) : SessionStore {
    private var token: String? = initialToken

    override fun save(token: String) {
        this.token = token
    }

    override fun load(): String? = token

    override fun clear() {
        token = null
    }
}

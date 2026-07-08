package com.rioikeda.newslisten.passkey

/**
 * Passkey ViewModel 群のテスト専用フェイク。
 *
 * createCredential/getCredential の呼び出し引数を記録し、挙動をラムダで差し替え可能にする
 * （[com.rioikeda.newslisten.auth.FakeApiClient] 等、既存 Fake と同じ設計方針）。
 */
class FakePasskeyProvider(
    private val onCreateCredential: suspend (optionsJson: String) -> String =
        { error("createCredential is not stubbed") },
    private val onGetCredential: suspend (optionsJson: String) -> String =
        { error("getCredential is not stubbed") },
) : PasskeyProvider {
    var lastCreateCredentialOptionsJson: String? = null
        private set

    var lastGetCredentialOptionsJson: String? = null
        private set

    override suspend fun createCredential(optionsJson: String): String {
        lastCreateCredentialOptionsJson = optionsJson
        return onCreateCredential(optionsJson)
    }

    override suspend fun getCredential(optionsJson: String): String {
        lastGetCredentialOptionsJson = optionsJson
        return onGetCredential(optionsJson)
    }
}

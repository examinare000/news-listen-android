package com.rioikeda.newslisten.passkey

/**
 * パスキー（WebAuthn）セレモニー実行の抽象境界（ADR-066）。
 *
 * 実体は [CredentialManagerPasskeyProvider]（androidx.credentials.CredentialManager の
 * Activity Context ラッパー）。ViewModel 層がこの interface のみに依存することで、
 * Context/CredentialManager の実体・Android フレームワークに直接依存せずテスト可能にする
 * （テストでは [FakePasskeyProvider] に差し替える）。
 */
interface PasskeyProvider {
    /**
     * パスキー登録セレモニーを実行する。
     *
     * @param optionsJson backend の `PasskeyOptionsResponse.options`（JSON 文字列）をそのまま渡す。
     * @return Credential Manager が返す registrationResponseJson（文字列）。
     * @throws PasskeyCancellationException ユーザーがシステム UI を明示的にキャンセルした場合
     * （失敗として扱わない。呼び出し側はエラー表示をしない）。
     * @throws PasskeyProviderException キャンセル以外の失敗（デバイス非対応・通信エラー等）。
     */
    suspend fun createCredential(optionsJson: String): String

    /**
     * パスキー認証セレモニーを実行する。
     *
     * @param optionsJson backend の `PasskeyOptionsResponse.options`（JSON 文字列）をそのまま渡す。
     * @return Credential Manager が返す authenticationResponseJson（文字列）。
     * 例外の意味は [createCredential] と同じ。
     */
    suspend fun getCredential(optionsJson: String): String
}

/**
 * ユーザーがパスキーセレモニーの system UI を明示的にキャンセルした。
 *
 * 呼び出し側（ViewModel）はこれを失敗として扱わずエラーメッセージを表示しない
 * （done 基準・issue #140 P17 要件）。
 */
class PasskeyCancellationException(cause: Throwable? = null) : Exception(cause)

/** キャンセル以外のパスキーセレモニー失敗（デバイス非対応・タイムアウト・システムエラー等）。 */
class PasskeyProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

package com.rioikeda.newslisten.network

import java.util.Base64

/**
 * 暗号文とIVを SharedPreferences 保存用の単一文字列へ直列化するヘルパー。
 *
 * Keystore/SharedPreferences から分離した純粋部分。`java.util.Base64` は API 26 で
 * 追加されたクラスで minSdk=26 の本プロジェクトでは実機でも動作するため、
 * `android.util.Base64`（JVM 単体テストではスタブ化されず例外を投げる）を避けて
 * Robolectric なしでテスト可能にする。
 */
internal object EncryptedTokenEnvelope {
    // Base64 標準アルファベット(A-Za-z0-9+/=)に含まれない文字なので区切りに使って安全。
    private const val DELIMITER = ":"

    fun serialize(iv: ByteArray, ciphertext: ByteArray): String {
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(iv) + DELIMITER + encoder.encodeToString(ciphertext)
    }

    /** 不正な形式・不正な Base64 の場合は `null` を返す（例外を投げない）。 */
    fun deserialize(raw: String): Pair<ByteArray, ByteArray>? {
        val parts = raw.split(DELIMITER)
        if (parts.size != 2) return null
        val decoder = Base64.getDecoder()
        return try {
            decoder.decode(parts[0]) to decoder.decode(parts[1])
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

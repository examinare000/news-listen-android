package com.rioikeda.newslisten.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [EncryptedTokenEnvelope] の直列化/復元の検証。
 *
 * Keystore/SharedPreferences に依存しない純粋部分（Base64 + IV の直列化）のみを対象とする。
 * `java.util.Base64`（API 26+ で利用可能。minSdk=26 のため Android 実機でも動作する）を使うため
 * Robolectric なしで JVM 単体テスト可能。
 */
class EncryptedTokenEnvelopeTest {

    @Test
    fun serializeしたものをdeserializeすると元のIVと暗号文が復元される() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ciphertext = byteArrayOf(10, 20, 30, 40, 50)

        val serialized = EncryptedTokenEnvelope.serialize(iv, ciphertext)
        val restored = EncryptedTokenEnvelope.deserialize(serialized)

        assertArrayEquals(iv, restored?.first)
        assertArrayEquals(ciphertext, restored?.second)
    }

    @Test
    fun 区切り文字を含まない不正な文字列はnullを返す() {
        val restored = EncryptedTokenEnvelope.deserialize("no-delimiter-here")

        assertNull(restored)
    }

    @Test
    fun Base64として不正な文字列はnullを返す() {
        val restored = EncryptedTokenEnvelope.deserialize("!!!not-base64!!!:!!!not-base64!!!")

        assertNull(restored)
    }
}

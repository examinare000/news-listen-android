package com.rioikeda.newslisten.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore（AES/GCM）でセッショントークンを暗号化し SharedPreferences に保管する本番実装。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Networking/SessionStore.swift の `KeychainSessionStore`
 * のミラー（iOS は OS の Keychain、Android は Keystore 由来の鍵で自前暗号化する構成の違いを吸収）。
 * androidx.security-crypto は使用しない（agent-rules/12 準拠の最小依存構成）。
 *
 * Android実機（Keystore）依存のため単体テスト対象外（Robolectric不使用方針）。
 * Base64+IVの直列化のみ [EncryptedTokenEnvelope] に分離し、そちらは単体テスト済み。
 *
 * 失敗時契約: 復号失敗・鍵欠落・Keystore例外時は例外を投げず `null` を返し、壊れた暗号文は
 * 内部で削除する（re-login 導線。端末バックアップ復元等で鍵だけが失われるケースを許容する）。
 * save/clear も Keystore 例外を伝播させない。save 失敗時は暗黙に no-op とし、次回 [load] が
 * `null` を返すことで自然に re-login に誘導する。
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // AuthInterceptor の tokenProvider はリクエスト毎に呼ばれるため、初回 load 後は
    // メモリキャッシュを返して毎回の AES 復号を避ける。
    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cacheLoaded = false

    override fun save(token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val envelope = EncryptedTokenEnvelope.serialize(cipher.iv, ciphertext)
            prefs.edit { putString(PREF_KEY_TOKEN, envelope) }
            cachedToken = token
            cacheLoaded = true
        } catch (e: Exception) {
            // Keystore/Cipher例外は伝播させない。次回load()がnullを返し自然にre-loginへ誘導する。
            Log.w(TAG, "セッショントークンの暗号化に失敗したため保存できません")
        }
    }

    override fun load(): String? {
        if (cacheLoaded) return cachedToken
        val token = decryptStoredToken()
        cachedToken = token
        cacheLoaded = true
        return token
    }

    override fun clear() {
        prefs.edit { remove(PREF_KEY_TOKEN) }
        cachedToken = null
        cacheLoaded = true
    }

    private fun decryptStoredToken(): String? {
        val raw = prefs.getString(PREF_KEY_TOKEN, null) ?: return null
        val envelope = EncryptedTokenEnvelope.deserialize(raw) ?: run {
            clearBrokenState()
            return null
        }
        val (iv, ciphertext) = envelope
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // 鍵欠落（バックアップ復元等）・改ざん・破損暗号文はすべてここに落ちる。
            Log.w(TAG, "セッショントークンの復号に失敗したため破棄しました")
            clearBrokenState()
            null
        }
    }

    /** 復号不能な暗号文を保持し続けない。次回 save() までは load()=null が re-login を促す。 */
    private fun clearBrokenState() {
        prefs.edit { remove(PREF_KEY_TOKEN) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        private const val TAG = "KeystoreSessionStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "news_listen_session_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PREFS_NAME = "news_listen_session_store"
        const val PREF_KEY_TOKEN = "encrypted_token"
    }
}

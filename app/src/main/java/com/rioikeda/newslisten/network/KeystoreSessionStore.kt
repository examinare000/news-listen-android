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
 * save も Keystore 例外を伝播させず `false` を返し、保存済みの値（キャッシュを含む）は変更しない。
 * 呼出元（CI-T14）が失敗を扱う。
 *
 * スレッド安全性（CI-S0-11、#14）: save / clear / 初回 load の「復号 → 書戻し」と
 * [clearBrokenState] は、すべて [storeLock] で直列化する（線形化可能）。`cachedToken` /
 * `cacheLoaded` へのキャッシュ書込みはすべて `storeLock` の中で行い、[load] の 2 回目以降の
 * fast path（lock を取らない volatile 読み）だけが lock の外に残る。これは
 * [com.rioikeda.newslisten.network.AuthInterceptor] の `tokenProvider` から任意のスレッド
 * （OkHttp のコールバックスレッドを含む）で [load] が呼ばれるため。
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** save/clear/初回loadの書戻しを直列化する（CI-S0-11）。中で suspend・外部への callback はしない。 */
    private val storeLock = Any()

    // AuthInterceptor の tokenProvider はリクエスト毎に呼ばれるため、初回 load 後は
    // メモリキャッシュを返して毎回の AES 復号を避ける。書込みはすべて storeLock の中で行い、
    // 2 回目以降の要求で lock を取らない fast path だけが volatile 読みで済ませる
    // （読んだ時点の値が線形化点になる）。
    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cacheLoaded = false

    override fun save(token: String): Boolean = synchronized(storeLock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val envelope = EncryptedTokenEnvelope.serialize(cipher.iv, ciphertext)
            prefs.edit { putString(PREF_KEY_TOKEN, envelope) }
            cachedToken = token
            cacheLoaded = true
            true
        } catch (e: Exception) {
            // Keystore/Cipher例外は伝播させない。呼出元（CI-T14）へ false を返す。既存値は変更しない。
            Log.w(TAG, "セッショントークンの暗号化に失敗したため保存できません")
            false
        }
    }

    override fun load(): String? {
        if (cacheLoaded) return cachedToken
        return synchronized(storeLock) {
            if (cacheLoaded) return@synchronized cachedToken
            val token = decryptStoredToken()
            cachedToken = token
            cacheLoaded = true
            token
        }
    }

    override fun clear(): Unit = synchronized(storeLock) {
        prefs.edit { remove(PREF_KEY_TOKEN) }
        cachedToken = null
        cacheLoaded = true
    }

    /** 呼出元は [storeLock] を保持して呼ぶこと（[load] の lock の中だけから呼ぶ）。 */
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

    /**
     * 復号不能な暗号文を保持し続けない。次回 save() までは load()=null が re-login を促す。
     * 呼出元は [storeLock] を保持して呼ぶこと（[decryptStoredToken] の中だけから呼ぶ）。
     */
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

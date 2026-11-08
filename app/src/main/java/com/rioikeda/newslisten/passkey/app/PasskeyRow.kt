package com.rioikeda.newslisten.passkey.app

import com.rioikeda.newslisten.core.ApiException
import java.util.ArrayList
import java.util.Collections

/** 画面に出る登録済みパスキー 1 件（画面が読む項目だけ）。 */
data class PasskeyRow(
    val credentialId: String,
    val name: String?,
    val createdAt: String,
)

sealed interface CredentialsLoad {
    /** 渡された list は複写して変更不能にする（android-check-06）。 */
    class Loaded(credentials: List<PasskeyRow>) : CredentialsLoad {
        val credentials: List<PasskeyRow> = Collections.unmodifiableList(ArrayList(credentials))
    }

    class Failed(val cause: ApiException) : CredentialsLoad
}

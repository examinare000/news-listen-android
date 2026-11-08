package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.passkey.app.CredentialsLoad
import com.rioikeda.newslisten.passkey.app.PasskeyRow

/** セッション・パスキー一覧の読み。状態は持たず、port の部品をリードモデルに写す。 */
class SessionQueries(private val accountApi: AccountApi) {
    suspend fun sessions(): SessionsLoad =
        try {
            val rows = accountApi.listSessions().map {
                SessionRow(it.id, it.deviceLabel, it.createdAt, it.lastUsedAt, it.current)
            }
            SessionsLoad.Loaded(SessionsView(rows))
        } catch (e: ApiException) {
            SessionsLoad.Failed(e)
        }

    suspend fun credentials(): CredentialsLoad =
        try {
            val rows = accountApi.listPasskeyCredentials().map { PasskeyRow(it.credentialId, it.name, it.createdAt) }
            CredentialsLoad.Loaded(rows)
        } catch (e: ApiException) {
            CredentialsLoad.Failed(e)
        }
}

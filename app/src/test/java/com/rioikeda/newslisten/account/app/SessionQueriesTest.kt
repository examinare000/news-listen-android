package com.rioikeda.newslisten.account.app

import com.rioikeda.newslisten.account.FakeApiClient
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.SessionResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.network.AccountApiAdapter
import com.rioikeda.newslisten.passkey.app.CredentialsLoad
import com.rioikeda.newslisten.passkey.app.PasskeyRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rioikeda.newslisten.passkey.FakeApiClient as PasskeyFakeApiClient

/** `SessionQueries` は一覧の読みだけを持ち、port の部品を read model に写して `Loaded`/`Failed` で返す。 */
class SessionQueriesTest {

    private fun session(id: String, deviceLabel: String? = "Pixel 8", lastUsedAt: String? = "2026-07-07T00:00:00Z", current: Boolean = false) =
        SessionResponse(
            id = id,
            deviceLabel = deviceLabel,
            createdAt = "2026-07-01T00:00:00Z",
            lastUsedAt = lastUsedAt,
            current = current,
        )

    private fun credential(id: String, name: String?) = PasskeyCredential(
        credentialId = id,
        username = "u",
        name = name,
        transports = listOf("internal"),
        aaguid = null,
        signCount = 1,
        createdAt = "2026-07-01T00:00:00Z",
        lastUsedAt = null,
    )

    private fun sessionQueries(client: FakeApiClient) = SessionQueries(AccountApiAdapter(client))

    private fun credentialQueries(client: PasskeyFakeApiClient) = SessionQueries(AccountApiAdapter(client))

    @Test
    fun `android-dec-89 sessionsは取得した一覧をSessionRowに写してLoadedを返す`() = runTest {
        val queries = sessionQueries(
            FakeApiClient(
                onListSessions = {
                    SessionsListResponse(
                        listOf(session("s1", deviceLabel = null, lastUsedAt = null, current = true), session("s2")),
                    )
                },
            ),
        )

        val load = queries.sessions()

        val rows = (load as SessionsLoad.Loaded).view.sessions
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
        assertEquals(SessionRow("s1", null, "2026-07-01T00:00:00Z", null, true), rows[0])
        assertEquals(SessionRow("s2", "Pixel 8", "2026-07-01T00:00:00Z", "2026-07-07T00:00:00Z", false), rows[1])
    }

    @Test
    fun `android-dec-89 sessionsは取得の失敗でFailedを返す`() = runTest {
        val failure = ApiException.HttpError(500)
        val queries = sessionQueries(FakeApiClient(onListSessions = { throw failure }))

        val load = queries.sessions()

        assertSame(failure, (load as SessionsLoad.Failed).cause)
    }

    @Test
    fun `android-dec-89 credentialsは取得した一覧をPasskeyRowに写してLoadedを返す`() = runTest {
        val queries = credentialQueries(
            PasskeyFakeApiClient(
                onListPasskeyCredentials = {
                    PasskeyCredentialsListResponse(listOf(credential("cred-1", null), credential("cred-2", "仕事用")))
                },
            ),
        )

        val load = queries.credentials()

        val rows = (load as CredentialsLoad.Loaded).credentials
        assertEquals(listOf("cred-1", "cred-2"), rows.map { it.credentialId })
        assertNull(rows[0].name)
        assertEquals("仕事用", rows[1].name)
        assertEquals("2026-07-01T00:00:00Z", rows[1].createdAt)
    }

    @Test
    fun `android-dec-89 credentialsは取得の失敗でFailedを返す`() = runTest {
        val failure = ApiException.NetworkError(RuntimeException("offline"))
        val queries = credentialQueries(PasskeyFakeApiClient(onListPasskeyCredentials = { throw failure }))

        val load = queries.credentials()

        assertSame(failure, (load as CredentialsLoad.Failed).cause)
    }

    @Test
    fun `android-check-06 SessionsViewは渡したlistの後の変更とcastした書換えで変わらない`() {
        val source = arrayListOf(SessionRow("s1", null, "t", null, true))

        val view = SessionsView(source)
        source.clear()

        assertEquals(1, view.sessions.size)
        @Suppress("UNCHECKED_CAST")
        val mutable = view.sessions as MutableList<SessionRow>
        assertThrows(UnsupportedOperationException::class.java) {
            mutable.add(SessionRow("s2", null, "t", null, false))
        }
        assertEquals(listOf("s1"), view.sessions.map { it.id })
    }

    @Test
    fun `android-check-06 CredentialsLoadのLoadedは渡したlistの後の変更とcastした書換えで変わらない`() {
        val source = arrayListOf(PasskeyRow("cred-1", null, "t"))

        val loaded = CredentialsLoad.Loaded(source)
        source.clear()

        assertEquals(1, loaded.credentials.size)
        @Suppress("UNCHECKED_CAST")
        val mutable = loaded.credentials as MutableList<PasskeyRow>
        assertThrows(UnsupportedOperationException::class.java) {
            mutable.add(PasskeyRow("cred-2", null, "t"))
        }
        assertEquals(listOf("cred-1"), loaded.credentials.map { it.credentialId })
    }

    @Test
    fun hasOtherSessionsは非currentが無ければfalse() = runTest {
        val queries = sessionQueries(
            FakeApiClient(onListSessions = { SessionsListResponse(listOf(session("s1", current = true))) }),
        )

        val view = (queries.sessions() as SessionsLoad.Loaded).view

        assertFalse(view.hasOtherSessions)
    }

    @Test
    fun hasOtherSessionsは非currentがあればtrue() = runTest {
        val queries = sessionQueries(
            FakeApiClient(
                onListSessions = { SessionsListResponse(listOf(session("s1", current = true), session("s2"))) },
            ),
        )

        val view = (queries.sessions() as SessionsLoad.Loaded).view

        assertTrue(view.hasOtherSessions)
    }
}

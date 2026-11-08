package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.Role
import com.rioikeda.newslisten.auth.domain.SubjectId
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.PasskeyCredential
import com.rioikeda.newslisten.model.PasskeyCredentialsListResponse
import com.rioikeda.newslisten.model.PasskeyOptionsResponse
import com.rioikeda.newslisten.model.RevokeSessionsResponse
import com.rioikeda.newslisten.model.SessionResponse
import com.rioikeda.newslisten.model.SessionsListResponse
import com.rioikeda.newslisten.model.UserResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `AccountApiAdapter` は `ApiClient` の 11 操作を包み、DTO を port の部品/domain の型に写し、例外は捕まえず透過する。 */
class AccountApiAdapterTest {

    private class RecordingApiClient : BaseFakeApiClient() {
        var sessionsResponse: SessionsListResponse = SessionsListResponse(emptyList())
        var credentialsResponse: PasskeyCredentialsListResponse = PasskeyCredentialsListResponse(emptyList())
        var optionsResponse: PasskeyOptionsResponse = PasskeyOptionsResponse("c-1", "opts")
        var loginResponse: LoginResponse? = null
        var profileResponse: UserResponse? = null
        var revokeOthersResponse: RevokeSessionsResponse = RevokeSessionsResponse(0)
        var failure: Throwable? = null

        var receivedLoginUsername: String? = "unset"
        var loginOptionsCalls = 0
        var receivedLoginVerify: Pair<String, JsonObject>? = null
        var receivedRegisterVerify: Pair<String, JsonObject>? = null
        var loginVerifyCalls = 0
        var registerVerifyCalls = 0
        val revokedIds = mutableListOf<String>()

        override suspend fun listSessions(): SessionsListResponse {
            failure?.let { throw it }
            return sessionsResponse
        }

        override suspend fun listPasskeyCredentials(): PasskeyCredentialsListResponse {
            failure?.let { throw it }
            return credentialsResponse
        }

        override suspend fun updateProfile(displayName: String): UserResponse {
            failure?.let { throw it }
            return checkNotNull(profileResponse)
        }

        override suspend fun revokeOtherSessions(): RevokeSessionsResponse {
            failure?.let { throw it }
            return revokeOthersResponse
        }

        override suspend fun revokeSession(id: String) {
            revokedIds += id
            failure?.let { throw it }
        }

        override suspend fun passkeyLoginOptions(username: String?): PasskeyOptionsResponse {
            loginOptionsCalls++
            receivedLoginUsername = username
            failure?.let { throw it }
            return optionsResponse
        }

        override suspend fun passkeyRegisterOptions(): PasskeyOptionsResponse {
            failure?.let { throw it }
            return optionsResponse
        }

        override suspend fun passkeyLoginVerify(challengeId: String, credential: JsonObject): LoginResponse {
            loginVerifyCalls++
            receivedLoginVerify = challengeId to credential
            failure?.let { throw it }
            return checkNotNull(loginResponse)
        }

        override suspend fun passkeyRegisterVerify(challengeId: String, credential: JsonObject) {
            registerVerifyCalls++
            receivedRegisterVerify = challengeId to credential
            failure?.let { throw it }
        }
    }

    @Test
    fun `android-dec-87 passkeyLoginOptionsはusernameにnullを渡す`() = runTest {
        val client = RecordingApiClient().apply {
            optionsResponse = PasskeyOptionsResponse(challengeId = "c-9", options = "the-options")
        }

        val options = AccountApiAdapter(client).passkeyLoginOptions()

        assertNull(client.receivedLoginUsername)
        assertEquals(1, client.loginOptionsCalls)
        assertEquals("c-9", options.challengeId)
        assertEquals("the-options", options.options)
    }

    @Test
    fun `android-dec-87 credentialJsonをJsonObjectに解析してverifyへ渡す`() = runTest {
        val user = UserResponse("u", "admin", "U", "u-1")
        val client = RecordingApiClient().apply { loginResponse = LoginResponse("tk", user) }
        val adapter = AccountApiAdapter(client)

        val issued = adapter.passkeyLoginVerify("c-1", """{"id":"abc"}""")
        adapter.passkeyRegisterVerify("c-2", """{"id":"def"}""")

        val (loginChallenge, loginCredential) = checkNotNull(client.receivedLoginVerify)
        assertEquals("c-1", loginChallenge)
        assertEquals("abc", loginCredential.getValue("id").jsonPrimitive.content)
        val (registerChallenge, registerCredential) = checkNotNull(client.receivedRegisterVerify)
        assertEquals("c-2", registerChallenge)
        assertEquals("def", registerCredential.getValue("id").jsonPrimitive.content)
        assertEquals("tk", issued.token)
        assertEquals(user.toAccountUser(), issued.user)
    }

    @Test
    fun `android-dec-87 不正なcredentialJsonは例外をそのまま投げる`() = runTest {
        val client = RecordingApiClient().apply { loginResponse = LoginResponse("tk", UserResponse("u", "user", "U")) }
        val adapter = AccountApiAdapter(client)

        var loginThrown: Throwable? = null
        try {
            adapter.passkeyLoginVerify("c-1", "not json")
        } catch (e: Exception) {
            loginThrown = e
        }
        var registerThrown: Throwable? = null
        try {
            adapter.passkeyRegisterVerify("c-1", "not json")
        } catch (e: Exception) {
            registerThrown = e
        }

        assertTrue(loginThrown != null && loginThrown !is ApiException)
        assertTrue(registerThrown != null && registerThrown !is ApiException)
        assertEquals(0, client.loginVerifyCalls)
        assertEquals(0, client.registerVerifyCalls)
    }

    @Test
    fun `android-deriv-24 listSessionsはSessionResponseを同じ値のSessionRecordに写す`() = runTest {
        val client = RecordingApiClient().apply {
            sessionsResponse = SessionsListResponse(
                listOf(
                    SessionResponse("s1", null, "2026-07-01T00:00:00Z", null, true),
                    SessionResponse("s2", "Pixel 8", "2026-07-02T00:00:00Z", "2026-07-07T00:00:00Z", false),
                ),
            )
        }

        val records = AccountApiAdapter(client).listSessions()

        assertEquals(2, records.size)
        assertEquals("s1", records[0].id)
        assertNull(records[0].deviceLabel)
        assertEquals("2026-07-01T00:00:00Z", records[0].createdAt)
        assertNull(records[0].lastUsedAt)
        assertEquals(true, records[0].current)
        assertEquals("s2", records[1].id)
        assertEquals("Pixel 8", records[1].deviceLabel)
        assertEquals("2026-07-02T00:00:00Z", records[1].createdAt)
        assertEquals("2026-07-07T00:00:00Z", records[1].lastUsedAt)
        assertEquals(false, records[1].current)
    }

    @Test
    fun `android-deriv-24 listPasskeyCredentialsはPasskeyRecordの3項目に写す`() = runTest {
        val client = RecordingApiClient().apply {
            credentialsResponse = PasskeyCredentialsListResponse(
                listOf(
                    PasskeyCredential("cred-1", "u", null, listOf("internal"), null, 3, "2026-07-01T00:00:00Z", null),
                    PasskeyCredential("cred-2", "u", "仕事用", emptyList(), "aa", 0, "2026-07-02T00:00:00Z", "x"),
                ),
            )
        }

        val records = AccountApiAdapter(client).listPasskeyCredentials()

        assertEquals(2, records.size)
        assertEquals("cred-1", records[0].credentialId)
        assertNull(records[0].name)
        assertEquals("2026-07-01T00:00:00Z", records[0].createdAt)
        assertEquals("cred-2", records[1].credentialId)
        assertEquals("仕事用", records[1].name)
        assertEquals("2026-07-02T00:00:00Z", records[1].createdAt)
    }

    @Test
    fun `android-deriv-24 updateProfileは応答をAccountUserに写す`() = runTest {
        val client = RecordingApiClient().apply {
            profileResponse = UserResponse("u", "admin", "新しい表示名", "u-1")
        }

        val updated = AccountApiAdapter(client).updateProfile("新しい表示名")

        assertEquals(AccountUser("u", "新しい表示名", Role.Admin, SubjectId.of("u-1")), updated)
    }

    @Test
    fun `android-deriv-24 revokeOtherSessionsは失効した件数を返す`() = runTest {
        val client = RecordingApiClient().apply { revokeOthersResponse = RevokeSessionsResponse(revokedCount = 3) }

        assertEquals(3, AccountApiAdapter(client).revokeOtherSessions())
    }

    @Test
    fun `android-deriv-24 ApiClientの例外は捕まえずに同じものを投げる`() = runTest {
        val failure = ApiException.ServerError(500)
        val client = RecordingApiClient().apply { this.failure = failure }

        try {
            AccountApiAdapter(client).revokeSession("s1")
            fail("ApiException が投げられるはず")
        } catch (e: ApiException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("s1"), client.revokedIds)
    }
}

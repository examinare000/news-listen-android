package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.auth.domain.AccountUser
import com.rioikeda.newslisten.auth.domain.IssuedSession
import com.rioikeda.newslisten.auth.domain.Role
import com.rioikeda.newslisten.auth.domain.SubjectId
import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.model.LoginResponse
import com.rioikeda.newslisten.model.UserResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** `AuthApiAdapter` は `ApiClient` の認証 3 操作へ 1 対 1 で委譲し、DTO を domain 型へ写して、例外は捕まえず透過する。 */
class AuthApiAdapterTest {

    private class RecordingApiClient : BaseFakeApiClient() {
        var meResponse: UserResponse? = null
        var loginResponse: LoginResponse? = null
        var failure: Throwable? = null
        var meCalls = 0
        val loginCalls = mutableListOf<Pair<String, String>>()
        val logoutCalls = mutableListOf<String>()

        override suspend fun me(): UserResponse {
            meCalls++
            failure?.let { throw it }
            return checkNotNull(meResponse)
        }

        override suspend fun login(username: String, password: String): LoginResponse {
            loginCalls += username to password
            failure?.let { throw it }
            return checkNotNull(loginResponse)
        }

        override suspend fun logout(token: String) {
            logoutCalls += token
            failure?.let { throw it }
        }
    }

    @Test
    fun `android-dec-85 toAccountUserはroleとsubjectIdを写し不正なuser_idはnullにする`() {
        val admin = UserResponse("u", "admin", "U", "u-1").toAccountUser()
        val invalid = UserResponse("u", "user", "U", "../evil").toAccountUser()

        assertEquals(AccountUser("u", "U", Role.Admin, SubjectId.of("u-1")), admin)
        assertNull(invalid.subjectId)
        assertEquals(Role.User, invalid.role)
    }

    @Test
    fun `android-dec-85 toIssuedSessionはtokenとuserを写す`() {
        val user = UserResponse("u", "admin", "U", "u-1")

        val issued = LoginResponse(token = "tk", user = user).toIssuedSession()

        assertEquals(IssuedSession(token = "tk", user = user.toAccountUser()), issued)
        assertEquals("tk", issued.token)
        assertEquals(user.toAccountUser(), issued.user)
    }

    @Test
    fun `android-dec-85 adapterのloginとmeとlogoutはApiClientへそのまま渡り結果を変換する`() = runTest {
        val user = UserResponse("u", "admin", "U", "u-1")
        val client = RecordingApiClient().apply {
            meResponse = user
            loginResponse = LoginResponse("tk", user)
        }
        val adapter = AuthApiAdapter(client)

        val me = adapter.me()
        val issued = adapter.login("alice", "pw")
        adapter.logout("tk")

        assertEquals(1, client.meCalls)
        assertEquals(listOf("alice" to "pw"), client.loginCalls)
        assertEquals(listOf("tk"), client.logoutCalls)
        assertEquals(user.toAccountUser(), me)
        assertEquals(IssuedSession("tk", user.toAccountUser()), issued)
    }

    @Test
    fun `android-dec-85 adapterはApiClientの例外を捕まえずに呼び手へ伝える`() = runTest {
        val failures = listOf<ApiException>(
            ApiException.Unauthorized(),
            ApiException.NetworkError(RuntimeException("offline")),
        )
        failures.forEach { failure ->
            val client = RecordingApiClient().apply { this.failure = failure }
            val adapter = AuthApiAdapter(client)
            val operations: List<Pair<String, suspend () -> Unit>> = listOf(
                "me" to { adapter.me(); Unit },
                "login" to { adapter.login("u", "p"); Unit },
                "logout" to { adapter.logout("tk") },
            )

            operations.forEach { (name, operation) ->
                try {
                    operation()
                    fail("$name は例外を伝えるはず: $failure")
                } catch (e: ApiException) {
                    assertSame("$name: $failure", failure, e)
                }
            }
        }
    }
}

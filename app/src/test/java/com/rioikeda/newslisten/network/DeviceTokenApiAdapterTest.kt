package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** `DeviceTokenApiAdapter` は `registerDeviceToken` だけを包み、解除は持たない（android-dec-117・android-dec-01）。 */
class DeviceTokenApiAdapterTest {

    private class StubApiClient : BaseFakeApiClient() {
        var failure: Throwable? = null
        val registered = mutableListOf<Pair<String, String>>()
        var unregisterCount = 0

        override suspend fun registerDeviceToken(token: String, platform: String) {
            registered += token to platform
            failure?.let { throw it }
        }

        override suspend fun unregisterDeviceToken(token: String, platform: String) {
            unregisterCount++
        }
    }

    @Test
    fun registerはtokenとplatformでregisterDeviceTokenを1回呼び解除は呼ばない() = runTest {
        val client = StubApiClient()

        DeviceTokenApiAdapter(client).register("t", "android")

        assertEquals(listOf("t" to "android"), client.registered)
        assertEquals(0, client.unregisterCount)
    }

    @Test
    fun registerは例外を捕まえずに同じinstanceを投げる() = runTest {
        val failure = ApiException.HttpError(422)
        val client = StubApiClient().apply { this.failure = failure }

        try {
            DeviceTokenApiAdapter(client).register("t", "android")
            fail("例外が透過されるはず")
        } catch (e: ApiException) {
            assertSame(failure, e)
        }
    }
}

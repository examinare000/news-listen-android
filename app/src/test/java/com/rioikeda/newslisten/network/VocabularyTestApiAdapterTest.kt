package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.app.VocabularyTestAnswerRecord
import com.rioikeda.newslisten.learning.app.VocabularyTestItemRecord
import com.rioikeda.newslisten.model.VocabularyTestItemResponse
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.model.VocabularyTestResultResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/** `VocabularyTestApiAdapter` は `ApiClient` の DTO を port の record へ写し、例外は捕まえず透過する（android-dec-104）。 */
class VocabularyTestApiAdapterTest {

    private class StubApiClient : BaseFakeApiClient() {
        var session = VocabularyTestSessionResponse(emptyList())
        var fetchFailure: Throwable? = null
        var submitFailure: Throwable? = null
        var fetchCalls = 0
        val submitted = mutableListOf<List<VocabularyTestResultItemRequest>>()

        override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse {
            fetchCalls++
            fetchFailure?.let { throw it }
            return session
        }

        override suspend fun submitVocabularyTestResults(
            results: List<VocabularyTestResultItemRequest>,
        ): VocabularyTestResultResponse {
            submitted += results
            submitFailure?.let { throw it }
            return VocabularyTestResultResponse(results.size)
        }
    }

    @Test
    fun `android-dec-104 出題のDTOを5項目そのままrecordへ順序を保って写す`() = runTest {
        val client = StubApiClient().apply {
            session = VocabularyTestSessionResponse(
                listOf(
                    VocabularyTestItemResponse("v-1", "term-1", "意味-1", "example-1", listOf("a", "b", "c")),
                    VocabularyTestItemResponse("v-2", "term-2", "意味-2", "example-2", listOf("d", "e", "f")),
                ),
            )
        }

        val record = VocabularyTestApiAdapter(client).fetchVocabularyTestSession()

        assertEquals(
            listOf(
                VocabularyTestItemRecord("v-1", "term-1", "意味-1", "example-1", listOf("a", "b", "c")),
                VocabularyTestItemRecord("v-2", "term-2", "意味-2", "example-2", listOf("d", "e", "f")),
            ),
            record.items,
        )
        assertEquals(1, client.fetchCalls)
    }

    @Test
    fun `android-dec-104 回答recordをretestCorrectのnullを保ってDTOへ順序どおり写して1回送る`() = runTest {
        val client = StubApiClient()

        VocabularyTestApiAdapter(client).submitVocabularyTestResults(
            listOf(
                VocabularyTestAnswerRecord("v-1", true, null),
                VocabularyTestAnswerRecord("v-2", false, false),
            ),
        )

        assertEquals(
            listOf(
                VocabularyTestResultItemRequest("v-1", true, null),
                VocabularyTestResultItemRequest("v-2", false, false),
            ),
            client.submitted.single(),
        )
    }

    @Test
    fun `android-dec-104 取得の例外は捕まえずに同じinstanceを投げる`() = runTest {
        val failure = ApiException.HttpError(500)
        val client = StubApiClient().apply { fetchFailure = failure }

        try {
            VocabularyTestApiAdapter(client).fetchVocabularyTestSession()
            fail("例外が透過されるはず")
        } catch (e: ApiException) {
            assertSame(failure, e)
        }
    }

    @Test
    fun `android-dec-104 送信の例外は捕まえずに同じinstanceを投げる`() = runTest {
        val failure = ApiException.NetworkError(java.io.IOException("offline"))
        val client = StubApiClient().apply { submitFailure = failure }

        try {
            VocabularyTestApiAdapter(client).submitVocabularyTestResults(
                listOf(VocabularyTestAnswerRecord("v-1", true, null)),
            )
            fail("例外が透過されるはず")
        } catch (e: ApiException) {
            assertSame(failure, e)
        }
    }
}

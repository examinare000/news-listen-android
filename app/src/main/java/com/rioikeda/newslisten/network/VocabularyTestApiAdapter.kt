package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.learning.app.VocabularyTestAnswerRecord
import com.rioikeda.newslisten.learning.app.VocabularyTestApi
import com.rioikeda.newslisten.learning.app.VocabularyTestItemRecord
import com.rioikeda.newslisten.learning.app.VocabularyTestSessionRecord
import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest

/** [ApiClient] の単語テストの読み書きを包み、DTO を port の部品に変える。例外は呼び手へそのまま伝える。 */
internal class VocabularyTestApiAdapter(private val apiClient: ApiClient) : VocabularyTestApi {
    override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionRecord =
        VocabularyTestSessionRecord(
            apiClient.fetchVocabularyTestSession().items.map {
                VocabularyTestItemRecord(it.vocabularyId, it.term, it.meaning, it.example, it.distractors)
            },
        )

    override suspend fun submitVocabularyTestResults(results: List<VocabularyTestAnswerRecord>) {
        apiClient.submitVocabularyTestResults(
            results.map { VocabularyTestResultItemRequest(it.vocabularyId, it.selfKnown, it.retestCorrect) },
        )
    }
}

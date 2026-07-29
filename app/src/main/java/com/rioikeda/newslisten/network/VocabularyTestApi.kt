package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.VocabularyTestResultItemRequest
import com.rioikeda.newslisten.model.VocabularyTestResultResponse
import com.rioikeda.newslisten.model.VocabularyTestSessionResponse

interface VocabularyTestApi {
    suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse
    suspend fun submitVocabularyTestResults(
        results: List<VocabularyTestResultItemRequest>,
    ): VocabularyTestResultResponse
}

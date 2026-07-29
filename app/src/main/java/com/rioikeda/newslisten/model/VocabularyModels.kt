package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SaveVocabularyRequest(
    @SerialName("podcast_id") val podcastId: String,
    val term: String,
)

@Serializable
data class VocabularyItemResponse(
    @SerialName("vocabulary_id") val vocabularyId: String,
    @SerialName("podcast_id") val podcastId: String,
    val term: String,
    val meaning: String,
    val example: String,
    @SerialName("registered_at") val registeredAt: String,
)

@Serializable
data class VocabularyListResponse(
    val vocabulary: List<VocabularyItemResponse>,
    val count: Int,
)

@Serializable
data class DeleteVocabularyResponse(
    val status: String,
    @SerialName("vocabulary_id") val vocabularyId: String,
)

@Serializable
data class VocabularyTestItemResponse(
    @SerialName("vocabulary_id") val vocabularyId: String,
    val term: String,
    val meaning: String,
    val example: String,
    val distractors: List<String>,
)

@Serializable
data class VocabularyTestSessionResponse(
    val items: List<VocabularyTestItemResponse>,
)

@Serializable
data class VocabularyTestResultItemRequest(
    @SerialName("vocabulary_id") val vocabularyId: String,
    @SerialName("self_known") val selfKnown: Boolean,
    @SerialName("retest_correct") val retestCorrect: Boolean?,
)

@Serializable
data class VocabularyTestResultResponse(
    val updated: Int,
)

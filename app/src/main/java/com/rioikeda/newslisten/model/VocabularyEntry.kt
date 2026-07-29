package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Podcast 内で扱う学習語彙。 */
@Serializable
data class VocabularyEntry(
    val term: String,
    @SerialName("meaning_ja") val meaningJa: String,
    val example: String,
)

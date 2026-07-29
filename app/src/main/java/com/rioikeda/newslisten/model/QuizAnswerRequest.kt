package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/** 設問順に選択肢の添字を送るクイズ回答。 */
@Serializable
data class QuizAnswerRequest(
    val answers: List<Int>,
)

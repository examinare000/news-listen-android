package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** サーバー採点後にだけ返る設問単位の結果。 */
@Serializable
data class QuizGradeResult(
    @SerialName("question_index") val questionIndex: Int,
    @SerialName("selected_index") val selectedIndex: Int,
    @SerialName("correct_index") val correctIndex: Int,
    @SerialName("is_correct") val isCorrect: Boolean,
)

/** サーバー採点結果。 */
@Serializable
data class QuizAnswerResponse(
    @SerialName("correct_count") val correctCount: Int,
    val total: Int,
    @SerialName("correct_rate") val correctRate: Double,
    val results: List<QuizGradeResult>,
)

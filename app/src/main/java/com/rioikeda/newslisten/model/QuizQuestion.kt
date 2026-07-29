package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * クライアント公開用クイズ設問。
 *
 * WHY: 正解の answer_index は採点前に漏らさないサーバー秘匿情報であり、この型には持たせない。
 */
@Serializable
data class QuizQuestion(
    val question: String,
    val options: List<String>,
)

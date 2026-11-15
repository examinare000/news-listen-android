package com.rioikeda.newslisten.learning.domain

import java.util.Collections

/** 設問 1 つの採点結果。正解はサーバーの採点後にだけ開示される（ADR-070）。 */
data class QuizItemGrade(
    val questionIndex: Int,
    val selectedIndex: Int,
    val correctIndex: Int,
    val isCorrect: Boolean,
)

/**
 * クイズの採点結果の全体（android-dec-101）。[correctRate] は backend の値のまま持ち、再計算しない。
 * `data class` にしないのは、渡された list を共有せず読み取り専用の写しを持つため。
 */
class QuizGrade(
    val correctCount: Int,
    val total: Int,
    val correctRate: Double,
    items: List<QuizItemGrade>,
) {
    val items: List<QuizItemGrade> = Collections.unmodifiableList(ArrayList(items))

    /** 正答率 0.5 以上が合格（android-rule-learning-03）。 */
    val passed: Boolean = correctRate >= PASS_THRESHOLD

    fun itemFor(questionIndex: Int): QuizItemGrade? = items.firstOrNull { it.questionIndex == questionIndex }

    companion object {
        const val PASS_THRESHOLD = 0.5
    }
}

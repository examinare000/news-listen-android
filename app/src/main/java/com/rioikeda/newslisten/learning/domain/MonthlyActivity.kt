package com.rioikeda.newslisten.learning.domain

/** 月の活動日数の率。31 日を分母にして 0〜1 に収める（android-dec-92）。 */
object MonthlyActivity {
    fun rate(activeDays: Int): Double = (activeDays.toDouble() / 31.0).coerceIn(0.0, 1.0)
}

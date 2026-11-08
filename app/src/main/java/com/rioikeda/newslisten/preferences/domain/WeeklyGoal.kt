package com.rioikeda.newslisten.preferences.domain

/** 週あたりの完聴目標の値域の正本。生成は [of] だけで、値域外は null を返す。 */
@JvmInline
value class WeeklyGoal private constructor(val episodes: Int) {
    /** 1 日あたりの目標本数。 */
    val perDay: Double get() = episodes / 7.0

    companion object {
        val all: List<WeeklyGoal> = listOf(3, 5, 7, 10).map(::WeeklyGoal)

        val DEFAULT: WeeklyGoal = WeeklyGoal(3)

        fun of(raw: Int): WeeklyGoal? = all.firstOrNull { it.episodes == raw }
    }
}

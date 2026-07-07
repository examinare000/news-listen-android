package com.rioikeda.newslisten.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /users/me/listening-streak のレスポンス（issue #165）。
 *
 * 正本: backend/api/schemas.py:149-159（ListeningStreakResponse）。
 *
 * currentStreakDays == 0 は「聴取歴なし」を意味しない。backend の compute_streak
 * （shared/streak.py）は、一昨日以前で連続が途切れた場合にも current_streak_days = 0
 * を返す（last_listened_day は非 null のまま）。lastListenedDay が null になるのは、
 * 聴取記録が一度もない場合のみ（iOS ListeningStreak.swift と同じ注意点）。
 */
@Serializable
data class ListeningStreakResponse(
    @SerialName("current_streak_days") val currentStreakDays: Int,
    @SerialName("today_listened") val todayListened: Boolean,
    @SerialName("last_listened_day") val lastListenedDay: String?,
)

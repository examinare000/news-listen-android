package com.rioikeda.newslisten.preferences.app

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 設定の現在値と選択肢のリードモデル。集合は渡された集合と共有しない読み取り専用の写し。
 */
class PreferencesView(
    val defaultDifficulty: Difficulty,
    val defaultPlaybackSpeed: PlaybackSpeed,
    val weeklyGoal: WeeklyGoal,
    val articleOpenMode: ArticleOpenMode,
    val timeFormat: TimeFormat,
    val sfxEnabled: Boolean,
    val hapticsEnabled: Boolean,
    seenAchievementIds: Set<String>,
) {
    val seenAchievementIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(seenAchievementIds))
    val difficultyOptions: List<Difficulty> = Collections.unmodifiableList(ArrayList(Difficulty.entries))
    val speedOptions: List<PlaybackSpeed> = Collections.unmodifiableList(ArrayList(PlaybackSpeed.all))
    val weeklyGoalOptions: List<WeeklyGoal> = Collections.unmodifiableList(ArrayList(WeeklyGoal.all))

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PreferencesView &&
                defaultDifficulty == other.defaultDifficulty &&
                defaultPlaybackSpeed == other.defaultPlaybackSpeed &&
                weeklyGoal == other.weeklyGoal &&
                articleOpenMode == other.articleOpenMode &&
                timeFormat == other.timeFormat &&
                sfxEnabled == other.sfxEnabled &&
                hapticsEnabled == other.hapticsEnabled &&
                seenAchievementIds == other.seenAchievementIds &&
                difficultyOptions == other.difficultyOptions &&
                speedOptions == other.speedOptions &&
                weeklyGoalOptions == other.weeklyGoalOptions
            )

    override fun hashCode(): Int = listOf(
        defaultDifficulty, defaultPlaybackSpeed, weeklyGoal, articleOpenMode, timeFormat,
        sfxEnabled, hapticsEnabled, seenAchievementIds, difficultyOptions, speedOptions, weeklyGoalOptions,
    ).hashCode()

    override fun toString(): String =
        "PreferencesView(defaultDifficulty=$defaultDifficulty, defaultPlaybackSpeed=$defaultPlaybackSpeed, " +
            "weeklyGoal=$weeklyGoal, articleOpenMode=$articleOpenMode, timeFormat=$timeFormat, " +
            "sfxEnabled=$sfxEnabled, hapticsEnabled=$hapticsEnabled, seenAchievementIds=$seenAchievementIds)"
}

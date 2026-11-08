package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import kotlinx.coroutines.flow.StateFlow

/**
 * ユーザー設定選択（難易度・再生速度・記事の開き方・日付表記）の値所有層（フェーズ10 P10 Task3）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift（AppState が4値を保持し UserDefaults へ
 * 永続化する構成）のミラー。難易度・再生速度のみサーバー同期対象（server-wins。
 * 同期呼び出し自体は [com.rioikeda.newslisten.preferences.app.PreferencesSync] が担い、成功時にこの
 * ストアへ書き戻す）。articleOpenMode/timeFormat はサーバー API に対応フィールドが無く
 * ローカル専用。
 *
 * 値の正本をここに一本化する（呼び出し元が別途 StateFlow を持たない）ことで、
 * 「どちらが最新か」の二重管理を避ける。
 */
interface PreferencesStore {
    /** 既定の英語難易度。保存値が値域外なら既定値は Difficulty.DEFAULT。 */
    val defaultDifficulty: StateFlow<Difficulty>

    /** 既定の再生速度。保存値が値域外なら既定値 1.0。 */
    val defaultPlaybackSpeed: StateFlow<PlaybackSpeed>

    /** 記事タップ時の遷移先。既定値 [ArticleOpenMode.DEFAULT]（IN_APP）。 */
    val articleOpenMode: StateFlow<ArticleOpenMode>

    /** 記事の日付表記。既定値 [TimeFormat.DEFAULT]（ABSOLUTE）。 */
    val timeFormat: StateFlow<TimeFormat>

    /** 短い肯定音を有効にする端末ローカル設定。既定 true。 */
    val sfxEnabled: StateFlow<Boolean>

    /** 操作触覚を有効にする端末ローカル設定。既定 true。 */
    val hapticsEnabled: StateFlow<Boolean>

    /** サーバー同期する週あたりの完聴目標。旧サーバー欠損時・保存値が値域外のときの既定値は3。 */
    val weeklyGoalEpisodes: StateFlow<WeeklyGoal>

    /** 実績解錠の祝福を重複表示しないため、端末で表示済みの実績ID。 */
    val seenAchievementIds: StateFlow<Set<String>>

    /** 既定の英語難易度を更新し永続化する。 */
    suspend fun setDefaultDifficulty(difficulty: Difficulty)

    /** 既定の再生速度を更新し永続化する。値域外の値は無視する（保存も通知もしない）。 */
    suspend fun setDefaultPlaybackSpeed(speed: Double)

    /** 記事タップ時の遷移先を更新し永続化する。 */
    suspend fun setArticleOpenMode(mode: ArticleOpenMode)

    /** 記事の日付表記を更新し永続化する。 */
    suspend fun setTimeFormat(format: TimeFormat)

    suspend fun setSfxEnabled(enabled: Boolean)

    suspend fun setHapticsEnabled(enabled: Boolean)

    /** 週あたりの完聴目標を更新し永続化する。値域外の値は無視する（保存も通知もしない）。 */
    suspend fun setWeeklyGoalEpisodes(episodes: Int)

    suspend fun markAchievementsSeen(ids: Set<String>)

    /**
     * 主体に属する設定（難易度・再生速度・週目標・表示済み実績ID）を既定値へ戻す。端末設定は変えない。
     * 主体が離れるときの後始末が呼ぶ（shared-dec-02）。何度呼んでも同じ状態になる。
     */
    suspend fun clearSubjectScoped()
}

package com.rioikeda.newslisten.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * ユーザー設定選択（難易度・再生速度・記事の開き方・日付表記）の値所有層（フェーズ10 P10 Task3）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift（AppState が4値を保持し UserDefaults へ
 * 永続化する構成）のミラー。難易度・再生速度のみサーバー同期対象（server-wins。
 * 同期呼び出し自体は [com.rioikeda.newslisten.auth.AuthViewModel] が担い、成功時にこの
 * ストアへ書き戻す）。articleOpenMode/timeFormat はサーバー API に対応フィールドが無く
 * ローカル専用。
 *
 * 値の正本をここに一本化する（AuthViewModel 等の呼び出し元が別途 StateFlow を持たない）ことで、
 * 「どちらが最新か」の二重管理を避ける。
 */
interface PreferencesStore {
    /** 既定の英語難易度（[com.rioikeda.newslisten.core.Difficulty.code]）。既定値は Difficulty.DEFAULT。 */
    val defaultDifficulty: StateFlow<String>

    /** 既定の再生速度。既定値 1.0。 */
    val defaultPlaybackSpeed: StateFlow<Double>

    /** 記事タップ時の遷移先。既定値 [ArticleOpenMode.DEFAULT]（IN_APP）。 */
    val articleOpenMode: StateFlow<ArticleOpenMode>

    /** 記事の日付表記。既定値 [TimeFormat.DEFAULT]（ABSOLUTE）。 */
    val timeFormat: StateFlow<TimeFormat>

    /** 既定の英語難易度を更新し永続化する。 */
    suspend fun setDefaultDifficulty(code: String)

    /** 既定の再生速度を更新し永続化する。 */
    suspend fun setDefaultPlaybackSpeed(speed: Double)

    /** 記事タップ時の遷移先を更新し永続化する。 */
    suspend fun setArticleOpenMode(mode: ArticleOpenMode)

    /** 記事の日付表記を更新し永続化する。 */
    suspend fun setTimeFormat(format: TimeFormat)
}

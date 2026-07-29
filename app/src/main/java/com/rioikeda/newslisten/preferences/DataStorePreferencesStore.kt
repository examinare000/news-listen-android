package com.rioikeda.newslisten.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rioikeda.newslisten.core.Difficulty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/**
 * Jetpack DataStore(Preferences) による [PreferencesStore] の本番実装（フェーズ10 P10 Task3）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift（UserDefaults 永続化）のミラー。
 *
 * WHY constructor で `DataStore<Preferences>` を受け取る設計: `Context.dataStore` 拡張プロパティ
 * 経由の暗黙シングルトン化に頼ると、テストで一時ファイルに差し替えられない。呼び出し元
 * （[com.rioikeda.newslisten.di.AppContainer]）が `PreferenceDataStoreFactory.create` で明示的に
 * 生成して注入する。
 *
 * WHY `stateIn(SharingStarted.Eagerly, ...)`: [PreferencesStore] は同期的な `.value` 読み取り
 * （StateFlow）を契約とするため、`dataStore.data`（Flow）を起動時に一度だけ collect して
 * キャッシュする必要がある。Eagerly は [scope] が生きている限り購読を維持し、
 * 「DataStore ローカル値ロード」を起動時に一度実行する設計（呼び出し元の起動シーケンス）と対応する。
 */
class DataStorePreferencesStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : PreferencesStore {

    override val defaultDifficulty: StateFlow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_DEFAULT_DIFFICULTY] ?: Difficulty.DEFAULT.code }
        .stateIn(scope, SharingStarted.Eagerly, Difficulty.DEFAULT.code)

    override val defaultPlaybackSpeed: StateFlow<Double> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_DEFAULT_PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_PLAYBACK_SPEED)

    override val articleOpenMode: StateFlow<ArticleOpenMode> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { ArticleOpenMode.fromCode(it[KEY_ARTICLE_OPEN_MODE]) }
        .stateIn(scope, SharingStarted.Eagerly, ArticleOpenMode.DEFAULT)

    override val timeFormat: StateFlow<TimeFormat> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { TimeFormat.fromCode(it[KEY_TIME_FORMAT]) }
        .stateIn(scope, SharingStarted.Eagerly, TimeFormat.DEFAULT)

    override val sfxEnabled: StateFlow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_SFX_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    override val hapticsEnabled: StateFlow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_HAPTICS_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    override val weeklyGoalEpisodes: StateFlow<Int> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_WEEKLY_GOAL_EPISODES] ?: DEFAULT_WEEKLY_GOAL_EPISODES }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_WEEKLY_GOAL_EPISODES)

    override val seenAchievementIds: StateFlow<Set<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_SEEN_ACHIEVEMENT_IDS].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override suspend fun setDefaultDifficulty(code: String) {
        dataStore.edit { it[KEY_DEFAULT_DIFFICULTY] = code }
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Double) {
        dataStore.edit { it[KEY_DEFAULT_PLAYBACK_SPEED] = speed }
    }

    override suspend fun setArticleOpenMode(mode: ArticleOpenMode) {
        dataStore.edit { it[KEY_ARTICLE_OPEN_MODE] = mode.code }
    }

    override suspend fun setTimeFormat(format: TimeFormat) {
        dataStore.edit { it[KEY_TIME_FORMAT] = format.code }
    }

    override suspend fun setSfxEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SFX_ENABLED] = enabled }
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_HAPTICS_ENABLED] = enabled }
    }

    override suspend fun setWeeklyGoalEpisodes(episodes: Int) {
        dataStore.edit { it[KEY_WEEKLY_GOAL_EPISODES] = episodes }
    }

    override suspend fun markAchievementsSeen(ids: Set<String>) {
        if (ids.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[KEY_SEEN_ACHIEVEMENT_IDS] =
                preferences[KEY_SEEN_ACHIEVEMENT_IDS].orEmpty() + ids
        }
    }

    private companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1.0
        const val DEFAULT_WEEKLY_GOAL_EPISODES = 3
        val KEY_DEFAULT_DIFFICULTY = stringPreferencesKey("default_difficulty")
        val KEY_DEFAULT_PLAYBACK_SPEED = doublePreferencesKey("default_playback_speed")
        val KEY_ARTICLE_OPEN_MODE = stringPreferencesKey("article_open_mode")
        val KEY_TIME_FORMAT = stringPreferencesKey("time_format")
        val KEY_SFX_ENABLED = booleanPreferencesKey("sfx_enabled")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_WEEKLY_GOAL_EPISODES = intPreferencesKey("weekly_goal_episodes")
        val KEY_SEEN_ACHIEVEMENT_IDS = stringSetPreferencesKey("seen_achievement_ids")
    }
}

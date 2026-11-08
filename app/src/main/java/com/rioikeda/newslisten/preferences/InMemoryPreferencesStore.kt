package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.domain.PreferenceItem
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * テスト用のインメモリ実装。DataStore/Context に触れずに [PreferencesStore] 契約を検証できる。
 *
 * 正本: [com.rioikeda.newslisten.network.InMemorySessionStore] と同じ設計方針
 * （本番実装と対になる Fake を main ソースセットに置く）。
 */
class InMemoryPreferencesStore(
    initialDefaultDifficulty: Difficulty = Difficulty.DEFAULT,
    initialDefaultPlaybackSpeed: PlaybackSpeed = PlaybackSpeed.DEFAULT,
    initialArticleOpenMode: ArticleOpenMode = ArticleOpenMode.DEFAULT,
    initialTimeFormat: TimeFormat = TimeFormat.DEFAULT,
    initialSfxEnabled: Boolean = true,
    initialHapticsEnabled: Boolean = true,
    initialWeeklyGoalEpisodes: WeeklyGoal = WeeklyGoal.DEFAULT,
    initialSeenAchievementIds: Set<String> = emptySet(),
) : PreferencesStore {
    private val _defaultDifficulty = MutableStateFlow(initialDefaultDifficulty)
    override val defaultDifficulty: StateFlow<Difficulty> = _defaultDifficulty.asStateFlow()

    private val _defaultPlaybackSpeed = MutableStateFlow(initialDefaultPlaybackSpeed)
    override val defaultPlaybackSpeed: StateFlow<PlaybackSpeed> = _defaultPlaybackSpeed.asStateFlow()

    private val _articleOpenMode = MutableStateFlow(initialArticleOpenMode)
    override val articleOpenMode: StateFlow<ArticleOpenMode> = _articleOpenMode.asStateFlow()

    private val _timeFormat = MutableStateFlow(initialTimeFormat)
    override val timeFormat: StateFlow<TimeFormat> = _timeFormat.asStateFlow()

    private val _sfxEnabled = MutableStateFlow(initialSfxEnabled)
    override val sfxEnabled: StateFlow<Boolean> = _sfxEnabled.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(initialHapticsEnabled)
    override val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _weeklyGoalEpisodes = MutableStateFlow(initialWeeklyGoalEpisodes)
    override val weeklyGoalEpisodes: StateFlow<WeeklyGoal> = _weeklyGoalEpisodes.asStateFlow()

    private val _seenAchievementIds = MutableStateFlow(initialSeenAchievementIds)
    override val seenAchievementIds: StateFlow<Set<String>> = _seenAchievementIds.asStateFlow()

    override suspend fun setDefaultDifficulty(difficulty: Difficulty) {
        _defaultDifficulty.value = difficulty
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Double) {
        _defaultPlaybackSpeed.value = PlaybackSpeed.of(speed) ?: return
    }

    override suspend fun setArticleOpenMode(mode: ArticleOpenMode) {
        _articleOpenMode.value = mode
    }

    override suspend fun setTimeFormat(format: TimeFormat) {
        _timeFormat.value = format
    }

    override suspend fun setSfxEnabled(enabled: Boolean) {
        _sfxEnabled.value = enabled
    }

    override suspend fun setHapticsEnabled(enabled: Boolean) {
        _hapticsEnabled.value = enabled
    }

    override suspend fun setWeeklyGoalEpisodes(episodes: Int) {
        _weeklyGoalEpisodes.value = WeeklyGoal.of(episodes) ?: return
    }

    override suspend fun markAchievementsSeen(ids: Set<String>) {
        _seenAchievementIds.value += ids
    }

    override suspend fun clearSubjectScoped() {
        PreferenceItem.entries.filter { it.subjectScoped }.forEach { item ->
            when (item) {
                PreferenceItem.DEFAULT_DIFFICULTY -> _defaultDifficulty.value = Difficulty.DEFAULT
                PreferenceItem.DEFAULT_PLAYBACK_SPEED -> _defaultPlaybackSpeed.value = PlaybackSpeed.DEFAULT
                PreferenceItem.WEEKLY_GOAL_EPISODES -> _weeklyGoalEpisodes.value = WeeklyGoal.DEFAULT
                PreferenceItem.SEEN_ACHIEVEMENT_IDS -> _seenAchievementIds.value = emptySet()
                PreferenceItem.ARTICLE_OPEN_MODE,
                PreferenceItem.TIME_FORMAT,
                PreferenceItem.SFX_ENABLED,
                PreferenceItem.HAPTICS_ENABLED,
                -> Unit
            }
        }
    }
}

package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.core.Difficulty
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
    initialDefaultDifficulty: String = Difficulty.DEFAULT.code,
    initialDefaultPlaybackSpeed: Double = DEFAULT_PLAYBACK_SPEED,
    initialArticleOpenMode: ArticleOpenMode = ArticleOpenMode.DEFAULT,
    initialTimeFormat: TimeFormat = TimeFormat.DEFAULT,
) : PreferencesStore {
    private val _defaultDifficulty = MutableStateFlow(initialDefaultDifficulty)
    override val defaultDifficulty: StateFlow<String> = _defaultDifficulty.asStateFlow()

    private val _defaultPlaybackSpeed = MutableStateFlow(initialDefaultPlaybackSpeed)
    override val defaultPlaybackSpeed: StateFlow<Double> = _defaultPlaybackSpeed.asStateFlow()

    private val _articleOpenMode = MutableStateFlow(initialArticleOpenMode)
    override val articleOpenMode: StateFlow<ArticleOpenMode> = _articleOpenMode.asStateFlow()

    private val _timeFormat = MutableStateFlow(initialTimeFormat)
    override val timeFormat: StateFlow<TimeFormat> = _timeFormat.asStateFlow()

    override suspend fun setDefaultDifficulty(code: String) {
        _defaultDifficulty.value = code
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Double) {
        _defaultPlaybackSpeed.value = speed
    }

    override suspend fun setArticleOpenMode(mode: ArticleOpenMode) {
        _articleOpenMode.value = mode
    }

    override suspend fun setTimeFormat(format: TimeFormat) {
        _timeFormat.value = format
    }

    private companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1.0
    }
}

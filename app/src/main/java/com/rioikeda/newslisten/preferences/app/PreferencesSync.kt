package com.rioikeda.newslisten.preferences.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.core.PlaybackSpeed
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import com.rioikeda.newslisten.preferences.domain.PreferenceItem
import com.rioikeda.newslisten.preferences.domain.PreferenceRejected
import com.rioikeda.newslisten.preferences.domain.PreferenceSyncResult
import com.rioikeda.newslisten.preferences.domain.WeeklyGoal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 設定の同期の入口（application）。値域の検査、サーバー往復、端末への保存、競合の解決を持つ。
 *
 * - 競合: 後から来た操作を採る。連番は 3 つの設定変更で 1 本を共有し、古い応答は成功でも失敗でも store を変えない。
 * - 主体: 往復を始めた時点の主体 id と書く直前の主体 id が違えば書かない。照合と書込は後始末と同じ [Mutex] の中で行う。
 * - 失敗: サーバー往復の失敗は variant を潰さず結果に包む。端末への保存の失敗は [IOException] を結果に写す。
 */
class PreferencesSync(
    private val api: PreferencesApi,
    private val store: PreferencesStore,
    private val currentSubject: () -> String?,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private val syncFailed = MutableStateFlow(false)

    /** 現在値と選択肢。 */
    val preferences: StateFlow<PreferencesView> = combine(
        combine(store.defaultDifficulty, store.defaultPlaybackSpeed, store.weeklyGoalEpisodes) { difficulty, speed, goal ->
            Triple(difficulty, speed, goal)
        },
        combine(
            store.articleOpenMode,
            store.timeFormat,
            store.sfxEnabled,
            store.hapticsEnabled,
            store.seenAchievementIds,
        ) { mode, format, sfx, haptics, seen ->
            DeviceSettings(mode, format, sfx, haptics, seen)
        },
    ) { (difficulty, speed, goal), device -> viewOf(difficulty, speed, goal, device) }
        .stateIn(scope, SharingStarted.Eagerly, snapshot())

    /** 保存済みトークンでの取り込みが失敗したか。永続化しない。 */
    val preferencesSyncFailed: StateFlow<Boolean> = syncFailed.asStateFlow()

    suspend fun setDefaultDifficulty(code: String): PreferenceSyncResult {
        val difficulty = Difficulty.entries.firstOrNull { it.code == code }
            ?: return rejected(PreferenceItem.DEFAULT_DIFFICULTY)
        return changeWithServer(
            send = { api.updateDefaultDifficulty(difficulty.code) },
            save = { store.setDefaultDifficulty(difficulty) },
        )
    }

    suspend fun setDefaultPlaybackSpeed(raw: Double): PreferenceSyncResult {
        val speed = PlaybackSpeed.of(raw) ?: return rejected(PreferenceItem.DEFAULT_PLAYBACK_SPEED)
        return changeWithServer(
            send = { api.updateDefaultPlaybackSpeed(speed.multiplier) },
            save = { store.setDefaultPlaybackSpeed(speed.multiplier) },
        )
    }

    suspend fun setWeeklyGoal(raw: Int): PreferenceSyncResult {
        val goal = WeeklyGoal.of(raw) ?: return rejected(PreferenceItem.WEEKLY_GOAL_EPISODES)
        return changeWithServer(
            send = { api.updateWeeklyGoalEpisodes(goal.episodes) },
            save = { store.setWeeklyGoalEpisodes(goal.episodes) },
        )
    }

    suspend fun setArticleOpenMode(mode: ArticleOpenMode) {
        store.setArticleOpenMode(mode)
    }

    suspend fun setTimeFormat(format: TimeFormat) {
        store.setTimeFormat(format)
    }

    suspend fun setSfxEnabled(enabled: Boolean) {
        store.setSfxEnabled(enabled)
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        store.setHapticsEnabled(enabled)
    }

    /** サーバーの設定を 1 回取り込む。失敗は既存値を保って印を立て、例外を外へ出さない。 */
    suspend fun syncFromServer() {
        val subject = currentSubject()
        val server = try {
            api.fetchPreferences()
        } catch (e: ApiException) {
            markFailedIfSameSubject(subject)
            return
        } catch (e: IOException) {
            markFailedIfSameSubject(subject)
            return
        }
        mutex.withLock {
            if (currentSubject() != subject) return@withLock
            try {
                store.setDefaultDifficulty(Difficulty.fromCode(server.defaultDifficulty))
                PlaybackSpeed.of(server.defaultPlaybackSpeed)?.let { store.setDefaultPlaybackSpeed(it.multiplier) }
                server.weeklyGoalEpisodes?.let(WeeklyGoal::of)?.let { store.setWeeklyGoalEpisodes(it.episodes) }
                syncFailed.value = false
            } catch (e: IOException) {
                syncFailed.value = true
            }
        }
    }

    suspend fun clearSubjectScoped() {
        mutex.withLock { store.clearSubjectScoped() }
    }

    private fun rejected(item: PreferenceItem): PreferenceSyncResult =
        PreferenceSyncResult.Rejected(PreferenceRejected(item))

    /** 値域検査の後に連番を取り、送信の成功時だけ store へ書く。 */
    private suspend fun changeWithServer(
        send: suspend () -> Unit,
        save: suspend () -> Unit,
    ): PreferenceSyncResult {
        val mine = sequence.incrementAndGet()
        val subject = currentSubject()
        try {
            send()
        } catch (e: ApiException) {
            return if (sequence.get() == mine) PreferenceSyncResult.Failed(e) else PreferenceSyncResult.Superseded
        }
        return mutex.withLock {
            if (sequence.get() != mine || currentSubject() != subject) {
                PreferenceSyncResult.Superseded
            } else {
                try {
                    save()
                    PreferenceSyncResult.Applied
                } catch (e: IOException) {
                    PreferenceSyncResult.SaveFailed
                }
            }
        }
    }

    private suspend fun markFailedIfSameSubject(subject: String?) {
        mutex.withLock {
            if (currentSubject() != subject) return@withLock
            syncFailed.value = true
        }
    }

    private class DeviceSettings(
        val articleOpenMode: ArticleOpenMode,
        val timeFormat: TimeFormat,
        val sfxEnabled: Boolean,
        val hapticsEnabled: Boolean,
        val seenAchievementIds: Set<String>,
    )

    private fun snapshot(): PreferencesView = viewOf(
        store.defaultDifficulty.value,
        store.defaultPlaybackSpeed.value,
        store.weeklyGoalEpisodes.value,
        DeviceSettings(
            store.articleOpenMode.value,
            store.timeFormat.value,
            store.sfxEnabled.value,
            store.hapticsEnabled.value,
            store.seenAchievementIds.value,
        ),
    )

    private fun viewOf(
        difficulty: Difficulty,
        speed: PlaybackSpeed,
        goal: WeeklyGoal,
        device: DeviceSettings,
    ): PreferencesView = PreferencesView(
        defaultDifficulty = difficulty,
        defaultPlaybackSpeed = speed,
        weeklyGoal = goal,
        articleOpenMode = device.articleOpenMode,
        timeFormat = device.timeFormat,
        sfxEnabled = device.sfxEnabled,
        hapticsEnabled = device.hapticsEnabled,
        seenAchievementIds = device.seenAchievementIds,
    )
}

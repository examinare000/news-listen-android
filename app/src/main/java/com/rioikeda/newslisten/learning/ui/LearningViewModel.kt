package com.rioikeda.newslisten.learning.ui

import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningDashboardQuery
import com.rioikeda.newslisten.learning.app.LearningDashboardView
import com.rioikeda.newslisten.learning.app.UnlockedAchievement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LearningUiState(
    val isLoading: Boolean = false,
    val dashboard: LearningDashboardView? = null,
    /** 画面の強調の状態。[LearningViewModel.clearAchievementHighlight] が空にする。 */
    val newlyUnlockedAchievements: List<UnlockedAchievement> = emptyList(),
    val loadFailed: Boolean = false,
)

class LearningViewModel(
    private val query: LearningDashboardQuery,
    private val commands: LearningCommands,
) {
    private val _uiState = MutableStateFlow(LearningUiState())
    val uiState: StateFlow<LearningUiState> = _uiState.asStateFlow()

    suspend fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val view = query.load()
        if (view == null) {
            _uiState.value = _uiState.value.copy(isLoading = false, loadFailed = true)
            return
        }
        commands.markAchievementsSeen(view.unlockedAchievements.mapTo(mutableSetOf()) { it.id }, view.readAs)
        _uiState.value = LearningUiState(
            dashboard = view,
            newlyUnlockedAchievements = view.newlyUnlockedAchievements,
        )
    }

    fun clearAchievementHighlight() {
        _uiState.value = _uiState.value.copy(newlyUnlockedAchievements = emptyList())
    }
}

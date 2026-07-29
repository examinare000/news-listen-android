package com.rioikeda.newslisten.learning

import com.rioikeda.newslisten.model.AchievementResponse
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.model.VocabularyItemResponse
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.network.LearningApi
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LearningUiState(
    val isLoading: Boolean = false,
    val dashboard: LearningDashboardResponse? = null,
    val vocabularyCount: Int = 0,
    val recentVocabulary: List<VocabularyItemResponse> = emptyList(),
    val hasVocabularyTest: Boolean = false,
    val newlyUnlockedAchievements: List<AchievementResponse> = emptyList(),
    val loadFailed: Boolean = false,
)

class LearningViewModel(
    private val api: LearningApi,
    private val preferencesStore: PreferencesStore,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _uiState = MutableStateFlow(LearningUiState())
    val uiState: StateFlow<LearningUiState> = _uiState.asStateFlow()

    suspend fun load(): Unit = withContext(dispatcher) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        val dashboard = try {
            api.fetchLearningDashboard()
        } catch (_: ApiException) {
            _uiState.value = _uiState.value.copy(isLoading = false, loadFailed = true)
            return@withContext
        }

        val vocabulary = try {
            api.fetchVocabulary()
        } catch (_: ApiException) {
            null
        }
        val testSession = try {
            api.fetchVocabularyTestSession()
        } catch (_: ApiException) {
            null
        }
        val unlocked = dashboard.achievements.orEmpty()
        val newlyUnlocked = unlocked.filterNot { it.id in preferencesStore.seenAchievementIds.value }
        preferencesStore.markAchievementsSeen(unlocked.mapTo(mutableSetOf()) { it.id })

        _uiState.value = LearningUiState(
            dashboard = dashboard,
            vocabularyCount = vocabulary?.count ?: 0,
            recentVocabulary = vocabulary?.vocabulary.orEmpty().take(RECENT_VOCABULARY_LIMIT),
            hasVocabularyTest = testSession?.items?.isNotEmpty() == true,
            newlyUnlockedAchievements = newlyUnlocked,
        )
    }

    fun clearAchievementHighlight() {
        _uiState.value = _uiState.value.copy(newlyUnlockedAchievements = emptyList())
    }

    private companion object {
        const val RECENT_VOCABULARY_LIMIT = 5
    }
}

package com.rioikeda.newslisten.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSFeedback
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.RelevanceBar
import com.rioikeda.newslisten.model.LearningDashboardResponse
import com.rioikeda.newslisten.vocabulary.VocabularyTestScreen
import com.rioikeda.newslisten.vocabulary.VocabularyTestViewModel
import kotlinx.coroutines.launch

@Composable
fun LearningScreen(
    viewModel: LearningViewModel,
    feedback: DSFeedback,
    onNavigateToVocabularyTest: (VocabularyTestViewModel) -> Unit = {},
    appContainer: com.rioikeda.newslisten.di.AppContainer? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var showVocabularyTest by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        coroutineScope.launch { viewModel.load() }
    }

    if (showVocabularyTest) {
        val vocabularyTestViewModel = appContainer?.createVocabularyTestViewModel()
        if (vocabularyTestViewModel != null) {
            VocabularyTestScreen(
                viewModel = vocabularyTestViewModel,
                onBack = { showVocabularyTest = false },
                feedback = feedback
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState.isLoading && uiState.dashboard == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(DSSpacing.l))
                        Text(stringResource(R.string.learning_loading))
                    }
                }

                // 表示済みデータがあるときは失敗でも紙面を消さない（iOS と同じ規律）。
                // dashboard != null の分岐が下にあるため、失敗画面は「一度も表示できていない」場合のみ。
                uiState.loadFailed && uiState.dashboard == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(DSSpacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(stringResource(R.string.learning_load_failed), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(DSSpacing.s))
                        Text(stringResource(R.string.learning_load_failed_retry), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(DSSpacing.l))
                        Button(onClick = { coroutineScope.launch { viewModel.load() } }) {
                            Text(stringResource(R.string.learning_retry_button))
                        }
                    }
                }

                uiState.dashboard != null -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DSSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.xl)
                    ) {
                        item {
                            Text(
                                stringResource(R.string.learning_screen_title),
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        item {
                            ProgressSection(dashboard = uiState.dashboard!!)
                        }

                        if (uiState.hasVocabularyTest) {
                            item {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
                                ) {
                                    Text(
                                        stringResource(R.string.learning_vocabulary_test_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { showVocabularyTest = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.learning_vocabulary_test_button))
                                    }
                                }
                            }
                        }

                        item {
                            AchievementsSection(
                                achievements = uiState.dashboard!!.achievements.orEmpty(),
                                newlyUnlocked = uiState.newlyUnlockedAchievements,
                                onClear = { viewModel.clearAchievementHighlight() }
                            )
                        }

                        if (uiState.recentVocabulary.isNotEmpty()) {
                            item {
                                VocabularySection(
                                    vocabularyCount = uiState.vocabularyCount,
                                    recentVocabulary = uiState.recentVocabulary,
                                    hasTest = uiState.hasVocabularyTest,
                                    onTestClick = { showVocabularyTest = true }
                                )
                            }
                        } else if (uiState.vocabularyCount == 0) {
                            item {
                                Text(
                                    stringResource(R.string.learning_vocabulary_load_failed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(DSSpacing.l)
                                )
                            }
                        }

                        item {
                            HistorySection(
                                weeklyGoal = uiState.dashboard!!.weeklyGoal,
                                monthlyActivity = uiState.dashboard!!.monthlyActivity
                            )
                        }

                        item {
                            AccumulationSection(dashboard = uiState.dashboard!!)
                        }

                        item {
                            QuizTrendSection(dashboard = uiState.dashboard!!)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(dashboard: LearningDashboardResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(DSSpacing.l),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        Text(
            stringResource(R.string.learning_progress_title),
            style = MaterialTheme.typography.headlineSmall
        )

        // Streak
        if (dashboard.streak.currentStreakDays > 0) {
            Text(
                stringResource(R.string.streak_format, dashboard.streak.currentStreakDays),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Weekly goal
        dashboard.weeklyGoal?.let { goal ->
            Text(
                goal.progressText,
                style = MaterialTheme.typography.bodyMedium
            )
            RelevanceBar(
                score = goal.progressFraction.toDouble(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "今週の学習目標進捗"
                    }
            )
        }
    }
}

@Composable
private fun AchievementsSection(
    achievements: List<com.rioikeda.newslisten.model.AchievementResponse>,
    newlyUnlocked: List<com.rioikeda.newslisten.model.AchievementResponse>,
    onClear: () -> Unit
) {
    val unlockedById = achievements.associateBy { it.id }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        // Celebration card for newly unlocked
        if (newlyUnlocked.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(DSSpacing.l),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
            ) {
                Text(
                    stringResource(R.string.learning_achievements_unlocked_notify),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                newlyUnlocked.forEach { achievement ->
                    Text(
                        // サーバー側カタログが先行拡張されても落ちない（未知 id は id を表示）
                        AchievementCatalog.firstOrNull { it.id == achievement.id }?.name ?: achievement.id,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Button(
                    onClick = onClear,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close_button))
                }
            }
            Spacer(modifier = Modifier.height(DSSpacing.l))
        }

        // Achievements list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                .padding(DSSpacing.l),
            verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
        ) {
            Text(
                stringResource(R.string.learning_achievements_title),
                style = MaterialTheme.typography.headlineSmall
            )

            AchievementCatalog.forEach { catalogItem ->
                val unlocked = unlockedById[catalogItem.id]
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
                ) {
                    Text(
                        catalogItem.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (unlocked != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        catalogItem.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (unlocked != null) {
                        Text(
                            stringResource(R.string.learning_achievements_unlocked, unlocked.unlockedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularySection(
    vocabularyCount: Int,
    recentVocabulary: List<com.rioikeda.newslisten.model.VocabularyItemResponse>,
    hasTest: Boolean,
    onTestClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(DSSpacing.l),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        Text(
            stringResource(R.string.learning_vocabulary_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.learning_vocabulary_count, vocabularyCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        recentVocabulary.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
            ) {
                Text(item.term, style = MaterialTheme.typography.titleMedium)
                Text(item.meaning, style = MaterialTheme.typography.bodyMedium)
                Text(item.registeredAt.take(10), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (hasTest) {
            Spacer(modifier = Modifier.height(DSSpacing.m))
            Button(modifier = Modifier.fillMaxWidth(), onClick = onTestClick) {
                Text(stringResource(R.string.learning_vocabulary_test_button))
            }
        }
    }
}

@Composable
private fun HistorySection(
    weeklyGoal: com.rioikeda.newslisten.model.WeeklyGoalResponse?,
    monthlyActivity: List<com.rioikeda.newslisten.model.MonthlyActivityResponse>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.l)
    ) {
        weeklyGoal?.history?.takeIf { it.isNotEmpty() }?.let { records ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                    .padding(DSSpacing.l),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
            ) {
                Text(
                    stringResource(R.string.learning_weekly_history_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                records.forEach { record ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
                    ) {
                        Text(record.week, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.learning_weekly_history_fact, record.goal, record.completed),
                            style = MaterialTheme.typography.bodySmall
                        )
                        RelevanceBar(
                            score = (record.completed.toDouble() / maxOf(record.goal, 1)).coerceIn(0.0, 1.0),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (monthlyActivity.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                    .padding(DSSpacing.l),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
            ) {
                Text(
                    stringResource(R.string.learning_monthly_activity_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                monthlyActivity.forEach { activity ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
                    ) {
                        Text(activity.month, style = MaterialTheme.typography.bodySmall)
                        Text(
                            stringResource(R.string.learning_monthly_activity_fact, activity.activeDays),
                            style = MaterialTheme.typography.bodySmall
                        )
                        RelevanceBar(
                            score = (activity.activeDays.toDouble() / 31.0).coerceIn(0.0, 1.0),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccumulationSection(dashboard: LearningDashboardResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(DSSpacing.l),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        Text(
            stringResource(R.string.learning_accumulation_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.learning_accumulation_episodes, dashboard.totalEpisodes),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(R.string.learning_accumulation_vocabulary, dashboard.vocabularyAcquired),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            stringResource(R.string.learning_accumulation_difficulty, dashboard.currentDifficulty),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun QuizTrendSection(dashboard: LearningDashboardResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .padding(DSSpacing.l),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        Text(
            stringResource(R.string.learning_quiz_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.learning_quiz_episodes, dashboard.quiz.quizzedEpisodes),
            style = MaterialTheme.typography.bodyMedium
        )
        dashboard.quiz.averageCorrectRate?.let { rate ->
            Text(
                stringResource(R.string.learning_quiz_rate, rate * 100),
                style = MaterialTheme.typography.bodyMedium
            )
        } ?: run {
            Text(
                stringResource(R.string.learning_quiz_rate_unconfirmed),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (dashboard.quiz.trend.isNotEmpty()) {
            Text(
                stringResource(R.string.learning_quiz_trend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            dashboard.quiz.trend.takeLast(3).forEach { point ->
                Text(
                    "${point.gradedAt.take(10)} - ${String.format("%.0f", point.correctRate * 100)}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

package com.rioikeda.newslisten.vocabulary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSFeedback
import com.rioikeda.newslisten.designsystem.DSFeedbackVocabulary
import com.rioikeda.newslisten.designsystem.DSSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun VocabularyTestScreen(
    viewModel: VocabularyTestViewModel,
    onBack: () -> Unit,
    feedback: DSFeedback? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(uiState.retestFeedback) {
        if (uiState.retestFeedback is RetestFeedback.Correct) {
            feedback?.play(DSFeedbackVocabulary.CORRECT)
        } else if (uiState.retestFeedback is RetestFeedback.Incorrect) {
            feedback?.play(DSFeedbackVocabulary.INCORRECT)
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch { viewModel.load() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Screen title header
        Text(
            stringResource(R.string.vocabulary_test_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(DSSpacing.l)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(DSSpacing.l)
        ) {
            when (uiState.phase) {
            VocabularyTestPhase.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(DSSpacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.vocabulary_test_preparing))
                }
            }

            VocabularyTestPhase.Empty -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(DSSpacing.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.vocabulary_test_empty), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(DSSpacing.s))
                    Text(
                        stringResource(R.string.vocabulary_test_empty_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(DSSpacing.l))
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.vocabulary_test_back_to_learning))
                    }
                }
            }

            VocabularyTestPhase.SelfAssessment -> {
                val item = uiState.currentAssessment
                if (item != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(DSSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_test_progress, uiState.assessmentIndex + 1, uiState.items.size),
                            style = MaterialTheme.typography.labelMedium,
                            // TalkBack へ進行を通知（カード切替は視覚のみのため）
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(
                            stringResource(R.string.vocabulary_test_self_assessment_question),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            stringResource(R.string.vocabulary_test_self_assessment_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SelfAssessmentCard(
                            term = item.term,
                            example = item.example,
                            onAssess = { known -> coroutineScope.launch { viewModel.assess(known = known) } }
                        )
                        // Button controls accessible for TalkBack users and as fallback
                        Button(
                            onClick = { coroutineScope.launch { viewModel.assess(known = false) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.vocabulary_test_button_unknown))
                        }
                        Text(
                            stringResource(R.string.vocabulary_test_button_unknown_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { coroutineScope.launch { viewModel.assess(known = true) } },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.vocabulary_test_button_known))
                        }
                        Text(
                            stringResource(R.string.vocabulary_test_button_known_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            VocabularyTestPhase.Retest -> {
                val item = uiState.currentRetest
                if (item != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(DSSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_test_progress, uiState.retestIndex + 1, uiState.retestItems.size),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(item.term, style = MaterialTheme.typography.headlineLarge)
                        Text(
                            stringResource(R.string.vocabulary_test_retest_question),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        uiState.currentChoices.forEach { choice ->
                            val isCorrectChoice = choice == item.meaning
                            val isAnswerCorrect = uiState.retestFeedback is RetestFeedback.Correct
                            Button(
                                onClick = { coroutineScope.launch { viewModel.answerRetest(choice) } },
                                enabled = !uiState.isAnswerLocked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        when {
                                            uiState.isAnswerLocked && isCorrectChoice -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    )
                            ) {
                                Text(choice)
                            }
                        }

                        // Display feedback
                        uiState.retestFeedback?.let { feedback ->
                            when (feedback) {
                                is RetestFeedback.Correct -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
                                    ) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            stringResource(R.string.vocabulary_test_correct),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                is RetestFeedback.Incorrect -> {
                                    Text(
                                        stringResource(R.string.vocabulary_test_correct_answer, feedback.correctMeaning),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            VocabularyTestPhase.Result -> {
                val summary = uiState.summary
                if (summary != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(DSSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(DSSpacing.l),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_test_result_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (summary.retestCount == 0) {
                            Text(
                                stringResource(R.string.vocabulary_test_result_all_known),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        } else {
                            Text(
                                stringResource(R.string.vocabulary_test_result_known_count, summary.knownCount),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                stringResource(
                                    R.string.vocabulary_test_result_retest_count,
                                    summary.retestCount,
                                    summary.retestCorrectCount
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            stringResource(R.string.vocabulary_test_result_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { coroutineScope.launch { viewModel.load() } }) {
                            Text(stringResource(R.string.vocabulary_test_button_retry))
                        }
                        Button(onClick = onBack) {
                            Text(stringResource(R.string.vocabulary_test_button_back))
                        }
                    }
                }
            }

            VocabularyTestPhase.Submitting -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.vocabulary_test_submitting))
                }
            }

            VocabularyTestPhase.LoadError -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.vocabulary_test_load_error), textAlign = TextAlign.Center)
                    Button(onClick = { coroutineScope.launch { viewModel.load() } }) {
                        Text(stringResource(R.string.vocabulary_test_submit_retry))
                    }
                    Spacer(modifier = Modifier.height(DSSpacing.s))
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.vocabulary_test_back_to_learning))
                    }
                }
            }

            VocabularyTestPhase.SubmitError -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.vocabulary_test_submit_error), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(DSSpacing.s))
                    Text(
                        stringResource(R.string.vocabulary_test_submit_error_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(DSSpacing.l))
                    Button(onClick = { coroutineScope.launch { viewModel.retrySubmission() } }) {
                        Text(stringResource(R.string.vocabulary_test_submit_retry))
                    }
                    Spacer(modifier = Modifier.height(DSSpacing.s))
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.vocabulary_test_back_to_learning))
                    }
                }
                }
            }
        }
    }
}

/**
 * 自己評価カード: 横スワイプでジェスチャー入力可能。
 *
 * ドラッグ中は生値をgraphicsLayerへ直結して追従ラグを解消。
 * 回転係数を translationX * 0.02f（最大2〜3°）に統一。
 * 確定時は方向へ±500dp相当の退場アニメ 0.2s（isExiting で入力ブロック）→ assess → リセット。
 * 非確定リリースのみ0へ戻す。
 */
@Composable
private fun SelfAssessmentCard(
    term: String,
    example: String,
    onAssess: (known: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val minSwipeDistanceDp = 20.dp
    val minSwipeDistance = with(density) { minSwipeDistanceDp.toPx() }
    val thresholdDp = 60.dp
    val threshold = with(density) { thresholdDp.toPx() }
    val exitDistanceDp = 500.dp
    val exitDistance = with(density) { exitDistanceDp.toPx() }
    val animationDurationMs = 200

    var translationX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isExiting by remember { mutableStateOf(false) }
    var cumulativeDragX by remember { mutableFloatStateOf(0f) }
    var cumulativeDragY by remember { mutableFloatStateOf(0f) }

    val animatedTranslationX = animateFloatAsState(
        targetValue = if (isExiting) (if (translationX > 0) exitDistance else -exitDistance) else if (isDragging) translationX else 0f,
        animationSpec = tween(durationMillis = animationDurationMs),
        label = "cardTranslationX"
    )

    LaunchedEffect(isExiting) {
        if (isExiting) {
            launch {
                delay(animationDurationMs.toLong())
                val known = translationX > 0
                onAssess(known)
                translationX = 0f
                isExiting = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (!isExiting) {
                            cumulativeDragX = 0f
                            cumulativeDragY = 0f
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (!isExiting) {
                            cumulativeDragX += dragAmount
                            cumulativeDragY += change.positionChange().y

                            if (abs(cumulativeDragX) > minSwipeDistance &&
                                abs(cumulativeDragX) > abs(cumulativeDragY)) {
                                change.consume()
                                isDragging = true
                                translationX = cumulativeDragX
                            }
                        }
                    },
                    onDragEnd = {
                        if (isDragging && !isExiting) {
                            if (abs(translationX) > threshold) {
                                isExiting = true
                            }
                            isDragging = false
                        }
                    },
                    onDragCancel = {
                        if (!isExiting) {
                            isDragging = false
                            translationX = 0f
                            cumulativeDragX = 0f
                            cumulativeDragY = 0f
                        }
                    }
                )
            }
            .graphicsLayer(
                translationX = animatedTranslationX.value * 0.4f,
                rotationZ = animatedTranslationX.value * 0.02f
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DSSpacing.l),
            verticalArrangement = Arrangement.spacedBy(DSSpacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = {
                Text(term, style = MaterialTheme.typography.headlineLarge)
                Text(example, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            }
        )
    }
}

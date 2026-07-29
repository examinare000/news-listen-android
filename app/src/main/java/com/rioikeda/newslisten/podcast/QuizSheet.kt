package com.rioikeda.newslisten.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.rioikeda.newslisten.designsystem.DSFeedback
import com.rioikeda.newslisten.designsystem.DSFeedbackVocabulary
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.success
import com.rioikeda.newslisten.model.PodcastResponse
import com.rioikeda.newslisten.model.QuizAnswerResponse
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.launch

internal fun quizFeedbackVocabulary(correctRate: Double): DSFeedbackVocabulary =
    if (correctRate >= 0.5) DSFeedbackVocabulary.CORRECT else DSFeedbackVocabulary.INCORRECT

/** 公開設問を提示し、サーバー採点後に正解と選択した誤答を意味色で示す。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizSheet(
    podcast: PodcastResponse,
    feedback: DSFeedback,
    submit: suspend (podcastId: String, answers: List<Int>) -> QuizAnswerResponse,
    onDismiss: () -> Unit,
) {
    val questions = podcast.quiz.orEmpty()
    val selections = remember(podcast.id) {
        mutableStateListOf<Int?>().apply { repeat(questions.size) { add(null) } }
    }
    var grade by remember(podcast.id) { mutableStateOf<QuizAnswerResponse?>(null) }
    var isSubmitting by remember(podcast.id) { mutableStateOf(false) }
    var isUnavailable by remember(podcast.id) { mutableStateOf(false) }
    var errorMessage by remember(podcast.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // 要件6: 採點時に TalkBack へ通知
    val gradeResult = grade
    LaunchedEffect(gradeResult) {
        if (gradeResult != null) {
            view.announceForAccessibility("${gradeResult.correctCount}問中${gradeResult.correctCount}問正解")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(DSSpacing.l),
            verticalArrangement = Arrangement.spacedBy(DSSpacing.l),
        ) {
            Text("理解度クイズ", style = MaterialTheme.typography.headlineSmall)
            when {
                isUnavailable || questions.isEmpty() -> {
                    Text(
                        "このエピソードにはクイズが用意されていません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val result = grade
                    // 要件8: serif スタイルでスコア表示
                    Text(
                        if (result == null) {
                            "本編の内容を${questions.size}問で振り返ります"
                        } else {
                            "${result.correctCount} / ${result.total} 正解"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    questions.forEachIndexed { questionIndex, question ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(DSSpacing.m),
                                verticalArrangement = Arrangement.spacedBy(DSSpacing.s),
                            ) {
                                // 要件8: 設問文を serif スタイルに
                                Text(
                                    "Q${questionIndex + 1}. ${question.question}",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                question.options.forEachIndexed { optionIndex, option ->
                                    val gradeItem = result?.results?.firstOrNull {
                                        it.questionIndex == questionIndex
                                    }
                                    val isCorrect = gradeItem?.correctIndex == optionIndex
                                    val isSelectedIncorrect =
                                        gradeItem?.selectedIndex == optionIndex && gradeItem?.isCorrect == false
                                    val tint = when {
                                        isCorrect -> MaterialTheme.colorScheme.success
                                        isSelectedIncorrect -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    // 要件5: 正解/不正解を icon で表現 + semantics で TalkBack へ伝達
                                    val stateDesc = when {
                                        isCorrect -> "正解"
                                        isSelectedIncorrect -> "誤答"
                                        else -> ""
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isCorrect || isSelectedIncorrect) {
                                                    tint.copy(alpha = 0.12f)
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                }
                                            )
                                            .clickable(enabled = result == null && !isSubmitting) {
                                                selections[questionIndex] = optionIndex
                                            }
                                            .padding(DSSpacing.s)
                                            .semantics {
                                                if (stateDesc.isNotEmpty()) {
                                                    stateDescription = stateDesc
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selections[questionIndex] == optionIndex,
                                            onClick = null,
                                            enabled = result == null && !isSubmitting,
                                        )
                                        Text(option, color = tint)
                                        // 要件5: 採点後に icon を表示
                                        if (isCorrect) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.padding(start = DSSpacing.s)
                                            )
                                        } else if (isSelectedIncorrect) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = null,
                                                tint = tint,
                                                modifier = Modifier.padding(start = DSSpacing.s)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (result == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isSubmitting = true
                                    errorMessage = null
                                    try {
                                        val response = submit(podcast.id, selections.filterNotNull())
                                        grade = response
                                        feedback.play(quizFeedbackVocabulary(response.correctRate))
                                    } catch (e: ApiException.HttpError) {
                                        if (e.code == 404) {
                                            isUnavailable = true
                                        } else {
                                            errorMessage = "採点結果を取得できませんでした。もう一度お試しください。"
                                        }
                                    } catch (_: ApiException) {
                                        errorMessage = "採点結果を取得できませんでした。もう一度お試しください。"
                                    } finally {
                                        isSubmitting = false
                                    }
                                }
                            },
                            enabled = selections.none { it == null } && !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "回答を送信" },
                        ) {
                            // 要件13: 送信中インジケータをサイズ限定
                            if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(if (isSubmitting) "採点中…" else "回答を送信")
                        }
                    }
                    errorMessage?.let {
                        Text("エラー: $it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

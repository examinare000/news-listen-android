package com.rioikeda.newslisten.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.model.TranscriptSegment

/**
 * 再生中の Podcast を操作するプレイヤー UI セクション。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/AudioPlayerView.swift のミラー。
 * - 日本語イントロテキスト表示
 * - トランスクリプト折りたたみ（segments が存在すれば）
 * - シークバー
 * - 再生コントロール（-15秒 / 再生|一時停止 / +30秒）
 * - 再生速度セグメント
 */
@Composable
fun AudioPlayerSection(viewModel: PodcastViewModel) {
    val currentPodcast by viewModel.currentPodcast.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentTime by viewModel.positionSeconds.collectAsState()
    val duration by viewModel.durationSeconds.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    var isTranscriptExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(DSSpacing.l)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DSSpacing.l)
        ) {
            // "再生中" ラベル + 日本語イントロテキスト
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
            ) {
                Text(
                    text = stringResource(R.string.player_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                currentPodcast?.japaneseIntroText?.let { intro ->
                    if (intro.isNotEmpty()) {
                        Text(
                            text = intro,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 3
                        )
                    }
                }
            }

            // トランスクリプト折りたたみセクション
            currentPodcast?.segments?.let { segments ->
                if (segments.isNotEmpty()) {
                    TranscriptSection(
                        segments = segments,
                        isExpanded = isTranscriptExpanded,
                        onToggle = { isTranscriptExpanded = !isTranscriptExpanded }
                    )
                }
            }

            // シークバー
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
            ) {
                // ドラッグ中はローカル値を表示し、指を離した時（onValueChangeFinished）に
                // 一度だけ seekTo する。WHY: onValueChange 毎に seekTo すると毎フレーム
                // PlayerController へシーク命令が飛び、実機（ExoPlayer）では
                // デコーダへの過剰なシーク要求でカクつきや不要な処理負荷を招く。
                var dragPosition by remember { mutableFloatStateOf(0f) }
                var isDragging by remember { mutableStateOf(false) }

                Slider(
                    value = if (isDragging) dragPosition else currentTime.toFloat(),
                    onValueChange = {
                        isDragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        viewModel.seekTo(dragPosition.toDouble())
                        isDragging = false
                    },
                    valueRange = 0f..(duration?.toFloat() ?: 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(currentTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatTime(duration ?: 0.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 再生コントロール
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DSSpacing.xxl + DSSpacing.s, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // -15秒ボタン
                val skipBackwardDescription = stringResource(R.string.player_skip_backward_description)
                Button(
                    onClick = { viewModel.skipBackward() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .width(50.dp)
                        .semantics { contentDescription = skipBackwardDescription }
                ) {
                    Text(
                        text = stringResource(R.string.player_skip_backward),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }

                // 再生|一時停止ボタン（大）
                Button(
                    onClick = { viewModel.togglePlayPause() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.width(80.dp)
                ) {
                    if (isPlaying) {
                        // 一時停止アイコン。Material Icons のコアセットに Pause は含まれず
                        // （material-icons-extended にのみ存在。本アプリは軽量さを優先し
                        // extended 依存を追加していないため）、絵文字テキストで代替する。
                        // a11y のため、装飾テキストの既定セマンティクス（生の "⏸" を読み上げる）
                        // を clearAndSetSemantics で置き換え、contentDescription を明示する。
                        val pauseDescription = stringResource(R.string.player_pause)
                        Text(
                            text = "⏸",
                            style = MaterialTheme.typography.displayLarge,
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = pauseDescription
                            }
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.player_play),
                            modifier = Modifier.height(60.dp)
                        )
                    }
                }

                // +30秒ボタン
                val skipForwardDescription = stringResource(R.string.player_skip_forward_description)
                Button(
                    onClick = { viewModel.skipForward() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .width(50.dp)
                        .semantics { contentDescription = skipForwardDescription }
                ) {
                    Text(
                        text = stringResource(R.string.player_skip_forward),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            }

            // 再生速度セグメント
            SpeedSegmentedControl(
                speeds = PlaybackConstants.speeds,
                currentSpeed = playbackSpeed,
                onSpeedChange = { viewModel.setSpeed(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * トランスクリプト折りたたみセクション。
 */
@Composable
private fun TranscriptSection(
    segments: List<TranscriptSegment>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleDescription = stringResource(
        if (isExpanded) R.string.player_transcript_collapse else R.string.player_transcript_expand
    )
    Column(modifier = modifier.fillMaxWidth()) {
        // 折りたたみヘッダ
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .semantics { contentDescription = toggleDescription }
                .padding(vertical = DSSpacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.player_transcript),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isExpanded) "▼" else "▶",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 展開時の内容
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(DSSpacing.s)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
                ) {
                    segments.forEach { segment ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(DSSpacing.s),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = segment.speaker,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = segment.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 再生速度セグメント化ボタングループ。
 */
@Composable
private fun SpeedSegmentedControl(
    speeds: List<Float>,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.small)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        val selectedSuffix = stringResource(R.string.player_speed_selected)
        speeds.forEach { speed ->
            val isSelected = speed == currentSpeed
            val label = speedLabel(speed)
            val description = stringResource(R.string.player_speed_description, label) +
                if (isSelected) " $selectedSuffix" else ""
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSpeedChange(speed) }
                    .semantics { contentDescription = description }
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        // WHY MaterialTheme.shapes.small: 元は RoundedCornerShape(8.dp) 直値だったが、
                        // このアプリの角丸トークンは Theme.kt の DSShapes（small=10dp/medium=14dp）に
                        // 一元化されている（android-design.md §5.5）。8dp は独立トークンではなく、
                        // 外枠と揃えて視覚的一貫性を保つため small を再利用する。
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(vertical = DSSpacing.xs, horizontal = DSSpacing.xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

/**
 * 秒数を分:秒 形式に整形する（例: 1:05）。
 */
private fun formatTime(seconds: Double): String {
    val m = seconds.toInt() / 60
    val s = seconds.toInt() % 60
    return String.format("%d:%02d", m, s)
}

/**
 * 再生速度を表示用ラベルに整形する。
 */
private fun speedLabel(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) {
        String.format("×%.1f", speed)
    } else {
        String.format("×%.2f", speed)
    }
}

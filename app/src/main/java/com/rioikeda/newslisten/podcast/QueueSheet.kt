package com.rioikeda.newslisten.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.core.PlaybackQueue
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.model.PodcastResponse
import kotlinx.coroutines.launch

/**
 * 再生キュー（プレイリスト）の確認・並べ替え・削除シート（issue #81）。
 * 現在再生中と待機列（upNext）を表示し、待機列は上下ボタンで並べ替え・削除ボタンで削除できる。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/QueueSheet.swift のミラー（iOS はスワイプ削除だが
 * Android は意図的に adaptation）。
 * WHY 削除もボタン方式（スワイプではない）: Compose には reorderable ライブラリがなく、
 * 各行に上下移動ボタンを配置している。同じ行にスワイプ削除を共存させると、
 * 上下ボタンのタップ操作とスワイプジェスチャが干渉しうるため、削除も同じボタン方式に揃えて
 * ジェスチャ競合を避ける。
 * moveUpNext は SwiftUI onMove 規約（削除前オフセット方式）に準拠する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    viewModel: PodcastViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue by viewModel.queue.collectAsState()
    val currentPodcast by viewModel.currentPodcast.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_sheet_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(android.R.string.cancel)
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
        ) {
            // 再生中セクション（currentPodcast と queue.current の両者が揃っているときのみ表示）
            if (currentPodcast != null && queue.current != null) {
                item {
                    Text(
                        text = stringResource(R.string.queue_section_now_playing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(
                            horizontal = DSSpacing.l,
                            vertical = DSSpacing.m
                        )
                    )
                }
                item {
                    QueueRow(podcast = queue.current!!)
                }
            }

            // 再生待ちセクション
            item {
                Text(
                    text = stringResource(R.string.queue_section_up_next),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        horizontal = DSSpacing.l,
                        vertical = DSSpacing.m
                    )
                )
            }

            if (queue.upNext.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.queue_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = DSSpacing.l,
                            vertical = DSSpacing.m
                        )
                    )
                }
            } else {
                itemsIndexed(queue.upNext) { index, podcast ->
                    QueueUpNextRow(
                        podcast = podcast,
                        index = index,
                        totalCount = queue.upNext.size,
                        onDelete = {
                            scope.launch { viewModel.removeFromQueue(podcast.id) }
                        },
                        onMoveUp = {
                            // 上へ移動: from = index, toOffset = index - 1
                            // PlaybackQueue.moveUpNext の KDoc §2.7 参照：削除前オフセット方式
                            scope.launch { viewModel.moveUpNext(index, index - 1) }
                        },
                        onMoveDown = {
                            // 下へ移動: from = index, toOffset = index + 2
                            // (SwiftUI onMove semantics: toOffset は削除前の位置系)
                            scope.launch { viewModel.moveUpNext(index, index + 2) }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 再生中のエピソード行（タイトル＝日本語イントロ要約・難易度・長さ）。
 */
@Composable
private fun QueueRow(podcast: PodcastResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DSSpacing.l,
                vertical = DSSpacing.m
            ),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
    ) {
        Text(
            text = podcast.japaneseIntroText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${podcast.difficulty} · ${formatDuration(podcast.durationSeconds)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = DSSpacing.l)
    )
}

/**
 * 再生待ち行（上下ボタン・削除ボタン付き）。
 * WHY 上下ボタン方式：Compose に reorderable ライブラリがないため、
 * 各行に ▲▼ ボタンを配置して moveUpNext を手動呼び出しする。
 * SwiftUI onMove の削除前オフセット方式に準拠し、上移動は (i, i-1)、
 * 下移動は (i, i+2) を指定する（PlaybackQueue.moveUpNext の KDoc §2.7 参照）。
 */
@Composable
private fun QueueUpNextRow(
    podcast: PodcastResponse,
    index: Int,
    totalCount: Int,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = DSSpacing.l,
                    vertical = DSSpacing.m
                ),
            horizontalArrangement = Arrangement.spacedBy(DSSpacing.m),
            verticalAlignment = Alignment.Top
        ) {
            // コンテンツ（タイトル + メタ情報）
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.xs)
            ) {
                Text(
                    text = podcast.japaneseIntroText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${podcast.difficulty} · ${formatDuration(podcast.durationSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 上下移動ボタン・削除ボタン
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DSSpacing.xs)
            ) {
                // 上へ移動ボタン（最上行は無効化）
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.queue_move_up_description),
                        tint = if (index > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // 下へ移動ボタン（最下行は無効化）
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.queue_move_down_description),
                        tint = if (index < totalCount - 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // 削除ボタン
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.queue_remove_description),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 区切り線
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = DSSpacing.l)
        )
    }
}

/**
 * 秒数を分:秒 形式に整形する（例: 1:05）。
 */
private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", mins, secs)
}

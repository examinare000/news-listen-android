package com.rioikeda.newslisten.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.model.PodcastResponse

/**
 * Podcast 一覧の各行。タイトル・難易度・日付・長さ・ステータスバッジを表示する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift のミラー。
 */
@Composable
fun PodcastRowView(
    podcast: PodcastResponse,
    isPlaying: Boolean,
    downloadingIds: Set<String> = emptySet(),
    downloadedIds: Set<String> = emptySet(),
    onDownloadTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = DSSpacing.l,
                    vertical = DSSpacing.s
                ),
            horizontalArrangement = Arrangement.spacedBy(DSSpacing.m),
            verticalAlignment = Alignment.Top
        ) {
            // 再生状態アイコン
            Box(
                modifier = Modifier.width(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isPlaying) "再生中" else "未再生",
                    tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                )
            }

            // コンテンツ（タイトル + メタ情報）
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
            ) {
                // タイトル
                Text(
                    text = podcast.title.take(60),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // メタ情報行：難易度 + 長さ + ステータス + 日付
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DSSpacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 難易度バッジ
                    DifficultyBadge(podcast.difficulty)

                    // 長さ
                    Text(
                        text = formatDuration(podcast.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // ステータスバッジ
                    PodcastStatusBadge.from(podcast).let { badge ->
                        when (badge) {
                            is PodcastStatusBadge.Processing -> {
                                Text(
                                    text = "生成中",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            is PodcastStatusBadge.Failed -> {
                                Text(
                                    text = "失敗",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            is PodcastStatusBadge.None -> {
                                // completed 状態はバッジ非表示
                            }
                        }
                    }

                    // スペーサ
                    Box(modifier = Modifier.weight(1f))

                    // 日付
                    Text(
                        text = formatDate(podcast.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // ダウンロードボタン（processing/failed の場合は表示しない）
                    if (PodcastStatusBadge.from(podcast) is PodcastStatusBadge.None) {
                        DownloadButton(
                            isDownloading = podcast.id in downloadingIds,
                            isDownloaded = podcast.id in downloadedIds,
                            onDownloadTap = onDownloadTap,
                            modifier = Modifier.padding(start = DSSpacing.s)
                        )
                    }
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
 * ダウンロード状態に応じたボタンを返す。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift:80-102
 * - 未キャッシュ + completed: ダウンロードボタン（iOS: arrow.down.circle。Android は
 *   Icons.Filled.Download が material-icons-core に非搭載のため "↓" テキストグリフで代替し、
 *   a11y は contentDescription（podcast_download_label）を semantics で補う）
 * - ダウンロード中（downloadingIds に含まれる）: 不定 CircularProgressIndicator（進捗率なし。
 *   a11y は contentDescription（podcast_downloading）を semantics で補う）
 * - キャッシュ済み（downloadedIds に含まれる）: 静的チェックアイコン（非インタラクティブ）
 */
@Composable
private fun DownloadButton(
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onDownloadTap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    when {
        isDownloading -> {
            // ダウンロード中: 不定 ProgressIndicator。既定の semantics（進捗ロール）だけでは
            // 読み上げ内容が不明瞭なため、AudioPlayerSection の一時停止アイコンと同じ前例に倣い
            // clearAndSetSemantics で contentDescription に置き換える。
            val downloadingLabel = stringResource(R.string.podcast_downloading)
            CircularProgressIndicator(
                modifier = modifier
                    .width(24.dp)
                    .padding(2.dp)
                    .clearAndSetSemantics { contentDescription = downloadingLabel },
                strokeWidth = 2.dp
            )
        }
        isDownloaded -> {
            // キャッシュ済み: 静的チェックアイコン（非インタラクティブ）
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.podcast_downloaded),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = modifier.width(24.dp)
            )
        }
        else -> {
            // 未キャッシュ: ダウンロードボタン。装飾テキスト "↓" 自体の既定 semantics（生の文字を
            // 読み上げる）は clearAndSetSemantics で打ち消し、ボタン側の contentDescription のみ
            // 読み上げさせる（AudioPlayerSection の skip ボタン群と同じ前例）。
            val downloadLabel = stringResource(R.string.podcast_download_label)
            IconButton(
                onClick = { onDownloadTap?.invoke() },
                modifier = modifier
                    .width(32.dp)
                    .semantics { contentDescription = downloadLabel }
            ) {
                Text(
                    text = "↓",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

/**
 * 難易度コードを表示用バッジへ変換する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastRowView.swift:123-127（difficultyLabel）
 * 及び ios/NewsListenApp/NewsListenApp/Core/DifficultyLabel.swift
 */
@Composable
private fun DifficultyBadge(difficulty: String) {
    Text(
        text = when (difficulty) {
            "toeic_300" -> "初級"
            "toeic_500" -> "中級"
            "toeic_650" -> "中上級"
            "toeic_800" -> "上級"
            "toeic_900" -> "超上級"
            else -> difficulty
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 秒数を分:秒 形式に整形する（例: 1:05）。
 */
private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", mins, secs)
}

/**
 * 日付を表示用文字列に整形する。
 *
 * TODO: AppState.timeFormat に対応する相対時間表記（follow-up）。
 * 現状は "YYYY-MM-DD" 形式のみ。
 */
private fun formatDate(createdAt: String): String {
    return createdAt.take(10)
}

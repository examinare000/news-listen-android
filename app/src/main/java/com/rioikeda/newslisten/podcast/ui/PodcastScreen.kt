package com.rioikeda.newslisten.podcast.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.DSFeedback
import com.rioikeda.newslisten.learning.app.LearningCommands
import com.rioikeda.newslisten.learning.app.LearningNotice
import com.rioikeda.newslisten.podcast.app.GenerationFailureReason
import com.rioikeda.newslisten.podcast.app.PlaybackNotice

/**
 * Podcast タブのルートスクリーン。一覧表示と再生中のプレイヤーを担う。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastView.swift のミラー。
 * - 読み込み中 / 空 / 一覧 の3状態を出し分ける。
 * - 行タップで viewModel.playNow(episodeId) を呼ぶ。
 * - 長押しで次に再生 / キューに追加メニューを表示する。
 * - nowPlaying が存在するときのみ AudioPlayerSection を表示する。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastScreen(viewModel: PodcastViewModel, learningCommands: LearningCommands, feedback: DSFeedback) {
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val queueView by viewModel.queueView.collectAsState()
    val downloadingIds by viewModel.downloadingIds.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val scope = rememberCoroutineScope()

    var showQueue by remember { mutableStateOf(false) }
    var expandedMenuId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // トップバー（キューボタン付き）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DSSpacing.l, vertical = DSSpacing.m),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(onClick = { showQueue = true }) {
                BadgedBox(
                    badge = {
                        if (queueView.upNextCount > 0) {
                            Badge {
                                Text(queueView.upNextCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.List,
                        contentDescription = stringResource(R.string.queue_button_label)
                    )
                }
            }
        }

        // 一覧表示エリア
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                isLoading && episodes.isEmpty() -> {
                    // 読み込み中
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.loading_podcasts),
                            modifier = Modifier.padding(top = DSSpacing.m),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                episodes.isEmpty() -> {
                    // 空状態
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.podcast_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    // 一覧表示
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(episodes) { item ->
                            val row = item.row
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            scope.launch {
                                                viewModel.playNow(row.episodeId)
                                            }
                                        },
                                        onLongClick = {
                                            expandedMenuId = row.episodeId
                                        }
                                    )
                            ) {
                                PodcastRowView(
                                    row = row,
                                    isNowPlaying = item.isNowPlaying,
                                    downloadingIds = downloadingIds,
                                    downloadedIds = downloadedIds,
                                    onDownloadTap = {
                                        scope.launch {
                                            viewModel.download(row.episodeId)
                                        }
                                    }
                                )

                                // 長押しメニュー：次に再生 / キューに追加
                                Box {
                                    DropdownMenu(
                                        expanded = expandedMenuId == row.episodeId,
                                        onDismissRequest = { expandedMenuId = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.podcast_play_next)) },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.playNext(row.episodeId)
                                                }
                                                expandedMenuId = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.podcast_add_to_queue)) },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.addToQueue(row.episodeId)
                                                }
                                                expandedMenuId = null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // プレイヤー表示エリア（再生中のときのみ）
        if (nowPlaying != null) {
            AudioPlayerSection(viewModel = viewModel, learningCommands = learningCommands, feedback = feedback)
        }
    }

    // エラーダイアログ
    if (notice != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNotice() },
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(notice?.let(::playbackNoticeMessage) ?: "") },
            confirmButton = {
                Button(onClick = { viewModel.dismissNotice() }) {
                    Text("OK")
                }
            }
        )
    }

    // キューシート
    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false }
        ) {
            QueueSheet(
                viewModel = viewModel,
                onDismiss = { showQueue = false }
            )
        }
    }

    // 初期化：一覧を読み込む
    LaunchedEffect(Unit) {
        viewModel.fetchPodcasts()
        learningCommands.loadVocabularyRegistrations()
    }
}

/** 再生の知らせの種類から、画面に出す文言を選ぶ（android-dec-144）。 */
internal fun playbackNoticeMessage(notice: PlaybackNotice): String =
    when (notice) {
        PlaybackNotice.Offline -> "オフラインのため再生できません"
        PlaybackNotice.CacheRemoved -> "ダウンロード済みの音声が削除されたため再生できません"
        PlaybackNotice.StillGenerating -> "生成中のため再生できません"
        is PlaybackNotice.GenerationFailed -> when (notice.reason) {
            GenerationFailureReason.GENERATION_FAILED -> "生成に失敗しました"
            GenerationFailureReason.QUOTA_EXHAUSTED -> "生成の上限に達したため、生成できませんでした"
        }
        PlaybackNotice.FetchOffline -> "オフラインです。接続を確認してから、もう一度お試しください"
        PlaybackNotice.FetchFailed -> "エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください"
        PlaybackNotice.PlayerFailed -> "再生できませんでした。もう一度お試しください"
        PlaybackNotice.SaveFailed -> "音声を保存できませんでした"
        PlaybackNotice.RemoveFailed -> "音声を削除できませんでした"
        is PlaybackNotice.Learning -> learningNoticeMessage(notice.notice)
    }

/** 学習の知らせの種類から、画面に出す文言を選ぶ（android-dec-144）。 */
internal fun learningNoticeMessage(notice: LearningNotice): String =
    when (notice) {
        LearningNotice.VocabularyFailed -> "語彙の登録に失敗しました"
    }

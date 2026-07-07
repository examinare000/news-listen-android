package com.rioikeda.newslisten.podcast

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
import com.rioikeda.newslisten.model.PodcastResponse

/**
 * Podcast タブのルートスクリーン。一覧表示と再生中のプレイヤーを担う。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Podcast/PodcastView.swift のミラー。
 * - 読み込み中 / 空 / 一覧 の3状態を出し分ける。
 * - 行タップで viewModel.playNow(podcast) を呼ぶ。
 * - 長押しで次に再生 / キューに追加メニューを表示する。
 * - currentPodcast が存在するときのみ AudioPlayerSection を表示する。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PodcastScreen(viewModel: PodcastViewModel) {
    val podcasts by viewModel.podcasts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentPodcast by viewModel.currentPodcast.collectAsState()
    val queue by viewModel.queue.collectAsState()
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
                        if (queue.upNext.isNotEmpty()) {
                            Badge {
                                Text(queue.upNext.size.toString())
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
                isLoading && podcasts.isEmpty() -> {
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
                podcasts.isEmpty() -> {
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
                        items(podcasts) { podcast ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            scope.launch {
                                                viewModel.playNow(podcast)
                                            }
                                        },
                                        onLongClick = {
                                            expandedMenuId = podcast.id
                                        }
                                    )
                            ) {
                                PodcastRowView(
                                    podcast = podcast,
                                    isPlaying = currentPodcast?.id == podcast.id
                                )

                                // 長押しメニュー：次に再生 / キューに追加
                                Box {
                                    DropdownMenu(
                                        expanded = expandedMenuId == podcast.id,
                                        onDismissRequest = { expandedMenuId = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.podcast_play_next)) },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.playNext(podcast)
                                                }
                                                expandedMenuId = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.podcast_add_to_queue)) },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.addToQueue(podcast)
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
        if (currentPodcast != null) {
            AudioPlayerSection(viewModel = viewModel)
        }
    }

    // エラーダイアログ
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                Button(onClick = { viewModel.clearError() }) {
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
    }
}

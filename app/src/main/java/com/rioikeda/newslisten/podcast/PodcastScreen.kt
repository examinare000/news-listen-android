package com.rioikeda.newslisten.podcast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * - 行タップで viewModel.play(podcast) を呼ぶ。
 * - currentPodcast が存在するときのみ AudioPlayerSection を表示する。
 */
@Composable
fun PodcastScreen(viewModel: PodcastViewModel) {
    val podcasts by viewModel.podcasts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val currentPodcast by viewModel.currentPodcast.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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
                            PodcastRowView(
                                podcast = podcast,
                                isPlaying = currentPodcast?.id == podcast.id,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        viewModel.play(podcast)
                                    }
                                }
                            )
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

    // 初期化：一覧を読み込む
    LaunchedEffect(Unit) {
        viewModel.fetchPodcasts()
    }
}

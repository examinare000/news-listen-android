package com.rioikeda.newslisten

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.auth.LoginScreen
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.NewsListenTheme
import com.rioikeda.newslisten.podcast.PodcastViewModel

/**
 * 単一 Activity エントリポイント。認証状態に基づいてコンテンツを切り分ける。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/NewsListenAppApp.swift:35-71（ゲーティング UI）のミラー。
 * iOS の @State authStatus ゲーティングと同型の Compose StateFlow 実装。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 依存グラフから ViewModel を取得
        val appContainer = NewsListenApplication.getAppContainer()
        val authViewModel = appContainer.getAuthViewModel()
        val feedViewModel = appContainer.getFeedViewModel()
        val podcastViewModel = appContainer.getPodcastViewModel()

        setContent {
            NewsListenTheme {
                // 起動時に refreshAuth() を一度だけ実行（トークン確認）
                LaunchedEffect(Unit) {
                    authViewModel.refreshAuth()
                }

                // authState を購読
                val authState = authViewModel.authState.collectAsStateWithLifecycle()

                // AuthState に応じた UI 出し分け
                when (val state = authState.value) {
                    is AuthState.Unknown -> {
                        // トークン確認中のローディング画面
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.loading_auth),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(top = DSSpacing.s)
                                )
                            }
                        }
                    }

                    is AuthState.Unauthenticated -> {
                        // ログイン画面
                        LoginScreen(viewModel = authViewModel)
                    }

                    is AuthState.Authenticated -> {
                        // メインアプリ（3 タブスカフォルド）
                        AppScaffold(feedViewModel, podcastViewModel)
                    }
                }
            }
        }
    }
}

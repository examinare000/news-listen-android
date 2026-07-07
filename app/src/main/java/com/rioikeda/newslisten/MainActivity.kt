package com.rioikeda.newslisten

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
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
    /**
     * POST_NOTIFICATIONS ランタイム権限要求（API 33+ 対応）。
     *
     * WHY ランタイム要求: MediaStyle 通知の表示には POST_NOTIFICATIONS 権限が必要（API 33+）。
     * Manifest では宣言済みだが、Android 13 以上ではランタイムでも要求が必須。
     * 拒否された場合も再生は継続し、通知のみ非表示（ベストエフォート）。
     *
     * WHY ActivityResultContracts.RequestPermission: Accompanist 依存を避け、
     * 標準 Activity APIs のみを使用。
     *
     * WHY 条件付き要求: メディア通知がコア体験のため起動時に要求。iOS に相当権限なし。
     * 未許可のときのみ launch（毎起動の無条件発火を避ける）。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // 権限が許可されたため、ログイン済みの場合は FCM トークンを登録する
            // 未ログイン時は FcmTokenRegistrar の内部ガードによりスキップされる
            lifecycleScope.launch {
                NewsListenApplication.getAppContainer().getFcmTokenRegistrar().onAuthenticated()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // POST_NOTIFICATIONS ランタイム権限要求（API 33+ で未許可のときのみ）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 依存グラフから ViewModel と PreferencesStore を取得
        val appContainer = NewsListenApplication.getAppContainer()
        val authViewModel = appContainer.getAuthViewModel()
        val feedViewModel = appContainer.getFeedViewModel()
        val podcastViewModel = appContainer.getPodcastViewModel()
        val settingsViewModel = appContainer.getSettingsViewModel()
        val preferencesStore = appContainer.getPreferencesStore()
        val accountViewModel = appContainer.getAccountViewModel()
        val sessionsViewModel = appContainer.getSessionsViewModel()

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
                        AppScaffold(
                            feedViewModel,
                            podcastViewModel,
                            settingsViewModel,
                            preferencesStore,
                            authViewModel,
                            accountViewModel,
                            sessionsViewModel
                        )
                    }
                }
            }
        }
    }
}

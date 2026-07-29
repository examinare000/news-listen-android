package com.rioikeda.newslisten

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.account.AccountViewModel
import com.rioikeda.newslisten.account.SessionsViewModel
import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.feed.FeedScreen
import com.rioikeda.newslisten.feed.FeedViewModel
import com.rioikeda.newslisten.engagement.ListeningStreakStore
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.designsystem.DSFeedbackVocabulary
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.rememberDSFeedback
import com.rioikeda.newslisten.passkey.PasskeyCredentialsViewModel
import com.rioikeda.newslisten.passkey.PasskeyRegistrationViewModel
import com.rioikeda.newslisten.podcast.PodcastScreen
import com.rioikeda.newslisten.podcast.PodcastViewModel
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.settings.SettingsScreen
import com.rioikeda.newslisten.settings.SettingsViewModel

/**
 * メインのアプリケーション スカフォルド。3 タブ（フィード / Podcast / 設定）を Material3 NavigationBar で提供する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/NewsListenAppApp.swift:73-108（ContentView）のミラー。
 * iOS の TabView と同型の Material3 BottomNavigationBar 実装。各タブはプレースホルダ（フェーズ4以降で実装）。
 *
 * @param feedViewModel フィード タブの ViewModel（AppContainer.getFeedViewModel() から供給）。
 * @param podcastViewModel Podcast タブの ViewModel（AppContainer.getPodcastViewModel() から供給）。
 * @param settingsViewModel 設定 タブの ViewModel（AppContainer.getSettingsViewModel() から供給）。
 * @param preferencesStore ユーザー設定値の永続化層（AppContainer.getPreferencesStore() から供給）。
 * @param authViewModel 認証状態 ViewModel（SettingsScreen の admin ゲート判定用）。
 * @param accountViewModel アカウント管理 ViewModel（AppContainer.getAccountViewModel() から供給・フェーズ11 P11 T3）。
 * @param sessionsViewModel デバイス管理 ViewModel（AppContainer.getSessionsViewModel() から供給・フェーズ11 P11 T4）。
 * @param passkeyRegistrationViewModel パスキー登録 ViewModel（MainActivity が Activity 単位で
 * 生成し供給する。フェーズ17 P17・issue #140）。
 * @param passkeyCredentialsViewModel パスキー一覧・削除 ViewModel（AppContainer.
 * getPasskeyCredentialsViewModel() から供給・フェーズ17 P17・issue #140）。
 */
@Composable
fun AppScaffold(
    feedViewModel: FeedViewModel,
    podcastViewModel: PodcastViewModel,
    settingsViewModel: SettingsViewModel,
    preferencesStore: PreferencesStore,
    authViewModel: AuthViewModel,
    accountViewModel: AccountViewModel,
    sessionsViewModel: SessionsViewModel,
    passkeyRegistrationViewModel: PasskeyRegistrationViewModel,
    passkeyCredentialsViewModel: PasskeyCredentialsViewModel,
    listeningStreakStore: ListeningStreakStore,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val feedback = rememberDSFeedback(preferencesStore)
    val listeningStreak by listeningStreakStore.listeningStreak.collectAsStateWithLifecycle()

    // 要件4: streak 増加検知の一本化。AppContainer で onStreakIncreased へ feedback を配線。
    // AppScaffold でのみ feedback が available なため、ここで設定する。
    LaunchedEffect(feedback, listeningStreakStore) {
        if (listeningStreakStore is com.rioikeda.newslisten.engagement.ApiListeningStreakStore) {
            listeningStreakStore.onStreakIncreased = {
                feedback.play(DSFeedbackVocabulary.STREAK_UP)
            }
        }
    }

    LaunchedEffect(Unit) {
        listeningStreakStore.refresh()
    }

    // predictive back: トップ画面でない場合、戻る操作でトップ（index 0）へ戻す
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    // Get admin status from authViewModel
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val isAdmin = (authState as? AuthState.Authenticated)?.user?.role == "admin"

    val tabs = listOf(
        TabItem(
            label = stringResource(R.string.tab_feed),
            icon = Icons.Filled.Home,
            screen = { FeedScreen(feedViewModel, feedback) }
        ),
        TabItem(
            label = stringResource(R.string.tab_podcast),
            icon = Icons.Filled.Favorite,
            screen = { PodcastScreen(podcastViewModel, feedback) }
        ),
        TabItem(
            label = stringResource(R.string.tab_settings),
            icon = Icons.Filled.Settings,
            screen = {
                SettingsScreen(
                    settingsViewModel,
                    preferencesStore,
                    authViewModel,
                    accountViewModel,
                    sessionsViewModel,
                    passkeyRegistrationViewModel,
                    passkeyCredentialsViewModel,
                    isAdmin
                )
            }
        )
    )

    Scaffold(
        topBar = {
            if (selectedTab != 2 && shouldShowStreakBadge(listeningStreak)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = DSSpacing.l, vertical = DSSpacing.s),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // 要件12: streak バッジに a11y contentDescription
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.semantics {
                            contentDescription = "聴取ストリーク ${listeningStreak!!.currentStreakDays}日連続"
                        }
                    ) {
                        Text(
                            text = "${listeningStreak!!.currentStreakDays}日連続",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(
                                horizontal = DSSpacing.s,
                                vertical = DSSpacing.xs,
                            ),
                        )
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            tabs[selectedTab].screen()
        }
    }
}

internal fun shouldShowStreakBadge(streak: ListeningStreakResponse?): Boolean =
    streak != null && streak.currentStreakDays > 0 && streak.lastListenedDay != null

/** タブの構成要素（ラベル、アイコン、画面 Composable）。 */
private data class TabItem(
    val label: String,
    val icon: ImageVector,
    val screen: @Composable () -> Unit
)

/** フィード タブ プレースホルダ。 */
@Composable
private fun FeedTabPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.placeholder_feed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** Podcast タブ プレースホルダ。 */
@Composable
private fun PodcastTabPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.placeholder_podcast),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

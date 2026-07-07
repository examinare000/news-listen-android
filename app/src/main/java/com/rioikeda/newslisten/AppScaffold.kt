package com.rioikeda.newslisten

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.rioikeda.newslisten.feed.FeedScreen
import com.rioikeda.newslisten.feed.FeedViewModel

/**
 * メインのアプリケーション スカフォルド。3 タブ（フィード / Podcast / 設定）を Material3 NavigationBar で提供する。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/NewsListenAppApp.swift:73-108（ContentView）のミラー。
 * iOS の TabView と同型の Material3 BottomNavigationBar 実装。各タブはプレースホルダ（フェーズ4以降で実装）。
 */
@Composable
fun AppScaffold(feedViewModel: FeedViewModel) {
    var selectedTab by remember { mutableStateOf(0) }

    val tabs = listOf(
        TabItem(
            label = stringResource(R.string.tab_feed),
            icon = Icons.Filled.Home,
            screen = { FeedScreen(feedViewModel) }
        ),
        TabItem(
            label = stringResource(R.string.tab_podcast),
            icon = Icons.Filled.Favorite,
            screen = { PodcastTabPlaceholder() }
        ),
        TabItem(
            label = stringResource(R.string.tab_settings),
            icon = Icons.Filled.Settings,
            screen = { SettingsTabPlaceholder() }
        )
    )

    Scaffold(
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

/** 設定 タブ プレースホルダ。 */
@Composable
private fun SettingsTabPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.placeholder_settings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

package com.rioikeda.newslisten.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.account.AccountViewModel
import com.rioikeda.newslisten.account.SessionsViewModel
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.passkey.PasskeyCredentialsViewModel
import com.rioikeda.newslisten.passkey.PasskeyRegistrationViewModel
import com.rioikeda.newslisten.preferences.ArticleOpenMode
import com.rioikeda.newslisten.preferences.PreferencesStore
import com.rioikeda.newslisten.preferences.TimeFormat
import kotlinx.coroutines.launch

/**
 * 設定タブのルート画面。iOS SettingsView.swift のミラー（フェーズ10 P10 Task5 & フェーズ11 P11 T3/T4）。
 *
 * RSS ソース管理・おすすめサイト・生成クォータ・聴取ストリーク・難易度/再生速度・
 * 記事の開き方・日付表記・アカウント（表示名/パスワード）・ログイン中デバイス管理を扱う。
 *
 * @param viewModel SettingsViewModel（RSS ソース・おすすめサイト・クォータ・ストリーク管理）
 * @param preferencesStore PreferencesStore（難易度・再生速度・記事開き方・日付表記）
 * @param authViewModel AuthViewModel（アカウント操作時の認証状態反映）
 * @param accountViewModel AccountViewModel（表示名/パスワード変更）
 * @param sessionsViewModel SessionsViewModel（デバイス一覧・失効管理）
 * @param passkeyRegistrationViewModel PasskeyRegistrationViewModel（パスキー登録・フェーズ17 P17）
 * @param passkeyCredentialsViewModel PasskeyCredentialsViewModel（パスキー一覧・削除・フェーズ17 P17）
 * @param isAdmin admin ロール判定フラグ（RSS ソース編集権限）
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    preferencesStore: PreferencesStore,
    authViewModel: AuthViewModel,
    accountViewModel: AccountViewModel,
    sessionsViewModel: SessionsViewModel,
    passkeyRegistrationViewModel: PasskeyRegistrationViewModel,
    passkeyCredentialsViewModel: PasskeyCredentialsViewModel,
    isAdmin: Boolean = false,
) {
    val coroutineScope = rememberCoroutineScope()

    // 画面ロード時にデータを取得
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            viewModel.loadSources()
            viewModel.loadFeaturedSites()
            viewModel.loadGenerationQuota()
            viewModel.loadListeningStreak()
            sessionsViewModel.loadSessions()
            passkeyCredentialsViewModel.loadCredentials()
        }
    }

    // 認証済み状態で表示名をプリフィル（iOS AccountSettingsView.swift:81 相当の onAppear パターン）。
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    LaunchedEffect(authState) {
        if (authState is com.rioikeda.newslisten.auth.AuthState.Authenticated) {
            accountViewModel.prefillDisplayName()
        }
    }

    // SettingsViewModel state
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val featuredSites by viewModel.featuredSites.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val generationQuota by viewModel.generationQuota.collectAsStateWithLifecycle()
    val generationQuotaLoadFailed by viewModel.generationQuotaLoadFailed.collectAsStateWithLifecycle()
    val listeningStreak by viewModel.listeningStreak.collectAsStateWithLifecycle()
    val listeningStreakLoadFailed by viewModel.listeningStreakLoadFailed.collectAsStateWithLifecycle()
    val featuredSitesLoadFailed by viewModel.featuredSitesLoadFailed.collectAsStateWithLifecycle()

    // AuthViewModel state
    val preferencesSyncFailed by authViewModel.preferencesSyncFailed.collectAsStateWithLifecycle()

    // PreferencesStore state
    val defaultDifficulty by preferencesStore.defaultDifficulty.collectAsStateWithLifecycle()
    val defaultPlaybackSpeed by preferencesStore.defaultPlaybackSpeed.collectAsStateWithLifecycle()
    val articleOpenMode by preferencesStore.articleOpenMode.collectAsStateWithLifecycle()
    val timeFormat by preferencesStore.timeFormat.collectAsStateWithLifecycle()

    // AccountViewModel state
    val displayName by accountViewModel.displayName.collectAsStateWithLifecycle()
    val currentPassword by accountViewModel.currentPassword.collectAsStateWithLifecycle()
    val newPassword by accountViewModel.newPassword.collectAsStateWithLifecycle()
    val accountIsLoading by accountViewModel.isLoading.collectAsStateWithLifecycle()
    val profileMessage by accountViewModel.profileMessage.collectAsStateWithLifecycle()
    val profileMessageIsError by accountViewModel.profileMessageIsError.collectAsStateWithLifecycle()
    val passwordMessage by accountViewModel.passwordMessage.collectAsStateWithLifecycle()
    val passwordMessageIsError by accountViewModel.passwordMessageIsError.collectAsStateWithLifecycle()

    // SessionsViewModel state
    val sessions by sessionsViewModel.sessions.collectAsStateWithLifecycle()
    val sessionsIsLoading by sessionsViewModel.isLoading.collectAsStateWithLifecycle()
    val sessionsErrorMessage by sessionsViewModel.errorMessage.collectAsStateWithLifecycle()
    val hasOtherSessions by sessionsViewModel.hasOtherSessions.collectAsStateWithLifecycle()
    val revokedOthersCount by sessionsViewModel.revokedOthersCount.collectAsStateWithLifecycle()

    // PasskeyRegistrationViewModel state
    val passkeyIsRegistering by passkeyRegistrationViewModel.isRegistering.collectAsStateWithLifecycle()
    val passkeyRegistrationErrorMessage by passkeyRegistrationViewModel.errorMessage.collectAsStateWithLifecycle()

    // PasskeyCredentialsViewModel state
    val passkeyCredentials by passkeyCredentialsViewModel.credentials.collectAsStateWithLifecycle()
    val passkeyCredentialsIsLoading by passkeyCredentialsViewModel.isLoading.collectAsStateWithLifecycle()
    val passkeyCredentialsErrorMessage by passkeyCredentialsViewModel.errorMessage.collectAsStateWithLifecycle()

    // Local UI state for dialogs and forms
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }
    var showEditSourceDialog by remember { mutableStateOf(false) }
    var editSourceOldUrl by remember { mutableStateOf("") }
    var editSourceName by remember { mutableStateOf("") }
    var editSourceUrl by remember { mutableStateOf("") }
    var selectedDifficultyIndex by remember { mutableIntStateOf(-1) }
    var selectedSpeedIndex by remember { mutableIntStateOf(-1) }
    var selectedArticleOpenModeIndex by remember { mutableIntStateOf(-1) }
    var selectedTimeFormatIndex by remember { mutableIntStateOf(-1) }
    var showRevokeOthersConfirmDialog by remember { mutableStateOf(false) }

    // Initialize selected indices
    LaunchedEffect(defaultDifficulty, defaultPlaybackSpeed, articleOpenMode, timeFormat) {
        selectedDifficultyIndex = Difficulty.entries.indexOfFirst { it.code == defaultDifficulty }
            .takeIf { it >= 0 } ?: 0
        selectedSpeedIndex = PLAYBACK_SPEEDS.indexOfFirst { it == defaultPlaybackSpeed }
            .takeIf { it >= 0 } ?: PLAYBACK_SPEEDS.indexOfFirst { it == 1.0 }
        selectedArticleOpenModeIndex = ArticleOpenMode.entries.indexOf(articleOpenMode)
        selectedTimeFormatIndex = TimeFormat.entries.indexOf(timeFormat)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = DSSpacing.m),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        item {
            Spacer(modifier = Modifier.height(DSSpacing.s))
            Text(
                text = stringResource(R.string.settings_screen_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Preferences 同期失敗バナー
        if (preferencesSyncFailed) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DSSpacing.m)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(DSSpacing.m),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_preferences_sync_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Feed セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_feed))
            SettingsDropdown(
                label = stringResource(R.string.settings_article_open_mode_label),
                options = ArticleOpenMode.entries.map { mode ->
                    when (mode) {
                        ArticleOpenMode.IN_APP -> stringResource(R.string.settings_article_open_mode_in_app)
                        ArticleOpenMode.EXTERNAL -> stringResource(R.string.settings_article_open_mode_external)
                    }
                },
                selectedIndex = selectedArticleOpenModeIndex,
                onSelectionChange = { index ->
                    selectedArticleOpenModeIndex = index
                    coroutineScope.launch {
                        preferencesStore.setArticleOpenMode(ArticleOpenMode.entries[index])
                    }
                }
            )
            SettingsDropdown(
                label = stringResource(R.string.settings_time_format_label),
                options = listOf(
                    stringResource(R.string.settings_time_format_absolute),
                    stringResource(R.string.settings_time_format_relative)
                ),
                selectedIndex = selectedTimeFormatIndex,
                onSelectionChange = { index ->
                    selectedTimeFormatIndex = index
                    coroutineScope.launch {
                        preferencesStore.setTimeFormat(TimeFormat.entries[index])
                    }
                }
            )
        }

        // RSS ソースセクション
        item {
            SettingsSectionHeader(
                if (sources.isEmpty() || isLoading) {
                    stringResource(R.string.settings_section_rss_sources)
                } else {
                    stringResource(R.string.settings_rss_sources_count, sources.size)
                }
            )
        }

        // RSS sources list or loading/empty state
        if (sources.isEmpty()) {
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.settings_rss_sources_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(sources) { source ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DSSpacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .run {
                                if (isAdmin) {
                                    this.clickable {
                                        editSourceOldUrl = source.url
                                        editSourceName = source.name
                                        editSourceUrl = source.url
                                        showEditSourceDialog = true
                                    }
                                } else {
                                    this
                                }
                            }
                    ) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = source.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isAdmin) {
                            Text(
                                text = stringResource(R.string.settings_rss_sources_edit_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = DSSpacing.xs)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                viewModel.removeSource(source.url)
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = { showAddSourceDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_rss_sources_add_button))
            }
        }

        // Featured Sites セクション
        if (featuredSites.isNotEmpty() || featuredSitesLoadFailed) {
            item {
                SettingsSectionHeader(stringResource(R.string.settings_section_featured_sites))
            }
            items(featuredSites) { site ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DSSpacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = site.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (site.description != null) {
                            Text(
                                text = site.description!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                viewModel.addSource(site.name, site.url)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.settings_featured_sites_subscribe_button))
                    }
                }
            }
            if (featuredSitesLoadFailed) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DSSpacing.m)
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(DSSpacing.m),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_featured_sites_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        TextButton(onClick = {
                            coroutineScope.launch {
                                viewModel.loadFeaturedSites()
                            }
                        }) {
                            Text(stringResource(R.string.settings_retry_button))
                        }
                    }
                }
            }
        }

        // Generation Quota セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_generation_quota))
            if (generationQuota != null) {
                Text(
                    text = if (generationQuota!!.limit == 0) {
                        stringResource(R.string.settings_generation_quota_unlimited)
                    } else {
                        stringResource(
                            R.string.settings_generation_quota_limited,
                            generationQuota!!.remaining ?: 0,
                            generationQuota!!.limit
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            if (generationQuotaLoadFailed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DSSpacing.m)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(DSSpacing.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_generation_quota_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            viewModel.loadGenerationQuota()
                        }
                    }) {
                        Text(stringResource(R.string.settings_retry_button))
                    }
                }
            }
        }

        // Listening Streak セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_listening_streak))
            listeningStreak?.let { streak ->
                if (streak.lastListenedDay != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = DSSpacing.s),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_listening_streak_days,
                                streak.currentStreakDays
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (streak.todayListened) {
                            Text(
                                text = stringResource(R.string.settings_listening_streak_today_listened),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.settings_listening_streak_last_day,
                            streak.lastListenedDay ?: ""
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_listening_streak_no_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (listeningStreakLoadFailed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DSSpacing.m)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(DSSpacing.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_listening_streak_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            viewModel.loadListeningStreak()
                        }
                    }) {
                        Text(stringResource(R.string.settings_retry_button))
                    }
                }
            }
        }

        // Difficulty セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_difficulty))
            SettingsDifficultyDropdown(
                selectedIndex = selectedDifficultyIndex,
                onSelectionChange = { index ->
                    selectedDifficultyIndex = index
                    coroutineScope.launch {
                        val newValue = Difficulty.entries[index].code
                        val ok = viewModel.syncDefaultDifficulty(newValue)
                        if (!ok) {
                            // Revert on failure
                            selectedDifficultyIndex = Difficulty.entries.indexOfFirst { it.code == defaultDifficulty }
                                .takeIf { it >= 0 } ?: 0
                        }
                    }
                }
            )
        }

        // Playback Speed セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_playback_speed))
            SettingsPlaybackSpeedDropdown(
                selectedIndex = selectedSpeedIndex,
                onSelectionChange = { index ->
                    selectedSpeedIndex = index
                    coroutineScope.launch {
                        val newValue = PLAYBACK_SPEEDS[index]
                        val ok = viewModel.syncDefaultPlaybackSpeed(newValue)
                        if (!ok) {
                            // Revert on failure
                            selectedSpeedIndex = PLAYBACK_SPEEDS.indexOfFirst { it == defaultPlaybackSpeed }
                                .takeIf { it >= 0 } ?: PLAYBACK_SPEEDS.indexOfFirst { it == 1.0 }
                        }
                    }
                }
            )
        }

        // Account セクション（表示名・パスワード変更）
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_account))
            AccountNameSection(
                displayName = displayName,
                isLoading = accountIsLoading,
                message = profileMessage,
                messageIsError = profileMessageIsError,
                onDisplayNameChange = { accountViewModel.onDisplayNameChange(it) },
                onSaveProfile = {
                    coroutineScope.launch {
                        accountViewModel.saveProfile()
                    }
                }
            )
            PasswordChangeSection(
                currentPassword = currentPassword,
                newPassword = newPassword,
                isLoading = accountIsLoading,
                message = passwordMessage,
                messageIsError = passwordMessageIsError,
                onCurrentPasswordChange = { accountViewModel.onCurrentPasswordChange(it) },
                onNewPasswordChange = { accountViewModel.onNewPasswordChange(it) },
                onChangePassword = {
                    coroutineScope.launch {
                        accountViewModel.changePassword()
                    }
                }
            )
        }

        // Sessions（デバイス管理）セクション
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_sessions))
            Button(
                onClick = { showRevokeOthersConfirmDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasOtherSessions
            ) {
                Text(stringResource(R.string.settings_sessions_revoke_others_button))
            }
        }

        // Sessions一覧
        if (sessions.isEmpty()) {
            if (sessionsIsLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.settings_sessions_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(sessions) { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DSSpacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(DSSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = session.deviceLabel ?: stringResource(R.string.settings_sessions_unknown_device),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (session.current) {
                                Text(
                                    text = stringResource(R.string.settings_sessions_current_device),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = formatSessionDateLine(
                                createdAt = session.createdAt,
                                lastUsedAt = session.lastUsedAt,
                                createdAtLabel = stringResource(R.string.settings_sessions_created_at_label),
                                lastUsedAtLabel = stringResource(R.string.settings_sessions_last_used_at_label),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!session.current) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    sessionsViewModel.revokeSession(session.id)
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.settings_sessions_revoke_button_description),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Sessions エラー＆フィードバック
        if (sessionsErrorMessage != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DSSpacing.m)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(DSSpacing.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sessionsErrorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            sessionsViewModel.loadSessions()
                        }
                    }) {
                        Text(stringResource(R.string.settings_retry_button))
                    }
                }
            }
        }

        if (revokedOthersCount != null) {
            item {
                Text(
                    text = stringResource(
                        R.string.settings_sessions_revoked_others_count,
                        revokedOthersCount ?: 0
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // パスキーセクション（issue #140 P17）
        item {
            SettingsSectionHeader(stringResource(R.string.settings_section_passkey))
        }

        if (passkeyCredentials.isEmpty()) {
            if (passkeyCredentialsIsLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.settings_passkey_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(passkeyCredentials, key = { it.credentialId }) { credential ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DSSpacing.s),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = credential.name ?: stringResource(R.string.settings_passkey_unnamed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${stringResource(R.string.settings_passkey_created_at_label)}${credential.createdAt.take(10)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                passkeyCredentialsViewModel.deleteCredential(credential.credentialId)
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.settings_passkey_delete_button_description),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (passkeyCredentialsErrorMessage != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DSSpacing.m)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(DSSpacing.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = passkeyCredentialsErrorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = {
                        coroutineScope.launch {
                            passkeyCredentialsViewModel.loadCredentials()
                        }
                    }) {
                        Text(stringResource(R.string.settings_retry_button))
                    }
                }
            }
        }

        if (passkeyRegistrationErrorMessage != null) {
            item {
                Text(
                    text = passkeyRegistrationErrorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        item {
            Button(
                onClick = {
                    coroutineScope.launch {
                        passkeyRegistrationViewModel.register()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !passkeyIsRegistering
            ) {
                if (passkeyIsRegistering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_passkey_add_button))
                }
            }
        }

        // Logout セクション
        item {
            Button(
                onClick = {
                    coroutineScope.launch {
                        authViewModel.logout()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_logout_button))
            }
            Spacer(modifier = Modifier.height(DSSpacing.xl))
        }
    }

    // Error alert
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { /* Dismiss */ },
            title = { Text(stringResource(R.string.settings_error_dialog_title)) },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { /* Dismiss */ }) {
                    Text("OK")
                }
            }
        )
    }

    // Add source dialog
    if (showAddSourceDialog) {
        AlertDialog(
            onDismissRequest = { showAddSourceDialog = false },
            title = { Text(stringResource(R.string.settings_rss_sources_add_dialog_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
                ) {
                    TextField(
                        value = newSourceName,
                        onValueChange = { newSourceName = it },
                        label = { Text(stringResource(R.string.settings_rss_sources_add_dialog_name_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = newSourceUrl,
                        onValueChange = { newSourceUrl = it },
                        label = { Text(stringResource(R.string.settings_rss_sources_add_dialog_url_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.addSource(newSourceName, newSourceUrl)
                            newSourceName = ""
                            newSourceUrl = ""
                            showAddSourceDialog = false
                        }
                    },
                    enabled = newSourceName.isNotEmpty() && newSourceUrl.isNotEmpty()
                ) {
                    Text(stringResource(R.string.settings_rss_sources_add_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSourceDialog = false }) {
                    Text(stringResource(R.string.settings_rss_sources_add_dialog_cancel))
                }
            }
        )
    }

    // Edit source dialog
    if (showEditSourceDialog) {
        AlertDialog(
            onDismissRequest = { showEditSourceDialog = false },
            title = { Text(stringResource(R.string.settings_rss_sources_edit_dialog_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DSSpacing.m)
                ) {
                    TextField(
                        value = editSourceName,
                        onValueChange = { editSourceName = it },
                        label = { Text(stringResource(R.string.settings_rss_sources_add_dialog_name_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = editSourceUrl,
                        onValueChange = { editSourceUrl = it },
                        label = { Text(stringResource(R.string.settings_rss_sources_add_dialog_url_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.updateSource(editSourceOldUrl, editSourceName, editSourceUrl)
                            editSourceOldUrl = ""
                            editSourceName = ""
                            editSourceUrl = ""
                            showEditSourceDialog = false
                        }
                    },
                    enabled = editSourceName.isNotEmpty() && editSourceUrl.isNotEmpty()
                ) {
                    Text(stringResource(R.string.settings_rss_sources_edit_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditSourceDialog = false }) {
                    Text(stringResource(R.string.settings_rss_sources_add_dialog_cancel))
                }
            }
        )
    }

    // Revoke other sessions confirmation dialog
    if (showRevokeOthersConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeOthersConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_sessions_confirm_revoke_others_title)) },
            text = { Text(stringResource(R.string.settings_sessions_confirm_revoke_others_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            sessionsViewModel.revokeOtherSessions()
                            showRevokeOthersConfirmDialog = false
                        }
                    },
                    enabled = !sessionsIsLoading
                ) {
                    Text(stringResource(R.string.settings_sessions_confirm_revoke_others_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeOthersConfirmDialog = false }) {
                    Text(stringResource(R.string.settings_sessions_confirm_revoke_others_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = DSSpacing.m, bottom = DSSpacing.s)
    )
}

@Composable
private fun SettingsDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (selectedIndex >= 0 && selectedIndex < options.size) {
                    options[selectedIndex]
                } else {
                    label
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelectionChange(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsDifficultyDropdown(
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
) {
    val difficulties = Difficulty.entries
    val difficultyLabels = listOf(
        stringResource(R.string.settings_difficulty_toeic_600),
        stringResource(R.string.settings_difficulty_toeic_900),
        stringResource(R.string.settings_difficulty_ielts_55),
        stringResource(R.string.settings_difficulty_ielts_7),
        stringResource(R.string.settings_difficulty_eiken_2),
        stringResource(R.string.settings_difficulty_eiken_p1),
    )

    SettingsDropdown(
        label = stringResource(R.string.settings_difficulty_label),
        options = difficultyLabels,
        selectedIndex = selectedIndex,
        onSelectionChange = onSelectionChange
    )
}

@Composable
private fun SettingsPlaybackSpeedDropdown(
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
) {
    val speedLabels = PLAYBACK_SPEEDS.map { speed ->
        stringResource(R.string.settings_playback_speed_format, speed)
    }

    SettingsDropdown(
        label = stringResource(R.string.settings_playback_speed_label),
        options = speedLabels,
        selectedIndex = selectedIndex,
        onSelectionChange = onSelectionChange
    )
}

/**
 * 表示名更新セクション（AccountSettingsView.swift:54-59 のミラー）。
 */
@Composable
private fun AccountNameSection(
    displayName: String,
    isLoading: Boolean,
    message: String?,
    messageIsError: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DSSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.settings_account_display_name_label)) },
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            )
            Button(
                onClick = onSaveProfile,
                enabled = !isLoading && displayName.isNotEmpty()
            ) {
                Text(stringResource(R.string.settings_account_save_profile_button))
            }
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * パスワード変更セクション（AccountSettingsView.swift:61-68 のミラー）。
 * パスワード要件の案内文（12文字以上・3文字種以上）も含む。
 */
@Composable
private fun PasswordChangeSection(
    currentPassword: String,
    newPassword: String,
    isLoading: Boolean,
    message: String?,
    messageIsError: Boolean,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
    ) {
        TextField(
            value = currentPassword,
            onValueChange = onCurrentPasswordChange,
            label = { Text(stringResource(R.string.settings_account_current_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        TextField(
            value = newPassword,
            onValueChange = onNewPasswordChange,
            label = { Text(stringResource(R.string.settings_account_new_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        Text(
            text = stringResource(R.string.settings_account_password_requirement),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onChangePassword,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && currentPassword.isNotEmpty() && newPassword.isNotEmpty()
        ) {
            Text(stringResource(R.string.settings_account_change_password_button))
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * セッション行の日付表記（ログイン日 + 最終利用日があれば併記・iOS :294-301 相当）。
 * ラベルは Composable 側から stringResource 経由で渡す（i18n 対応）。
 */
private fun formatSessionDateLine(
    createdAt: String,
    lastUsedAt: String?,
    createdAtLabel: String,
    lastUsedAtLabel: String,
): String {
    var line = "$createdAtLabel${createdAt.take(10)}"
    if (lastUsedAt != null) {
        line += "　$lastUsedAtLabel${lastUsedAt.take(10)}"
    }
    return line
}

// Settings screen specific playback speeds (5-step: iOS SettingsView.swift:44)
private val PLAYBACK_SPEEDS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)

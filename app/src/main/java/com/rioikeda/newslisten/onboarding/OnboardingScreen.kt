package com.rioikeda.newslisten.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.model.FeaturedSite
import kotlinx.coroutines.launch

/**
 * 初回オンボーディングの「おすすめサイト追加」ステップ。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Onboarding/OnboardingSourcesView.swift のミラー。
 * ログイン直後、[OnboardingViewModel.onboardingCompleted] が `false` の間だけ
 * 呼び出し元（MainActivity）から表示される。「完了」「スキップ」のどちらでも
 * [OnboardingViewModel.finish] を呼びオンボーディングを終える（iOS toolbar 準拠）。
 *
 * @param viewModel おすすめサイト取得・購読・完了処理を担う ViewModel。
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
) {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        coroutineScope.launch { viewModel.load() }
    }

    val featuredSites by viewModel.featuredSites.collectAsStateWithLifecycle()
    val addedIds by viewModel.addedIds.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val subscribeError by viewModel.subscribeError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(DSSpacing.l),
    ) {
        Text(
            text = stringResource(R.string.onboarding_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.onboarding_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = DSSpacing.s, bottom = DSSpacing.m),
        )

        if (isLoading && featuredSites.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.padding(DSSpacing.m))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(featuredSites) { site ->
                FeaturedSiteRow(
                    site = site,
                    subscribed = addedIds.contains(site.id),
                    onSubscribe = { coroutineScope.launch { viewModel.subscribe(site) } },
                )
            }
        }

        if (loadError != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = DSSpacing.s),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = loadError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { coroutineScope.launch { viewModel.load() } }) {
                    Text(stringResource(R.string.onboarding_retry_button))
                }
            }
        }

        if (subscribeError != null) {
            Text(
                text = subscribeError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = DSSpacing.s),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DSSpacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = { coroutineScope.launch { viewModel.finish() } }) {
                Text(stringResource(R.string.onboarding_skip_button))
            }
            Button(onClick = { coroutineScope.launch { viewModel.finish() } }) {
                Text(stringResource(R.string.onboarding_finish_button))
            }
        }
    }
}

/** おすすめサイト1行。名前/説明 + 購読ボタン（購読済みは無効化してラベル切り替え）。 */
@Composable
private fun FeaturedSiteRow(
    site: FeaturedSite,
    subscribed: Boolean,
    onSubscribe: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = DSSpacing.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = site.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (site.description != null) {
                Text(
                    text = site.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onSubscribe, enabled = !subscribed) {
            Text(
                stringResource(
                    if (subscribed) R.string.onboarding_subscribed_label else R.string.onboarding_subscribe_button,
                ),
            )
        }
    }
}

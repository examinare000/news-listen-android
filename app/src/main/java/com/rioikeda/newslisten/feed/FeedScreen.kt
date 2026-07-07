package com.rioikeda.newslisten.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rioikeda.newslisten.R
import com.rioikeda.newslisten.core.Difficulty
import com.rioikeda.newslisten.designsystem.DSSpacing
import com.rioikeda.newslisten.designsystem.RelevanceBar
import com.rioikeda.newslisten.model.ArticleResponse
import com.rioikeda.newslisten.preferences.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Feed タブのメイン画面。記事一覧・ダブルタップでの遷移・Star/Dismiss を担う。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedView.swift のミラー。
 * iOS の SwipeableArticleCard 相当の操作を Compose で再現する（段階実装）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val articles by viewModel.articles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val pendingAction by viewModel.pendingAction.collectAsState()
    val bulkActionResult by viewModel.bulkActionResult.collectAsState()
    val articleOpenMode by viewModel.articleOpenMode.collectAsState()
    val timeFormat by viewModel.timeFormat.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    // ツールバー色：MaterialTheme.colorScheme.background（ダークテーマ対応）
    val toolbarColor = MaterialTheme.colorScheme.background.toArgb()

    // ライフサイクル: ON_STOP で保留中操作を確定送信（取りこぼし防止）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { viewModel.commitPending() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // エラーメッセージ表示・表示後にクリア（同一文言の再表示を可能にする）
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearErrorMessage()
        }
    }

    // 一括 Star 操作の結果表示・表示後にクリア
    LaunchedEffect(bulkActionResult) {
        bulkActionResult?.let { result ->
            val message = if (result.failureCount > 0) {
                "${result.successCount}件スター、${result.failureCount}件失敗しました"
            } else {
                "${result.successCount}件スター完了しました"
            }
            snackbarHostState.showSnackbar(message)
            viewModel.clearBulkActionResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_feed_title)) },
                actions = {
                    IconButton(onClick = {
                        isSelectionMode = !isSelectionMode
                        if (!isSelectionMode) {
                            selectedIds = emptySet()
                        }
                    }) {
                        Icon(
                            if (isSelectionMode) Icons.Filled.Check else Icons.Outlined.CheckCircle,
                            contentDescription = stringResource(
                                if (isSelectionMode) R.string.feed_toggle_selection_on
                                else R.string.feed_toggle_selection_off
                            )
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { viewModel.refresh() } },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when {
                isLoading && articles.isEmpty() -> {
                    // ローディング状態
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.loading_articles))
                    }
                }
                articles.isEmpty() -> {
                    // 記事なし状態
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.feed_empty))
                    }
                }
                else -> {
                    // 記事一覧表示
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = if (pendingAction != null) DSSpacing.xl + DSSpacing.l else DSSpacing.xl)
                        ) {
                            items(
                                items = articles,
                                key = { it.id }
                            ) { article ->
                                if (isSelectionMode) {
                                    // 選択モード: チェックボックス + 記事テキスト
                                    SelectionModeRow(
                                        article = article,
                                        isSelected = article.id in selectedIds,
                                        timeFormat = timeFormat,
                                        onToggle = {
                                            selectedIds = if (article.id in selectedIds) {
                                                selectedIds - article.id
                                            } else {
                                                selectedIds + article.id
                                            }
                                        }
                                    )
                                } else {
                                    // 通常モード: カード
                                    ArticleCardRow(
                                        article = article,
                                        isExpanded = expandedId == article.id,
                                        timeFormat = timeFormat,
                                        onTap = {
                                            expandedId = if (expandedId == article.id) null else article.id
                                        },
                                        onDoubleTap = {
                                            ArticleOpener.openArticle(context, article.url, toolbarColor, articleOpenMode)
                                        },
                                        onStar = {
                                            // 展開状態をリセット（iOS stage 内の expandedId=nil 相当）
                                            if (expandedId == article.id) {
                                                expandedId = null
                                            }
                                            scope.launch { viewModel.star(article) }
                                        },
                                        onStarWithDifficulty = { difficulty ->
                                            if (expandedId == article.id) {
                                                expandedId = null
                                            }
                                            scope.launch { viewModel.star(article, difficulty) }
                                        },
                                        onDismiss = {
                                            if (expandedId == article.id) {
                                                expandedId = null
                                            }
                                            scope.launch { viewModel.dismiss(article) }
                                        }
                                    )
                                }

                                // 末尾以外に罫線表示
                                if (article.id != articles.last().id) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }

                        // Undo トースト（画面下部に固定）
                        if (pendingAction != null) {
                            UndoToast(
                                pending = pendingAction!!,
                                onUndo = { viewModel.undoLast() },
                                onCommit = { scope.launch { viewModel.commitPending() } },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = DSSpacing.l)
                            )
                        }

                        // 一括Star ボタン
                        if (isSelectionMode && selectedIds.isNotEmpty()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        viewModel.bulkStar(selectedIds)
                                        // 一括 Star 完了後に選択モードと選択 ID をリセット（iOS FeedViewModel.swift:220-222）
                                        isSelectionMode = false
                                        selectedIds = emptySet()
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(DSSpacing.l)
                                    .fillMaxWidth(0.9f)
                            ) {
                                Text(
                                    stringResource(
                                        R.string.feed_bulk_star,
                                        selectedIds.size
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

    // 初期読み込み
    LaunchedEffect(Unit) {
        viewModel.loadFeed()
    }
}

/**
 * Undo トースト。4 秒で自動確定。
 */
@Composable
private fun UndoToast(
    pending: PendingArticleAction,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(pending) {
        delay(4000L)
        onCommit()
    }

    Box(
        modifier = modifier
            .fillMaxWidth(0.9f)
            .background(
                MaterialTheme.colorScheme.onBackground,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(horizontal = DSSpacing.l, vertical = DSSpacing.m)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = pending.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.background
            )
            OutlinedButton(
                onClick = onUndo,
                modifier = Modifier.padding(start = DSSpacing.m)
            ) {
                Text(stringResource(R.string.feed_undo))
            }
        }
    }
}

/**
 * 選択モード行（チェックボックス + 記事情報）。
 */
@Composable
private fun SelectionModeRow(
    article: ArticleResponse,
    isSelected: Boolean,
    timeFormat: TimeFormat,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DSSpacing.l, vertical = DSSpacing.m)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggle() })
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DSSpacing.m)
    ) {
        Icon(
            if (isSelected) Icons.Filled.Check else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.outline
        )
        ArticleCardContent(
            article = article,
            isExpanded = false,
            timeFormat = timeFormat,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 通常モード行（スワイプ対応カード）。右スワイプ = Star、左スワイプ = Dismiss。
 * 長押しで難易度選択メニュー表示（iOS SwipeableArticleCard.swift:129-137）。
 */
@Composable
private fun ArticleCardRow(
    article: ArticleResponse,
    isExpanded: Boolean,
    timeFormat: TimeFormat,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onStar: () -> Unit,
    onStarWithDifficulty: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMenuOpen by remember { mutableStateOf(false) }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // 右スワイプ = Star（iOS :160）
                    onStar()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    // 左スワイプ = Dismiss（iOS :162）
                    onDismiss()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = {
            // スワイプ背景（アフォーダンス）：方向別背景+ラベル（iOS SwipeableArticleCard.swift:59-74）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when (swipeState.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.tertiary // 右=Star（金）
                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error    // 左=Dismiss（赤）
                            SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surface
                        }
                    )
                    .padding(horizontal = DSSpacing.l),
                contentAlignment = if (swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                }
            ) {
                Text(
                    text = when (swipeState.dismissDirection) {
                        SwipeToDismissBoxValue.StartToEnd -> stringResource(R.string.feed_star)
                        SwipeToDismissBoxValue.EndToStart -> stringResource(R.string.feed_dismiss)
                        SwipeToDismissBoxValue.Settled -> ""
                    },
                    color = when (swipeState.dismissDirection) {
                        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onTertiary
                        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onError
                        SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = DSSpacing.m, horizontal = DSSpacing.l)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = { onDoubleTap() },
                            onLongPress = { isMenuOpen = true }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
                ) {
                    // アイブロウ・タイトル・RelevanceBar
                    ArticleCardContent(
                        article = article,
                        isExpanded = isExpanded,
                        timeFormat = timeFormat
                    )

                    // 展開時: Star / Dismiss ボタン
                    if (isExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = DSSpacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(DSSpacing.m)
                        ) {
                            OutlinedButton(
                                onClick = onStar,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.feed_star))
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.feed_dismiss))
                            }
                        }
                    }
                }

                // 長押しメニュー：難易度選択（iOS SwipeableArticleCard.swift:129-137）
                Box {
                    DropdownMenu(
                        expanded = isMenuOpen,
                        onDismissRequest = { isMenuOpen = false }
                    ) {
                        Difficulty.entries.forEach { difficulty ->
                            DropdownMenuItem(
                                text = { Text(difficulty.label) },
                                onClick = {
                                    onStarWithDifficulty(difficulty.code)
                                    isMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

/**
 * 記事カード内容（アイブロウ・タイトル・RelevanceBar）。
 */
@Composable
private fun ArticleCardContent(
    article: ArticleResponse,
    isExpanded: Boolean,
    timeFormat: TimeFormat,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(DSSpacing.s)
    ) {
        // アイブロウ: ソース · 公開日
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DSSpacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = article.source,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ArticleDateFormatter.format(article.publishedAt, timeFormat, Instant.now()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        // タイトル（3行 ↔ 展開で無制限）
        Text(
            text = article.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis
        )

        // 関連スコアバー
        RelevanceBar(score = article.score, modifier = Modifier.padding(top = DSSpacing.xs))
    }
}

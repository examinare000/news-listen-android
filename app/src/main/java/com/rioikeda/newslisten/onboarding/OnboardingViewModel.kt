package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.onboarding.app.OnboardingCommands
import com.rioikeda.newslisten.settings.app.FeaturedSiteGroup
import com.rioikeda.newslisten.settings.app.FeaturedSitesLoad
import com.rioikeda.newslisten.settings.app.SourcesCommands
import com.rioikeda.newslisten.settings.app.SourcesOutcome
import com.rioikeda.newslisten.settings.app.SourcesQueries
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 初回オンボーディング（おすすめサイト追加ステップ）の状態とロジックを担う ViewModel（フェーズ13・issue #140 P13）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Onboarding/OnboardingSourcesViewModel.swift
 * （load/subscribe）、ios/NewsListenApp/NewsListenApp/AppState.swift:180-200
 * （refreshOnboardingStatus/completeOnboarding）のミラー。
 *
 * androidx.lifecycle.ViewModel は継承しない（ADR-066・他 ViewModel と同じ設計判断）。
 * Dispatcher をコンストラクタ注入し、Compose 側は viewModelScope.launch { } から
 * 各 suspend 関数を呼び出す想定。
 */
class OnboardingViewModel(
    private val sourcesCommands: SourcesCommands,
    private val sourcesQueries: SourcesQueries,
    private val onboardingCommands: OnboardingCommands,
    private val dispatcher: CoroutineDispatcher,
) {
    // WHY ViewModel が一覧を持つ: 取得に失敗したとき Onboarding は前の一覧を保つ（Settings は空にする。android-dec-115）。
    private val _featuredSites = MutableStateFlow<List<FeaturedSiteGroup>>(emptyList())
    val featuredSites: StateFlow<List<FeaturedSiteGroup>> = _featuredSites.asStateFlow()

    /** 既に購読済みのサイト id（ボタン表示の切り替えに使う。iOS addedIDs 相当）。 */
    private val _addedIds = MutableStateFlow<Set<String>>(emptySet())
    val addedIds: StateFlow<Set<String>> = _addedIds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _subscribeError = MutableStateFlow<String?>(null)
    val subscribeError: StateFlow<String?> = _subscribeError.asStateFlow()

    /**
     * 初回オンボーディングの完了状態（iOS AppState.onboardingCompleted: Bool? と同じ三値）。
     * `null`=未取得（判定保留）。`false` のときのみ [OnboardingScreen] を提示する。
     */
    val onboardingCompleted: StateFlow<Boolean?> = onboardingCommands.onboardingCompleted

    /**
     * サーバから初回オンボーディング状態を取得し [onboardingCompleted] を更新する。
     * 取得失敗時は `true` 扱い（行き止まりを防ぐ。iOS AppState.swift:180-191 準拠）で、入口が持つ。
     */
    suspend fun refreshOnboardingStatus(): Unit = withContext(dispatcher) {
        onboardingCommands.refreshOnboardingStatus()
    }

    /** おすすめサイト一覧を取得する。失敗時は [loadError] に反映し、前の一覧を保つ。 */
    suspend fun load(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _loadError.value = null
        when (val load = sourcesQueries.loadFeaturedSites()) {
            is FeaturedSitesLoad.Loaded -> _featuredSites.value = load.groups
            FeaturedSitesLoad.Failed -> _loadError.value = LOAD_ERROR_MESSAGE
        }
        _isLoading.value = false
    }

    /**
     * おすすめサイトを即購読する（専用 API は無く、入口が RSS ソースの追加を再利用する）。
     * 成功、および既に購読済みの 409 は購読済み扱いにする（iOS subscribe(_:) 準拠）。
     */
    suspend fun subscribe(site: FeaturedSiteEntry): Unit = withContext(dispatcher) {
        _subscribeError.value = null
        when (sourcesCommands.subscribeFeatured(site)) {
            SourcesOutcome.Done -> _addedIds.value = _addedIds.value + site.id
            else -> _subscribeError.value = SUBSCRIBE_ERROR_MESSAGE
        }
    }

    /** 初回オンボーディング完了を入口へ中継する。送信に失敗しても利用者は先へ進める。 */
    suspend fun finish(): Unit = withContext(dispatcher) {
        onboardingCommands.finishOnboarding()
    }

    private companion object {
        const val LOAD_ERROR_MESSAGE = "おすすめサイトの取得に失敗しました"
        const val SUBSCRIBE_ERROR_MESSAGE = "購読に失敗しました。もう一度お試しください。"
    }
}

package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
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
    private val apiClient: ApiClient,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _featuredSites = MutableStateFlow<List<FeaturedSite>>(emptyList())
    val featuredSites: StateFlow<List<FeaturedSite>> = _featuredSites.asStateFlow()

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
    private val _onboardingCompleted = MutableStateFlow<Boolean?>(null)
    val onboardingCompleted: StateFlow<Boolean?> = _onboardingCompleted.asStateFlow()

    /**
     * サーバから初回オンボーディング状態を取得し [onboardingCompleted] を更新する。
     *
     * 取得失敗時は `true` 扱いとし、追加ステップを挟まずフィードへ進ませる
     * （iOS AppState.swift:180-191 準拠。行き止まりを防ぐベストエフォート）。
     */
    suspend fun refreshOnboardingStatus(): Unit = withContext(dispatcher) {
        try {
            _onboardingCompleted.value = apiClient.fetchOnboardingStatus().onboardingCompleted
        } catch (e: ApiException) {
            _onboardingCompleted.value = true
        }
    }

    /** おすすめサイト一覧を取得する。失敗時は [loadError] に反映する。 */
    suspend fun load(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _loadError.value = null
        try {
            _featuredSites.value = apiClient.fetchFeaturedSites().sites
        } catch (e: ApiException) {
            _loadError.value = LOAD_ERROR_MESSAGE
        }
        _isLoading.value = false
    }

    /**
     * おすすめサイトを即購読する（既存 [ApiClient.createSource] を再利用。専用 API は無い）。
     * 成功、および既に購読済みの 409 は購読済み扱いにする（iOS subscribe(_:) 準拠）。
     */
    suspend fun subscribe(site: FeaturedSite): Unit = withContext(dispatcher) {
        _subscribeError.value = null
        try {
            apiClient.createSource(site.name, site.url)
            _addedIds.value = _addedIds.value + site.id
        } catch (e: ApiException.HttpError) {
            if (e.code == 409) {
                _addedIds.value = _addedIds.value + site.id
            } else {
                _subscribeError.value = SUBSCRIBE_ERROR_MESSAGE
            }
        } catch (e: ApiException) {
            _subscribeError.value = SUBSCRIBE_ERROR_MESSAGE
        }
    }

    /**
     * 初回オンボーディング完了をサーバに記録し、ローカル状態も完了にする。
     *
     * 保存に失敗しても UI 上は完了として扱い、追加ステップを閉じる（iOS AppState.completeOnboarding
     * の defer 相当。次回起動時に再取得され、サーバー側が未完了のままなら再度表示される）。
     */
    suspend fun finish(): Unit = withContext(dispatcher) {
        try {
            apiClient.completeOnboarding()
        } catch (e: ApiException) {
            // ベストエフォート: サーバー同期に失敗してもローカルでは完了扱いにする。
        }
        _onboardingCompleted.value = true
    }

    private companion object {
        const val LOAD_ERROR_MESSAGE = "おすすめサイトの取得に失敗しました"
        const val SUBSCRIBE_ERROR_MESSAGE = "購読に失敗しました。もう一度お試しください。"
    }
}

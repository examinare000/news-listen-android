package com.rioikeda.newslisten.settings

import com.rioikeda.newslisten.model.FeaturedSite
import com.rioikeda.newslisten.model.GenerationQuotaResponse
import com.rioikeda.newslisten.model.ListeningStreakResponse
import com.rioikeda.newslisten.model.RssSource
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import com.rioikeda.newslisten.preferences.PreferencesStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Settings タブの状態とロジックを担う ViewModel（フェーズ10 P10 Task2）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Settings/SettingsViewModel.swift のミラー。
 * androidx.lifecycle.ViewModel は継承しない（[com.rioikeda.newslisten.auth.AuthViewModel] と
 * 同じ設計判断）。Dispatcher をコンストラクタ注入し、Compose 側は viewModelScope.launch { } から
 * 各 suspend 関数を呼び出す想定。
 */
class SettingsViewModel(
    private val apiClient: ApiClient,
    /**
     * 難易度・再生速度の値の正本（AuthViewModel と同じ設計。詳細は [PreferencesStore] のコメント
     * 参照）。SettingsViewModel は同期の成否のみを扱い、値そのものはここへ書き戻すのみで
     * 独自コピーを持たない。
     */
    private val preferencesStore: PreferencesStore,
    private val dispatcher: CoroutineDispatcher,
    /**
     * 現在ログイン中ユーザーが admin かどうかを都度問い合わせる関数（issue #66 / ADR-047）。
     *
     * WHY 関数注入（AuthState を直接受け取らない）: settings 層が auth 層の型（AuthState）に
     * 依存すると層の依存方向が増える。呼び出し元（AppContainer）が
     * `{ authViewModel.authState.value を見て判定 }` を注入する形にし、settings 層は
     * 「admin かどうかを問い合わせられる」という事実のみを知る設計にした
     * （AuthViewModel の onLogoutCleanup/onAuthenticated と同じパターン）。
     * role は認証確立後に非同期で確定するため、コンストラクタ時点の固定値ではなく
     * 呼び出し時点で都度評価する関数にしている。
     */
    private val isAdminProvider: () -> Boolean = { false },
) {
    private val _sources = MutableStateFlow<List<RssSource>>(emptyList())
    val sources: StateFlow<List<RssSource>> = _sources.asStateFlow()

    private val _featuredSites = MutableStateFlow<List<FeaturedSite>>(emptyList())
    val featuredSites: StateFlow<List<FeaturedSite>> = _featuredSites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 直近の [loadFeaturedSites] が失敗したか（issue #164 同型）。 */
    private val _featuredSitesLoadFailed = MutableStateFlow(false)
    val featuredSitesLoadFailed: StateFlow<Boolean> = _featuredSitesLoadFailed.asStateFlow()

    /** Podcast 生成の本日残回数（issue #164 / ADR-061）。未取得・取得失敗時は `null`。 */
    private val _generationQuota = MutableStateFlow<GenerationQuotaResponse?>(null)
    val generationQuota: StateFlow<GenerationQuotaResponse?> = _generationQuota.asStateFlow()

    private val _generationQuotaLoadFailed = MutableStateFlow(false)
    val generationQuotaLoadFailed: StateFlow<Boolean> = _generationQuotaLoadFailed.asStateFlow()

    /** 聴取ストリーク（issue #165）。未取得・取得失敗時は `null`。 */
    private val _listeningStreak = MutableStateFlow<ListeningStreakResponse?>(null)
    val listeningStreak: StateFlow<ListeningStreakResponse?> = _listeningStreak.asStateFlow()

    private val _listeningStreakLoadFailed = MutableStateFlow(false)
    val listeningStreakLoadFailed: StateFlow<Boolean> = _listeningStreakLoadFailed.asStateFlow()

    /** RSS ソース一覧を取得して [sources] を更新する。失敗時は [errorMessage] に反映する。 */
    suspend fun loadSources(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            _sources.value = apiClient.fetchSources().sources
        } catch (e: ApiException) {
            _errorMessage.value = SOURCES_ERROR_MESSAGE
        }
        _isLoading.value = false
    }

    /**
     * おすすめサイト一覧を取得して [featuredSites] を更新する。
     *
     * 失敗してもおすすめ欄が出ないだけで RSS ソース管理は妨げないため、[errorMessage]（アラート）
     * には反映しない。代わりに [featuredSitesLoadFailed] を立て、インライン表示 + 再試行に使う
     * （issue #164・完全サイレントの解消）。
     */
    suspend fun loadFeaturedSites(): Unit = withContext(dispatcher) {
        try {
            _featuredSites.value = apiClient.fetchFeaturedSites().sites
            _featuredSitesLoadFailed.value = false
        } catch (e: ApiException) {
            _featuredSites.value = emptyList()
            _featuredSitesLoadFailed.value = true
        }
    }

    /**
     * RSS ソースを追加し、サーバが返す最新一覧で [sources] を更新する。
     * おすすめサイトの「購読」もこの関数を再利用する（専用 API は無い）。
     */
    suspend fun addSource(name: String, url: String): Unit = withContext(dispatcher) {
        try {
            _sources.value = apiClient.createSource(name, url).sources
            _errorMessage.value = null
        } catch (e: ApiException) {
            _errorMessage.value = SOURCES_ERROR_MESSAGE
        }
    }

    /**
     * 既存 RSS ソースの名称・URL を編集し、サーバが返す最新一覧で [sources] を更新する（issue #66）。
     *
     * 編集は admin 限定（ADR-047）。iOS は View 側（SettingsView.swift:155）でのみ導線を隠すが、
     * Android はそれに加え ViewModel 側でも防御的にガードする（呼び出し元の実装漏れで
     * 非 admin から呼ばれても API を叩かない）。ガード発火時は [errorMessage] に反映し、
     * サーバー呼び出しは行わない。
     */
    suspend fun updateSource(oldUrl: String, name: String, url: String): Unit = withContext(dispatcher) {
        if (!isAdminProvider()) {
            _errorMessage.value = ADMIN_ONLY_ERROR_MESSAGE
            return@withContext
        }
        try {
            _sources.value = apiClient.updateSource(oldUrl, name, url).sources
            _errorMessage.value = null
        } catch (e: ApiException) {
            _errorMessage.value = SOURCES_ERROR_MESSAGE
        }
    }

    /** 指定 URL の RSS ソースを削除し、一覧から取り除く。失敗時は [errorMessage] に反映する。 */
    suspend fun removeSource(url: String): Unit = withContext(dispatcher) {
        try {
            apiClient.deleteSource(url)
            _sources.value = _sources.value.filterNot { it.url == url }
        } catch (e: ApiException) {
            _errorMessage.value = SOURCES_ERROR_MESSAGE
        }
    }

    /**
     * Podcast 生成の本日残回数を取得する（issue #164 / ADR-061）。
     * 404 時は graceful degradation: セクション非表示（[generationQuotaLoadFailed] は立てない）。
     */
    suspend fun loadGenerationQuota(): Unit = withContext(dispatcher) {
        try {
            _generationQuota.value = apiClient.fetchGenerationQuota()
            _generationQuotaLoadFailed.value = false
        } catch (e: ApiException.HttpError) {
            _generationQuota.value = null
            _generationQuotaLoadFailed.value = e.code != 404
        } catch (e: ApiException) {
            _generationQuota.value = null
            _generationQuotaLoadFailed.value = true
        }
    }

    /**
     * 聴取ストリーク（連続聴取日数）を取得する（issue #165）。
     * 404 時は graceful degradation: セクション非表示（[listeningStreakLoadFailed] は立てない）。
     */
    suspend fun loadListeningStreak(): Unit = withContext(dispatcher) {
        try {
            _listeningStreak.value = apiClient.fetchListeningStreak()
            _listeningStreakLoadFailed.value = false
        } catch (e: ApiException.HttpError) {
            _listeningStreak.value = null
            _listeningStreakLoadFailed.value = e.code != 404
        } catch (e: ApiException) {
            _listeningStreak.value = null
            _listeningStreakLoadFailed.value = true
        }
    }

    /**
     * デフォルト難易度をサーバーへ同期する。
     * @return 成功したら `true`、失敗したら `false`（[errorMessage] にも反映する）。
     */
    suspend fun syncDefaultDifficulty(value: String): Boolean =
        syncPreference(
            operation = { apiClient.updatePreferences(defaultDifficulty = value, defaultPlaybackSpeed = null) },
            onSuccess = { preferencesStore.setDefaultDifficulty(value) },
        )

    /**
     * デフォルト再生速度をサーバーへ同期する。
     * @return 成功したら `true`、失敗したら `false`（[errorMessage] にも反映する）。
     */
    suspend fun syncDefaultPlaybackSpeed(value: Double): Boolean =
        syncPreference(
            operation = { apiClient.updatePreferences(defaultDifficulty = null, defaultPlaybackSpeed = value) },
            onSuccess = { preferencesStore.setDefaultPlaybackSpeed(value) },
        )

    /**
     * 設定同期の成否を共通化する内部ヘルパー（iOS SettingsViewModel.swift:215-227 の
     * `syncPreference` 相当）。成功時のみ [onSuccess] を呼び、値を [preferencesStore] に
     * 書き戻す（値の所有はストアに一本化し、ここでは二重保持しない）。
     */
    private suspend fun syncPreference(
        operation: suspend () -> Unit,
        onSuccess: suspend () -> Unit,
    ): Boolean = withContext(dispatcher) {
        try {
            operation()
            onSuccess()
            _errorMessage.value = null
            true
        } catch (e: ApiException) {
            _errorMessage.value = PREFERENCES_SYNC_ERROR_MESSAGE
            false
        }
    }

    private companion object {
        const val SOURCES_ERROR_MESSAGE = "RSSソースの操作に失敗しました"
        const val ADMIN_ONLY_ERROR_MESSAGE = "この操作には管理者権限が必要です"
        const val PREFERENCES_SYNC_ERROR_MESSAGE = "設定の保存に失敗しました"
    }
}

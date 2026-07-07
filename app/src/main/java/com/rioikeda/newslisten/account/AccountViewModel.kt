package com.rioikeda.newslisten.account

import com.rioikeda.newslisten.auth.AuthState
import com.rioikeda.newslisten.auth.AuthViewModel
import com.rioikeda.newslisten.network.ApiClient
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 設定画面「アカウント」セクションの状態とロジックを担う ViewModel（フェーズ11 P11 T3）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Settings/AccountSettingsView.swift:49-82（表示名編集・
 * パスワード変更 UI）、:303-332（saveProfile/changePassword ロジック）のミラー。
 * androidx.lifecycle.ViewModel は継承しない（[AuthViewModel]/[SettingsViewModel] と同じ設計判断。
 * Dispatcher をコンストラクタ注入し Compose 側の viewModelScope.launch { } から suspend 関数を呼ぶ）。
 *
 * WHY authViewModel を直接注入する（onLogoutCleanup 等の関数注入パターンにしない）: 表示名更新の
 * 成否は AuthState そのものを書き換える必要があり、「認証層の型を知らずに済ませる」抽象化よりも
 * 「表示名更新は認証状態の一部を変更する操作である」という事実を素直に表現する方が単純になる
 * （SettingsViewModel の isAdminProvider は読み取り専用の問い合わせだが、こちらは書き込みが本質）。
 */
class AccountViewModel(
    private val apiClient: ApiClient,
    private val authViewModel: AuthViewModel,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _displayName = MutableStateFlow(currentDisplayName())
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _currentPassword = MutableStateFlow("")
    val currentPassword: StateFlow<String> = _currentPassword.asStateFlow()

    private val _newPassword = MutableStateFlow("")
    val newPassword: StateFlow<String> = _newPassword.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _profileMessage = MutableStateFlow<String?>(null)
    val profileMessage: StateFlow<String?> = _profileMessage.asStateFlow()

    /** profileMessage がエラーメッセージか（成功メッセージ時は false）。UI での色分け用。 */
    private val _profileMessageIsError = MutableStateFlow(false)
    val profileMessageIsError: StateFlow<Boolean> = _profileMessageIsError.asStateFlow()

    private val _passwordMessage = MutableStateFlow<String?>(null)
    val passwordMessage: StateFlow<String?> = _passwordMessage.asStateFlow()

    /** passwordMessage がエラーメッセージか（成功メッセージ時は false）。UI での色分け用。 */
    private val _passwordMessageIsError = MutableStateFlow(false)
    val passwordMessageIsError: StateFlow<Boolean> = _passwordMessageIsError.asStateFlow()

    /**
     * 認証済みユーザーの表示名で入力欄をプリフィルする（iOS AccountSettingsView.swift:81 相当の
     * onAppear パターン。SettingsScreen の LaunchedEffect から呼ぶ想定）。
     *
     * WHY 初期化時に読まない: 構築時点では authState が Unknown の可能性が高く、表示名は空になる。
     * 認証遷移後に明示的にプリフィルすることで、常に最新値を反映する（iOS:81 準拠）。
     */
    fun prefillDisplayName() {
        val displayName = (authViewModel.authState.value as? AuthState.Authenticated)?.user?.displayName ?: ""
        _displayName.value = displayName
    }

    /** 表示名入力欄の変更を反映する（Compose TextField の onValueChange から呼ぶ想定）。 */
    fun onDisplayNameChange(value: String) {
        _displayName.value = value
    }

    /** 現在パスワード入力欄の変更を反映する。 */
    fun onCurrentPasswordChange(value: String) {
        _currentPassword.value = value
    }

    /** 新パスワード入力欄の変更を反映する。 */
    fun onNewPasswordChange(value: String) {
        _newPassword.value = value
    }

    /**
     * 表示名をサーバへ保存する（iOS の「更新」ボタン = AccountSettingsView.swift:57 相当。
     * 即時 PATCH ではなく明示的な submit 操作）。
     * 成功時は [AuthViewModel.applyProfileUpdate] を呼び AuthState へ反映する。
     */
    suspend fun saveProfile(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        try {
            val updated = apiClient.updateProfile(_displayName.value)
            authViewModel.applyProfileUpdate(updated)
            _profileMessage.value = "表示名を更新しました"
            _profileMessageIsError.value = false
        } catch (e: ApiException) {
            _profileMessage.value = "表示名の更新に失敗しました"
            _profileMessageIsError.value = true
        }
        _isLoading.value = false
    }

    /**
     * パスワードを変更する。
     *
     * エラー写像は backend の区別（現パスワード誤り=400、新パスワード強度不足=422）をそのまま
     * ユーザー向け文言に対応させる（iOS AccountSettingsView.swift:327-331 は 400 のみ専用文言・
     * それ以外は汎用文言だが、Android は 422 も専用文言にする。iOS の「8文字以上」表示は
     * backend の実ポリシー（12文字以上・4種のうち3種以上の文字種、shared/password_policy.py）と
     * 乖離しているため踏襲しない）。
     * 成功時のみ入力欄をクリアする（失敗時は入力し直しの手間を避けるため保持する）。
     */
    suspend fun changePassword(): Unit = withContext(dispatcher) {
        _isLoading.value = true
        try {
            apiClient.changePassword(_currentPassword.value, _newPassword.value)
            _currentPassword.value = ""
            _newPassword.value = ""
            _passwordMessage.value = "パスワードを変更しました"
            _passwordMessageIsError.value = false
        } catch (e: ApiException.HttpError) {
            _passwordMessage.value = when (e.code) {
                400 -> "現在のパスワードが正しくありません"
                422 -> PASSWORD_STRENGTH_ERROR_MESSAGE
                else -> GENERIC_PASSWORD_ERROR_MESSAGE
            }
            _passwordMessageIsError.value = true
        } catch (e: ApiException) {
            _passwordMessage.value = GENERIC_PASSWORD_ERROR_MESSAGE
            _passwordMessageIsError.value = true
        }
        _isLoading.value = false
    }

    private fun currentDisplayName(): String =
        (authViewModel.authState.value as? AuthState.Authenticated)?.user?.displayName ?: ""

    private companion object {
        const val PASSWORD_STRENGTH_ERROR_MESSAGE =
            "パスワードは12文字以上で、英大文字・英小文字・数字・記号のうち3種類以上を組み合わせてください"
        const val GENERIC_PASSWORD_ERROR_MESSAGE = "パスワードの変更に失敗しました"
    }
}

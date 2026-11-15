package com.rioikeda.newslisten.onboarding.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.app.SourcesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 初回オンボーディングの完了状態の保持と取得・完了（android-dec-116）。状態はこの class の中で閉じる。
 */
class OnboardingCommands(
    private val api: SourcesApi,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _onboardingCompleted = MutableStateFlow<Boolean?>(null)

    /** `null` = 未取得（判定保留）。`false` のときだけ画面がオンボーディングを出す。 */
    val onboardingCompleted: StateFlow<Boolean?> = _onboardingCompleted.asStateFlow()

    /** サーバーの値を取り込む。取得失敗は行き止まりを防ぐため `true` 扱い。 */
    suspend fun refreshOnboardingStatus(): Unit = withContext(dispatcher) {
        try {
            _onboardingCompleted.value = api.fetchOnboardingStatus()
        } catch (e: ApiException) {
            _onboardingCompleted.value = true
        }
    }

    /** 完了を送る。送信に失敗しても利用者は先へ進める（次回起動時に再取得される）。 */
    suspend fun finishOnboarding(): Unit = withContext(dispatcher) {
        try {
            api.completeOnboarding()
        } catch (e: ApiException) {
            // ベストエフォート: サーバー同期に失敗してもローカルでは完了扱いにする。
        }
        _onboardingCompleted.value = true
    }
}

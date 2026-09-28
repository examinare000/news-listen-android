package com.rioikeda.newslisten.onboarding

import com.rioikeda.newslisten.model.FeaturedSitesResponse
import com.rioikeda.newslisten.model.OnboardingStatusResponse
import com.rioikeda.newslisten.model.RssSourcesResponse
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [OnboardingViewModel] のテスト専用フェイク。
 *
 * フェーズ13（初回オンボーディング・issue #140 P13）で使う fetchFeaturedSites/createSource/
 * fetchOnboardingStatus/completeOnboarding のみ挙動を差し替え可能にする。それ以外は
 * スコープ外のため、誤って呼ばれた場合は即座に失敗させて検出できるよう例外を投げる
 * （settings/auth 等の既存 Fake と同じ設計方針）。
 */
class FakeApiClient(
    private val onFetchFeaturedSites: suspend () -> FeaturedSitesResponse =
        { error("fetchFeaturedSites is not stubbed") },
    private val onCreateSource: suspend (name: String, url: String) -> RssSourcesResponse =
        { _, _ -> error("createSource is not stubbed") },
    private val onFetchOnboardingStatus: suspend () -> OnboardingStatusResponse =
        { error("fetchOnboardingStatus is not stubbed") },
    private val onCompleteOnboarding: suspend () -> OnboardingStatusResponse =
        { error("completeOnboarding is not stubbed") },
) : BaseFakeApiClient() {

    override suspend fun createSource(name: String, url: String): RssSourcesResponse =
        onCreateSource(name, url)

    override suspend fun fetchFeaturedSites(): FeaturedSitesResponse = onFetchFeaturedSites()

    override suspend fun fetchOnboardingStatus(): OnboardingStatusResponse = onFetchOnboardingStatus()

    override suspend fun completeOnboarding(): OnboardingStatusResponse = onCompleteOnboarding()
}

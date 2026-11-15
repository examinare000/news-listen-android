package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.domain.FeaturedCategory
import com.rioikeda.newslisten.settings.domain.RssSourceEntry
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** RSS ソース・おすすめサイト・生成の上限の読み取り（android-query-sources-01）。port の書込は呼ばない。 */
class SourcesQueries(
    private val api: SourcesApi,
    private val state: SourcesState,
) {
    /** 画面が読む一覧。読むたびに写すので、返した list を書き換えても次の読みは変わらない（android-check-06）。 */
    val sources: StateFlow<List<RssSourceEntry>> =
        derived(state.sources.map { it.toList() }) { state.sources.value.toList() }

    /** 一覧を取得して置き換える。失敗したときは一覧に触らない。 */
    suspend fun loadSources(): SourcesLoad = withContext(state.dispatcher) {
        try {
            state.sources.value = api.fetchSources()
            SourcesLoad.Loaded
        } catch (e: ApiException) {
            SourcesLoad.Failed
        }
    }

    /** おすすめサイトをカテゴリの表示順の束にして返す。状態は持たない。 */
    suspend fun loadFeaturedSites(): FeaturedSitesLoad = withContext(state.dispatcher) {
        try {
            val grouped = FeaturedCategory.groupByCategoryInOrder(api.fetchFeaturedSites())
            FeaturedSitesLoad.Loaded(
                FeaturedCategory.getDisplayOrderCategories(grouped).map { category ->
                    FeaturedSiteGroup(category, grouped.getValue(category).toList())
                },
            )
        } catch (e: ApiException) {
            FeaturedSitesLoad.Failed
        }
    }

    /** 404 は非表示（[QuotaLoad.Hidden]）、それ以外の失敗は [QuotaLoad.Failed]。 */
    suspend fun loadGenerationQuota(): QuotaLoad = withContext(state.dispatcher) {
        try {
            QuotaLoad.Loaded(api.fetchGenerationQuota())
        } catch (_: ApiException.NotFound) {
            QuotaLoad.Hidden
        } catch (e: ApiException) {
            QuotaLoad.Failed
        }
    }

    /** 読んだ時点で [read] を計算する [StateFlow]（`FeedQueries` の先例と同じ形）。値は保存しない。 */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private fun <T> derived(flow: Flow<T>, read: () -> T): StateFlow<T> = object : StateFlow<T> {
        override val value: T get() = read()
        override val replayCache: List<T> get() = listOf(read())
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            flow.distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
}

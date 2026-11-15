package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import kotlinx.coroutines.withContext

/**
 * RSS ソースの書込（android-cmd-sources-01）。
 *
 * [isAdminProvider] は呼出の時点で評価する。role は認証確立後に非同期で確定するため、構築時の固定値にしない
 * （ADR-047）。
 */
class SourcesCommands(
    private val api: SourcesApi,
    private val state: SourcesState,
    private val isAdminProvider: () -> Boolean,
) {
    suspend fun addSource(name: String, url: String): SourcesOutcome = withContext(state.dispatcher) {
        try {
            state.sources.value = api.addSource(name, url)
            SourcesOutcome.Done
        } catch (e: ApiException) {
            SourcesOutcome.Failed
        }
    }

    /** 管理者でなければ API を呼ばない（android-rule-sources-02）。 */
    suspend fun updateSource(oldUrl: String, name: String, url: String): SourcesOutcome =
        withContext(state.dispatcher) {
            if (!isAdminProvider()) return@withContext SourcesOutcome.AdminOnly
            try {
                state.sources.value = api.updateSource(oldUrl, name, url)
                SourcesOutcome.Done
            } catch (e: ApiException) {
                SourcesOutcome.Failed
            }
        }

    suspend fun removeSource(url: String): SourcesOutcome = withContext(state.dispatcher) {
        try {
            api.removeSource(url)
            state.sources.value = state.sources.value.filterNot { it.url == url }
            SourcesOutcome.Done
        } catch (e: ApiException) {
            SourcesOutcome.Failed
        }
    }

    /** おすすめサイトの購読。一覧は書かない。既に購読済み（409）は成功（android-rule-sources-01）。 */
    suspend fun subscribeFeatured(site: FeaturedSiteEntry): SourcesOutcome = withContext(state.dispatcher) {
        try {
            api.addSource(site.name, site.url)
            SourcesOutcome.Done
        } catch (_: ApiException.Conflict) {
            SourcesOutcome.Done
        } catch (e: ApiException) {
            SourcesOutcome.Failed
        }
    }
}

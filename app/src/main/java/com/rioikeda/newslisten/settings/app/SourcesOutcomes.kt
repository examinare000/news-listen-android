package com.rioikeda.newslisten.settings.app

import com.rioikeda.newslisten.settings.domain.FeaturedSiteEntry
import com.rioikeda.newslisten.settings.domain.GenerationQuota

/** Sources の command の結果（android-dec-115）。文言は持たず、presentation が書き分ける。 */
sealed interface SourcesOutcome {
    data object Done : SourcesOutcome

    data object AdminOnly : SourcesOutcome

    data object Failed : SourcesOutcome
}

sealed interface SourcesLoad {
    data object Loaded : SourcesLoad

    data object Failed : SourcesLoad
}

/** おすすめサイトのカテゴリごとの束。[category] は正規化済み。 */
data class FeaturedSiteGroup(val category: String, val sites: List<FeaturedSiteEntry>)

sealed interface FeaturedSitesLoad {
    data class Loaded(val groups: List<FeaturedSiteGroup>) : FeaturedSitesLoad

    data object Failed : FeaturedSitesLoad
}

sealed interface QuotaLoad {
    data class Loaded(val quota: GenerationQuota) : QuotaLoad

    /** 404。セクションを出さない（失敗のフラグは立てない）。 */
    data object Hidden : QuotaLoad

    data object Failed : QuotaLoad
}

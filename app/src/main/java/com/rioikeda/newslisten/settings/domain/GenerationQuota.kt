package com.rioikeda.newslisten.settings.domain

/**
 * Podcast 生成の本日の上限。上限 0 は無制限（android-check-04 の正本。比較はこのファイルの [of] だけが持つ）。
 */
sealed interface GenerationQuota {
    data class Unlimited(val used: Int) : GenerationQuota

    data class Limited(val limit: Int, val used: Int, val remaining: Int) : GenerationQuota {
        init {
            require(limit > 0) { "Limited の limit は正でなければならない: $limit" }
        }
    }

    companion object {
        /** 上限 0 は [Unlimited]（remaining は捨てる）、それ以外は [Limited]（remaining が null なら 0）。 */
        fun of(limit: Int, used: Int, remaining: Int?): GenerationQuota =
            if (limit == 0) Unlimited(used) else Limited(limit, used, remaining ?: 0)
    }
}

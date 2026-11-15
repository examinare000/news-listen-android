package com.rioikeda.newslisten.feed.domain

/**
 * 取り消し可能な保留中の Star/Dismiss 操作（android-rule-catalog-04）。保留は 1 件だけで、
 * 次の操作・画面の離脱・読み直しの前に確定する。失敗と 429 は元の位置へ戻す。
 */
data class PendingCuration(
    val article: Article,
    /** 一覧から除く前の位置。取り消しと失敗のとき元の位置へ戻すために持つ。 */
    val index: Int,
    val kind: Kind,
    /** 記事単位で指定された難易度（[Kind.STAR] のみ有効）。`null` ならサーバが既定の難易度で解決する。 */
    val difficulty: String?,
) {
    enum class Kind { STAR, DISMISS }

    /** [article] を元の位置へ挿入した新しい一覧。位置は一覧の長さに丸める。 */
    fun restoreInto(articles: List<Article>): List<Article> =
        articles.toMutableList().apply { add(minOf(index, articles.size), article) }

    companion object {
        /** [articleId] の最初の記事を除いた一覧と保留を返す。対象が無ければ `null`。 */
        fun stage(
            articles: List<Article>,
            articleId: String,
            kind: Kind,
            difficulty: String?,
        ): Pair<List<Article>, PendingCuration>? {
            val index = articles.indexOfFirst { it.id == articleId }
            if (index < 0) return null
            val target = articles[index]
            val remaining = articles.toMutableList().apply { removeAt(index) }
            return remaining to PendingCuration(target, index, kind, difficulty)
        }
    }
}

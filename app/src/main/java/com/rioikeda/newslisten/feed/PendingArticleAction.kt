package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.model.ArticleResponse

/**
 * 取り消し可能な保留中の Star/Dismiss 操作（issue #111）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift:227-255（PendingArticleAction）。
 */
data class PendingArticleAction(
    /** 対象記事。 */
    val article: ArticleResponse,
    /** 楽観的削除前の一覧内インデックス（取り消し時に元の位置へ戻すため）。 */
    val index: Int,
    /** 操作種別。 */
    val kind: Kind,
    /** 記事単位で指定された難易度（[Kind.STAR] のみ有効・issue #163）。`null` なら prefs のデフォルト難易度。 */
    val difficulty: String? = null,
) {
    /** 操作種別。 */
    enum class Kind { STAR, DISMISS }

    /** 取り消しトーストに出す文言（iOS FeedViewModel.swift のミラー）。 */
    val message: String
        get() = when (kind) {
            Kind.STAR -> "スターしました"
            Kind.DISMISS -> "削除しました"
        }
}

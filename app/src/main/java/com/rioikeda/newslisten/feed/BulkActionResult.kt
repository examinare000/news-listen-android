package com.rioikeda.newslisten.feed

/**
 * 一括 Star 操作の結果情報（トースト表示用）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedViewModel.swift:257-272（BulkActionResult）。
 */
data class BulkActionResult(
    /** 成功した記事数。 */
    val successCount: Int,
    /** 失敗した記事数。 */
    val failureCount: Int,
) {
    /** 操作結果の日本語説明。 */
    val message: String
        get() = if (failureCount == 0) {
            "${successCount}件を一括スターしました"
        } else {
            "${successCount}件をスターしました（失敗: ${failureCount}件）"
        }
}

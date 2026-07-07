package com.rioikeda.newslisten.feed

/**
 * 記事 URL の妥当性検証。HTTP/HTTPS スキームのみを許可する。
 * クラッシュを防ぐために全ての不正入力（null, 空, 不正形式）に対応する。
 */
object ArticleUrlValidator {

    /**
     * 与えられた URL が有効な HTTP/HTTPS URL かどうかを判定する。
     *
     * @param url 検証対象の URL（null 可）
     * @return 有効な HTTP/HTTPS URL の場合 true、それ以外は false
     *
     * WHY: Custom Tabs 対応では URL が絶対に http:// または https:// で始まる必要があります。
     * FTP・file:// など他のプロトコルでは ACTION_VIEW フォールバックも失敗する可能性が高いため、
     * 事前にスキームで厳格にフィルタリングします。null・空・不正形式は no-op とします。
     */
    fun isValidArticleUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }

        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            return false
        }

        // http:// または https:// で始まるか（大文字小文字を区別しない）
        return trimmedUrl.startsWith("http://", ignoreCase = true) ||
            trimmedUrl.startsWith("https://", ignoreCase = true)
    }
}

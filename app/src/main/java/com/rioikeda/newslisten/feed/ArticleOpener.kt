package com.rioikeda.newslisten.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * 記事 URL を Chrome Custom Tabs で開く。
 *
 * 動作:
 * 1. URL を検証（HTTP/HTTPS のみ）
 * 2. Custom Tabs で開く（ツールバー色は designsystem に準拠）
 * 3. Custom Tabs 非対応端末では ACTION_VIEW にフォールバック
 * 4. Intent 解決失敗 → no-op（クラッシュさせない）
 *
 * WHY: iOS の articleOpenMode=.inApp に対応。外部ブラウザ選択UI はP9の設定画面実装予定。
 */
object ArticleOpener {

    /**
     * 記事 URL を Chrome Custom Tabs で開く。
     *
     * @param context Context（Activity など）
     * @param url 開く URL（http/https のみ有効）
     * @param toolbarColor ツールバーの色（Int の ARGB 値）。ダークテーマ対応のため呼び出し側で指定。
     *
     * 不正な URL・解決失敗 → no-op（エラーを出力しない）
     */
    fun openArticle(context: Context, url: String, toolbarColor: Int) {
        // URL 検証：HTTP/HTTPS のみを許可
        if (!ArticleUrlValidator.isValidArticleUrl(url)) {
            return
        }

        try {
            // Custom Tabs Intent の構築
            val customTabsIntent = CustomTabsIntent.Builder()
                .setToolbarColor(toolbarColor)
                .setShowTitle(true)
                .build()

            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            // Intent 解決失敗 / Custom Tabs 非対応 → フォールバック
            // ACTION_VIEW で起動を試みるが、失敗時も no-op
            tryFallbackIntent(context, url)
        }
    }

    /**
     * ACTION_VIEW でのフォールバック起動（Custom Tabs 非対応端末向け）。
     * Intent 解決失敗時も無視する（クラッシュしない）。
     */
    private fun tryFallbackIntent(context: Context, url: String) {
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            // ブラウザが存在するか確認してから起動
            if (fallbackIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(fallbackIntent)
            }
        } catch (e: Exception) {
            // 起動失敗：無視する
        }
    }
}

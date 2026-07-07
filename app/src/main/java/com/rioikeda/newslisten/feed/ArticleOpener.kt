package com.rioikeda.newslisten.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.rioikeda.newslisten.preferences.ArticleOpenMode

/**
 * 記事 URL を設定（[ArticleOpenMode]）に従って開く。
 *
 * 動作:
 * 1. URL を検証（HTTP/HTTPS のみ）。不正 URL は両モードで no-op（クラッシュさせない）
 * 2. IN_APP: Chrome Custom Tabs で開く（ツールバー色は designsystem に準拠）。
 *    Custom Tabs 非対応端末では ACTION_VIEW にフォールバック
 * 3. EXTERNAL: Custom Tabs を使わず ACTION_VIEW で直接外部ブラウザを起動
 * 4. Intent 解決失敗 → no-op（クラッシュさせない）
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedView.swift:204-214（open(_:)）。
 * iOS の `.inApp`（SFSafariViewController） / `.external`（openURL）のミラー。
 */
object ArticleOpener {

    /** [resolveOpenAction] の戻り値。実際に起動する Intent の種類を表す。 */
    enum class OpenAction {
        CUSTOM_TABS,
        EXTERNAL_VIEW,
    }

    /**
     * [ArticleOpenMode] からどちらの Intent 経路を使うかを決める純粋な分岐判定。
     * Context/Intent に依存しないため Robolectric なしで単体テスト可能。
     */
    fun resolveOpenAction(mode: ArticleOpenMode): OpenAction = when (mode) {
        ArticleOpenMode.IN_APP -> OpenAction.CUSTOM_TABS
        ArticleOpenMode.EXTERNAL -> OpenAction.EXTERNAL_VIEW
    }

    /**
     * 記事 URL を設定に従って開く。
     *
     * @param context Context（Activity など）
     * @param url 開く URL（http/https のみ有効）
     * @param toolbarColor ツールバーの色（Int の ARGB 値）。ダークテーマ対応のため呼び出し側で指定。
     *   IN_APP（Custom Tabs）でのみ使用する。
     * @param mode 記事タップ時の遷移先設定（[com.rioikeda.newslisten.preferences.PreferencesStore.articleOpenMode]）。
     *
     * 不正な URL・解決失敗 → no-op（エラーを出力しない）
     */
    fun openArticle(context: Context, url: String, toolbarColor: Int, mode: ArticleOpenMode) {
        // URL 検証：HTTP/HTTPS のみを許可（両モード共通）
        if (!ArticleUrlValidator.isValidArticleUrl(url)) {
            return
        }

        when (resolveOpenAction(mode)) {
            OpenAction.CUSTOM_TABS -> openInCustomTabs(context, url, toolbarColor)
            OpenAction.EXTERNAL_VIEW -> openInExternalBrowser(context, url)
        }
    }

    /** Custom Tabs で開く。非対応端末では ACTION_VIEW にフォールバックする。 */
    private fun openInCustomTabs(context: Context, url: String, toolbarColor: Int) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setToolbarColor(toolbarColor)
                .setShowTitle(true)
                .build()

            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            // Intent 解決失敗 / Custom Tabs 非対応 → フォールバック
            // ACTION_VIEW で起動を試みるが、失敗時も no-op
            openInExternalBrowser(context, url)
        }
    }

    /**
     * ACTION_VIEW で外部ブラウザを直接起動する（Custom Tabs を使わない）。
     * Intent 解決失敗時も無視する（クラッシュしない）。
     */
    private fun openInExternalBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            // ブラウザが存在するか確認してから起動
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // 起動失敗：無視する
        }
    }
}

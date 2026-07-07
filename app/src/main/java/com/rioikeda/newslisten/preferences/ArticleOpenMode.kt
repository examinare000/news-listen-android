package com.rioikeda.newslisten.preferences

/**
 * 記事タップ時の遷移先設定（アプリ内 Safari か外部ブラウザか）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:12-28（ArticleOpenMode）のミラー。
 * サーバー側 API に対応フィールドが無く、ローカル専用（[PreferencesStore] で永続化）の設定。
 */
enum class ArticleOpenMode(val code: String) {
    IN_APP("in_app"),
    EXTERNAL("external");

    companion object {
        /** 統一デフォルト（iOS AppState.swift:230 の `.inApp` フォールバックに準拠）。 */
        val DEFAULT: ArticleOpenMode = IN_APP

        /** 未知の値・null は [DEFAULT] にフォールバックする。 */
        fun fromCode(code: String?): ArticleOpenMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

package com.rioikeda.newslisten.preferences

/**
 * 記事の日付表記設定（絶対時刻か相対時刻か）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:72-73,231（timeFormat）。iOS は生 String
 * ("absolute"/"relative") で保持するが、Android は既存の [com.rioikeda.newslisten.core.Difficulty]
 * と同じ enum + code + fromCode の規約に揃える。サーバー側 API に対応フィールドが無く、
 * ローカル専用（[PreferencesStore] で永続化）の設定。
 */
enum class TimeFormat(val code: String) {
    ABSOLUTE("absolute"),
    RELATIVE("relative");

    companion object {
        /** 統一デフォルト（iOS AppState.swift:231 の `"absolute"` フォールバックに準拠）。 */
        val DEFAULT: TimeFormat = ABSOLUTE

        /** 未知の値・null は [DEFAULT] にフォールバックする。 */
        fun fromCode(code: String?): TimeFormat =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

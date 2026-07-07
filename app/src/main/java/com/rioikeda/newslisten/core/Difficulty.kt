package com.rioikeda.newslisten.core

/**
 * 記事の英語難易度。
 *
 * 値集合・表示ラベルは iOS 版 DifficultyLabel（ios/NewsListenApp/NewsListenApp/Utilities/DifficultyLabel.swift）に準拠する。
 * [code] はサーバー API とのやり取りに使う snake_case 値（例: `toeic_600`）。
 *
 * docs/design/shared-playback-spec.md §3.4: 統一デフォルト難易度は未ログイン時・サーバー prefs 未取得時・
 * 通信失敗時のフォールバック値として `toeic_600` を用いる。[DEFAULT] と [fromCode] がこの仕様を表現する。
 */
enum class Difficulty(val code: String, val label: String) {
    TOEIC_600("toeic_600", "TOEIC 600-"),
    TOEIC_900("toeic_900", "TOEIC 730-900"),
    IELTS_55("ielts_55", "IELTS 5.5-6.5"),
    IELTS_7("ielts_7", "IELTS 7.0+"),
    EIKEN_2("eiken_2", "英検2級"),
    EIKEN_P1("eiken_p1", "英検準1級");

    companion object {
        /** 統一デフォルト難易度（spec §3.4）。 */
        val DEFAULT: Difficulty = TOEIC_600

        /**
         * サーバー API 由来のコード文字列を [Difficulty] にパースする。
         * 未知の値・null は [DEFAULT] にフォールバックする（未ログイン時・サーバー prefs 未取得時・通信失敗時の既定仕様）。
         */
        fun fromCode(code: String?): Difficulty =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

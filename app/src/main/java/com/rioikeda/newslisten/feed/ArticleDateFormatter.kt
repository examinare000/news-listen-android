package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.core.RelativeTimeFormatter
import com.rioikeda.newslisten.preferences.TimeFormat
import java.time.Instant

/**
 * 記事一覧の公開日表示を [TimeFormat] 設定に応じて出し分ける純粋関数（フェーズ10 P10 Task4）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift（timeFormat）。ABSOLUTE は従来通り
 * ISO8601 文字列の先頭10文字（YYYY-MM-DD）、RELATIVE は [RelativeTimeFormatter] に委譲する。
 * `now` を呼び出し側から注入することで、システム時計に依存せずテストできる。
 */
object ArticleDateFormatter {

    fun format(publishedAt: String, timeFormat: TimeFormat, now: Instant): String = when (timeFormat) {
        TimeFormat.ABSOLUTE -> publishedAt.take(10)
        TimeFormat.RELATIVE -> RelativeTimeFormatter.formatIso(publishedAt, now)
    }
}

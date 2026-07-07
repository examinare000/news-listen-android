package com.rioikeda.newslisten.core

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 相対時刻の日本語表記フォーマッタ。
 *
 * 正本仕様: docs/design/shared-playback-spec.md §3。
 * コアは時刻値のみを扱う純関数（システム時計に依存しない。now は常に呼び出し側が注入する）。
 * ISO8601 文字列のパースと不正入力ハンドリングはアダプタ層（[formatIso]）の責務（spec §3.3）。
 */
object RelativeTimeFormatter {

    /**
     * `target` と `now` の差分から日本語の相対時刻表記を返す（spec §3.1・§3.2）。
     */
    fun format(target: Instant, now: Instant): String {
        val diffSeconds = Duration.between(target, now).seconds

        if (diffSeconds < 0) return "もうすぐ"
        if (diffSeconds < 60) return "たった今"

        val minutes = diffSeconds / 60
        if (minutes < 60) return "${minutes}分前"

        val hours = minutes / 60
        if (hours < 24) return "${hours}時間前"

        val days = hours / 24
        if (days < 30) return "${days}日前"

        // WHY 年を先に判定: 月=floor(日/30)・年=floor(日/365) は独立に丸めるため、
        // 月を先に判定すると 360〜364 日（月=12・年=0）で "12か月前" にならず矛盾する。
        // spec §3.2 の判定順序（年を月より先に判定）に従う。
        val years = days / 365
        if (years >= 1) return "${years}年前"

        val months = days / 30
        return "${months}か月前"
    }

    /**
     * ISO8601 文字列をパースして [format] へ渡すアダプタ関数（spec §3.3）。
     * 空文字列・パース不能な文字列は "" を返す（iOS の ISO8601DateFormatter 相当の挙動）。
     */
    fun formatIso(isoString: String, now: Instant): String {
        if (isoString.isEmpty()) return ""

        val target = parseInstant(isoString) ?: return ""
        return format(target, now)
    }

    private fun parseInstant(isoString: String): Instant? =
        try {
            Instant.parse(isoString)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(isoString).toInstant()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
}

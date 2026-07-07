package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.preferences.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * [ArticleDateFormatter] の出し分けロジック（フェーズ10 P10 Task4）。
 *
 * FeedScreen で直書きだった `article.publishedAt.take(10)`（ABSOLUTE 固定）を
 * timeFormat 設定に応じて [com.rioikeda.newslisten.core.RelativeTimeFormatter] へ
 * 委譲する純粋関数として切り出し、System 時計に依存せずテストできるようにする。
 */
class ArticleDateFormatterTest {

    @Test
    fun ABSOLUTEなら先頭10文字の日付部分を返す() {
        val result = ArticleDateFormatter.format(
            publishedAt = "2026-07-01T12:34:56Z",
            timeFormat = TimeFormat.ABSOLUTE,
            now = Instant.parse("2026-07-08T00:00:00Z"),
        )

        assertEquals("2026-07-01", result)
    }

    @Test
    fun RELATIVEならRelativeTimeFormatterへ委譲した相対表記を返す() {
        val result = ArticleDateFormatter.format(
            publishedAt = "2026-07-01T00:00:00Z",
            timeFormat = TimeFormat.RELATIVE,
            now = Instant.parse("2026-07-03T00:00:00Z"),
        )

        assertEquals("2日前", result)
    }

    @Test
    fun RELATIVEでパース不能な文字列は空文字を返す() {
        val result = ArticleDateFormatter.format(
            publishedAt = "not-a-date",
            timeFormat = TimeFormat.RELATIVE,
            now = Instant.parse("2026-07-08T00:00:00Z"),
        )

        assertEquals("", result)
    }
}

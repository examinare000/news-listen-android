package com.rioikeda.newslisten.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 記事の日付表記設定。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:72-73,231（timeFormat、"absolute"/"relative" の生文字列）。
 * iOS は生 String だが、Android は既存の [com.rioikeda.newslisten.core.Difficulty] と同じ
 * enum + code + fromCode の規約に揃える（フェーズ10 P10 Task3）。
 * サーバー側に対応フィールドが無いローカル専用設定。
 */
class TimeFormatTest {

    @Test
    fun デフォルト値がABSOLUTEである() {
        assertEquals("absolute", TimeFormat.DEFAULT.code)
        assertEquals(TimeFormat.ABSOLUTE, TimeFormat.DEFAULT)
    }

    @Test
    fun 既知のコード文字列を対応する値にパースできる() {
        assertEquals(TimeFormat.ABSOLUTE, TimeFormat.fromCode("absolute"))
        assertEquals(TimeFormat.RELATIVE, TimeFormat.fromCode("relative"))
    }

    @Test
    fun 未知のコード文字列やnullはデフォルトにフォールバックする() {
        assertEquals(TimeFormat.DEFAULT, TimeFormat.fromCode("unknown"))
        assertEquals(TimeFormat.DEFAULT, TimeFormat.fromCode(""))
        assertEquals(TimeFormat.DEFAULT, TimeFormat.fromCode(null))
    }
}

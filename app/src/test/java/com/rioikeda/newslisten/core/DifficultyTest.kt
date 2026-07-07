package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/design/shared-playback-spec.md §3.4 で定義された統一デフォルト難易度の検証。
 * 値集合・表示ラベルは iOS 版 DifficultyLabel（ios/NewsListenApp/NewsListenApp/Utilities/DifficultyLabel.swift）に準拠する。
 */
class DifficultyTest {

    @Test
    fun デフォルト値がtoeic_600である() {
        assertEquals("toeic_600", Difficulty.DEFAULT.code)
        assertEquals(Difficulty.TOEIC_600, Difficulty.DEFAULT)
    }

    @Test
    fun 既知のコード文字列を対応する難易度にパースできる() {
        assertEquals(Difficulty.TOEIC_600, Difficulty.fromCode("toeic_600"))
        assertEquals(Difficulty.TOEIC_900, Difficulty.fromCode("toeic_900"))
        assertEquals(Difficulty.IELTS_55, Difficulty.fromCode("ielts_55"))
        assertEquals(Difficulty.IELTS_7, Difficulty.fromCode("ielts_7"))
        assertEquals(Difficulty.EIKEN_2, Difficulty.fromCode("eiken_2"))
        assertEquals(Difficulty.EIKEN_P1, Difficulty.fromCode("eiken_p1"))
    }

    @Test
    fun 未知のコード文字列はデフォルト難易度にフォールバックする() {
        assertEquals(Difficulty.DEFAULT, Difficulty.fromCode("unknown_code"))
        assertEquals(Difficulty.DEFAULT, Difficulty.fromCode(""))
    }

    @Test
    fun nullはデフォルト難易度にフォールバックする() {
        assertEquals(Difficulty.DEFAULT, Difficulty.fromCode(null))
    }

    @Test
    fun 表示ラベルがiOS版DifficultyLabelと一致する() {
        assertEquals("TOEIC 600-", Difficulty.TOEIC_600.label)
        assertEquals("TOEIC 730-900", Difficulty.TOEIC_900.label)
        assertEquals("IELTS 5.5-6.5", Difficulty.IELTS_55.label)
        assertEquals("IELTS 7.0+", Difficulty.IELTS_7.label)
        assertEquals("英検2級", Difficulty.EIKEN_2.label)
        assertEquals("英検準1級", Difficulty.EIKEN_P1.label)
    }
}

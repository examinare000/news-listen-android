package com.rioikeda.newslisten.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * resume 位置決定の準拠テスト。
 *
 * verifies: android-contract-05
 * 正本仕様: docs/design/shared-playback-spec.md §4.3（RS表）・§6.4。
 * テストメソッド名は行 ID（shared-resume-01 等）を先頭に含め、正本表の行との対応を照合可能にする。
 */
class ResumeRuleConformanceTest {

    @Test
    fun `shared-resume-01 サーバ位置0は0`() {
        assertEquals(0.0, resolveResumePosition(0.0, 600), 0.0)
    }

    @Test
    fun `shared-resume-02 途中位置はそのまま`() {
        assertEquals(120.0, resolveResumePosition(120.0, 600), 0.0)
    }

    @Test
    fun `shared-resume-03 末尾2秒窓の境界598秒は0`() {
        assertEquals(0.0, resolveResumePosition(598.0, 600), 0.0)
    }

    @Test
    fun `shared-resume-04 窓の直前597点5秒はそのまま`() {
        assertEquals(597.5, resolveResumePosition(597.5, 600), 0.0)
    }

    @Test
    fun `shared-resume-05 duration到達は0`() {
        assertEquals(0.0, resolveResumePosition(600.0, 600), 0.0)
    }

    @Test
    fun `shared-resume-06 duration0は末尾判定せずサーバ位置`() {
        assertEquals(120.0, resolveResumePosition(120.0, 0), 0.0)
    }

    @Test
    fun `shared-resume-07 負値は0`() {
        assertEquals(0.0, resolveResumePosition(-5.0, 600), 0.0)
    }

    @Test
    fun `Spec 599は末尾2秒窓内で0`() {
        assertEquals(0.0, resolveResumePosition(599.0, 600), 0.0)
    }
}

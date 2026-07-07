package com.rioikeda.newslisten.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 記事タップ時の遷移先設定。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/AppState.swift:12-28（ArticleOpenMode）のミラー。
 * サーバー側に対応フィールドが無いローカル専用設定（フェーズ10 P10 Task3）。
 */
class ArticleOpenModeTest {

    @Test
    fun デフォルト値がIN_APPである() {
        assertEquals("in_app", ArticleOpenMode.DEFAULT.code)
        assertEquals(ArticleOpenMode.IN_APP, ArticleOpenMode.DEFAULT)
    }

    @Test
    fun 既知のコード文字列を対応する値にパースできる() {
        assertEquals(ArticleOpenMode.IN_APP, ArticleOpenMode.fromCode("in_app"))
        assertEquals(ArticleOpenMode.EXTERNAL, ArticleOpenMode.fromCode("external"))
    }

    @Test
    fun 未知のコード文字列やnullはデフォルトにフォールバックする() {
        assertEquals(ArticleOpenMode.DEFAULT, ArticleOpenMode.fromCode("unknown"))
        assertEquals(ArticleOpenMode.DEFAULT, ArticleOpenMode.fromCode(""))
        assertEquals(ArticleOpenMode.DEFAULT, ArticleOpenMode.fromCode(null))
    }
}

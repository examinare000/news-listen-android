package com.rioikeda.newslisten.feed

import com.rioikeda.newslisten.preferences.ArticleOpenMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ArticleOpener] のモード分岐判定（フェーズ10 P10 Task4）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Feed/FeedView.swift:204-214（open(_:)）。
 * `openArticle` 本体は Context/Intent 起動を伴い Robolectric 未導入の本プロジェクトでは
 * 直接テストできないため、Custom Tabs か ACTION_VIEW かを決める純粋な分岐判定
 * （[ArticleOpener.resolveOpenAction]）のみを切り出してテストする。
 */
class ArticleOpenerTest {

    @Test
    fun IN_APPはCustomTabsを選ぶ() {
        assertEquals(
            ArticleOpener.OpenAction.CUSTOM_TABS,
            ArticleOpener.resolveOpenAction(ArticleOpenMode.IN_APP),
        )
    }

    @Test
    fun EXTERNALは外部ブラウザ起動を選ぶ() {
        assertEquals(
            ArticleOpener.OpenAction.EXTERNAL_VIEW,
            ArticleOpener.resolveOpenAction(ArticleOpenMode.EXTERNAL),
        )
    }
}

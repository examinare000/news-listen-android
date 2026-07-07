package com.rioikeda.newslisten.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 記事 URL の妥当性検証ロジック（HTTP/HTTPS のみを許可）。
 * URLが不正なときはクラッシュさせず安全に処理する。
 */
class ArticleUrlValidatorTest {

    @Test
    fun httpスキームのURLは有効() {
        assertTrue(ArticleUrlValidator.isValidArticleUrl("http://example.com/article"))
        assertTrue(ArticleUrlValidator.isValidArticleUrl("http://example.com"))
    }

    @Test
    fun httpsスキームのURLは有効() {
        assertTrue(ArticleUrlValidator.isValidArticleUrl("https://example.com/article"))
        assertTrue(ArticleUrlValidator.isValidArticleUrl("https://example.com"))
        assertTrue(ArticleUrlValidator.isValidArticleUrl("https://example.com/article?query=1&foo=bar"))
    }

    @Test
    fun httpスキームのURLは大文字でも有効() {
        assertTrue(ArticleUrlValidator.isValidArticleUrl("HTTP://example.com"))
        assertTrue(ArticleUrlValidator.isValidArticleUrl("HTTPS://example.com"))
    }

    @Test
    fun httpスキームのURLは混合大文字でも有効() {
        assertTrue(ArticleUrlValidator.isValidArticleUrl("HtTp://example.com"))
        assertTrue(ArticleUrlValidator.isValidArticleUrl("HtTpS://example.com"))
    }

    @Test
    fun プロトコルなしのURLは無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl("example.com"))
        assertFalse(ArticleUrlValidator.isValidArticleUrl("www.example.com"))
    }

    @Test
    fun ftpスキームのURLは無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl("ftp://example.com/file"))
    }

    @Test
    fun ファイルスキームのURLは無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl("file:///path/to/file"))
    }

    @Test
    fun 空文字列は無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl(""))
    }

    @Test
    fun nullは無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl(null))
    }

    @Test
    fun 空白のみの文字列は無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl("   "))
    }

    @Test
    fun 不正な形式のURLは無効() {
        assertFalse(ArticleUrlValidator.isValidArticleUrl("ht!tp://example.com"))
        assertFalse(ArticleUrlValidator.isValidArticleUrl("htp://example.com"))
    }
}

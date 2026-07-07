package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * POST /articles/{id}/star の optional リクエストボディ DTO。
 *
 * 正本: backend/api/schemas.py:170-179（StarRequest, ADR-060・issue #163）。
 * difficulty は DifficultyLevel（backend/shared/models.py の Literal 文字列） | None。
 * DTO 層では検証をサーバーに委ね、素の String として転送する
 * （ドメイン層の Difficulty enum への変換は本 DTO の責務外）。
 * 未指定・null の場合は呼び出し元（star_article）で prefs.default_difficulty に
 * フォールバックする（旧クライアント＝ボディなし送信の後方互換）。
 */
@Serializable
data class StarRequest(
    val difficulty: String? = null,
)

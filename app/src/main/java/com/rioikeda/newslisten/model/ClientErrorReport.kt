package com.rioikeda.newslisten.model

import kotlinx.serialization.Serializable

/**
 * POST /client-errors のリクエストボディ DTO（クラッシュ/クライアントエラー報告、issue #140 フェーズ12）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/ClientErrorPayload.swift のミラー、
 * backend/api/routers/client_errors.py の ClientErrorReport に対応する。
 * 機微情報は backend 側でログ送出時にスクラブされるが、クライアント側でも PII を載せない方針
 * （context は String 値のみ・自由記述の例外メッセージは含めない。CrashReportFormatter 参照）。
 */
@Serializable
data class ClientErrorReport(
    val source: String,
    val kind: String,
    val message: String? = null,
    val context: Map<String, String>? = null,
)

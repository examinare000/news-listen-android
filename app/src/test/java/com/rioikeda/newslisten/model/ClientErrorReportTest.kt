package com.rioikeda.newslisten.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ClientErrorReport（POST /client-errors の body）の検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/ClientErrorPayload.swift のミラー、
 * backend/api/routers/client_errors.py の ClientErrorReport に対応する。
 */
class ClientErrorReportTest {

    @Test
    fun 全フィールド指定時はそのままエンコードされる() {
        val report = ClientErrorReport(
            source = "android",
            kind = "crash",
            message = "java.lang.RuntimeException",
            context = mapOf("app_version" to "1.0.0"),
        )

        val encoded = NewsListenJson.encodeToString(ClientErrorReport.serializer(), report)

        assertEquals(
            """{"source":"android","kind":"crash","message":"java.lang.RuntimeException","context":{"app_version":"1.0.0"}}""",
            encoded,
        )
    }

    @Test
    fun messageとcontextが未指定時は省略してエンコードされる() {
        val report = ClientErrorReport(source = "android", kind = "crash")

        val encoded = NewsListenJson.encodeToString(ClientErrorReport.serializer(), report)

        assertEquals("""{"source":"android","kind":"crash"}""", encoded)
    }
}

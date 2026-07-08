package com.rioikeda.newslisten.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrashReportFormatter] の検証。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/CrashReporter.swift の
 * CrashReportFormatter（MXCrashDiagnostic の原始値→ペイロード）のミラー。
 * Throwable/Android 非依存の純粋関数として、JVM 単体テストのみで全経路を検証する。
 */
class CrashReportFormatterTest {

    private val deviceInfo = DeviceInfo(appVersion = "1.2.3", osVersion = "14")

    @Test
    fun sourceはandroidでkindはcrash() {
        val report = CrashReportFormatter.format(RuntimeException("boom"), deviceInfo)

        assertEquals("android", report.source)
        assertEquals("crash", report.kind)
    }

    @Test
    fun contextに例外クラス名とアプリ_OSバージョンを積む() {
        val report = CrashReportFormatter.format(IllegalStateException("boom"), deviceInfo)

        assertEquals("java.lang.IllegalStateException", report.context?.get("exception_class"))
        assertEquals("1.2.3", report.context?.get("app_version"))
        assertEquals("14", report.context?.get("os_version"))
    }

    @Test
    fun 例外メッセージの自由記述はcontextにもmessageにも含めない() {
        val report = CrashReportFormatter.format(
            RuntimeException("user email is secret-user@example.com"),
            deviceInfo,
        )

        assertFalse(report.message.orEmpty().contains("secret-user@example.com"))
        assertFalse(report.context.orEmpty().values.any { it.contains("secret-user@example.com") })
    }

    @Test
    fun messageはスタックトレース要約を含む() {
        val throwable = RuntimeException("boom")

        val report = CrashReportFormatter.format(throwable, deviceInfo)

        assertTrue(report.message.orEmpty().contains("java.lang.RuntimeException"))
        assertTrue(report.message.orEmpty().contains(throwable.stackTrace.first().toString()))
    }

    @Test
    fun messageは4000文字を超えない() {
        val throwable = RuntimeException("boom")
        val longMethodName = "m".repeat(500)
        throwable.stackTrace = Array(20) {
            StackTraceElement("com.example.Very.Long.Class.Name", longMethodName, "File.kt", it)
        }

        val report = CrashReportFormatter.format(throwable, deviceInfo)

        assertTrue((report.message?.length ?: 0) <= 4000)
    }
}

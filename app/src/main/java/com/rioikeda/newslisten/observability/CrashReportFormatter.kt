package com.rioikeda.newslisten.observability

import com.rioikeda.newslisten.model.ClientErrorReport

/**
 * クラッシュ報告に積む端末/アプリ情報の原始値（PII を含まない）。
 *
 * 正本: CrashReporter.swift の appVersion（applicationBuildVersion）に相当。
 */
data class DeviceInfo(val appVersion: String, val osVersion: String)

/**
 * Throwable + 端末情報から [ClientErrorReport] を組み立てる純粋関数（テスト対象）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/CrashReporter.swift の
 * CrashReportFormatter（MXCrashDiagnostic の原始値→ペイロード）のミラー。
 *
 * WHY 自由記述を含めない: [Throwable.message] はアプリコードが任意の文字列を積める
 * 自由記述であり、ユーザー入力（メールアドレス等）を含み得る。そのため message には
 * 例外クラス名 + スタックトレース（コード上の位置情報のみで PII を含まない）の要約のみを使い、
 * throwable.message 自体は一切送信しない（iOS の terminationReason が OS 内部診断文字列
 * であるのとは異なり、Android の例外メッセージは呼び出し元次第で PII を含み得るため）。
 */
object CrashReportFormatter {
    private const val SOURCE = "android"
    private const val KIND = "crash"

    // backend の ClientErrorReport.message は max_length=4000（超過は 422）。
    private const val MAX_MESSAGE_LENGTH = 4000

    // 深すぎるスタックでの肥大化を避けるため上位フレームのみ使う。
    private const val MAX_STACK_FRAMES = 20

    fun format(throwable: Throwable, deviceInfo: DeviceInfo): ClientErrorReport {
        val exceptionClassName = throwable.javaClass.name

        val stackSummary = throwable.stackTrace
            .take(MAX_STACK_FRAMES)
            .joinToString(separator = "\n") { it.toString() }
        val message = "$exceptionClassName\n$stackSummary".take(MAX_MESSAGE_LENGTH)

        val context = mapOf(
            "exception_class" to exceptionClassName,
            "app_version" to deviceInfo.appVersion,
            "os_version" to deviceInfo.osVersion,
        )

        return ClientErrorReport(source = SOURCE, kind = KIND, message = message, context = context)
    }
}

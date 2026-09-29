package com.rioikeda.newslisten.observability

import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.network.BaseFakeApiClient

/**
 * [CrashReporter] のテスト専用フェイク。
 *
 * フェーズ12（クラッシュ報告）で使う reportClientError のみ挙動を差し替え可能にする。
 * それ以外はこのテストスイートのスコープ外のため、誤って呼ばれた場合は即座に失敗させて
 * 検出できるよう例外を投げる（auth/feed/podcast の Fake と同じ方針）。
 */
class FakeApiClient(
    private val onReportClientError: suspend (report: ClientErrorReport) -> Unit = {
        error("reportClientError is not stubbed")
    },
) : BaseFakeApiClient() {
    /** reportClientError に渡された [ClientErrorReport] の呼び出し履歴。 */
    val reportCalls: MutableList<ClientErrorReport> = mutableListOf()

    override suspend fun reportClientError(report: ClientErrorReport) {
        reportCalls.add(report)
        onReportClientError(report)
    }
}

package com.rioikeda.newslisten.observability

import com.rioikeda.newslisten.model.ClientErrorReport
import com.rioikeda.newslisten.model.NewsListenJson
import com.rioikeda.newslisten.network.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * [CrashReporter] の挙動検証（フェーズ12・issue #140）。
 *
 * 正本: ios/NewsListenApp/NewsListenApp/Observability/CrashReporter.swift のミラー。
 * iOS は MetricKit の次回起動時配信を使うが、Android は
 * Thread.setDefaultUncaughtExceptionHandler で未捕捉例外を捕まえ filesDir に永続化し、
 * 次回起動時（flush）に送信する方式に置換する。
 */
class CrashReporterTest {

    private lateinit var crashFile: File
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        crashFile = File.createTempFile("crash_report", ".json")
        crashFile.delete() // 「未永続化」を初期状態にする
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        crashFile.delete()
    }

    private val deviceInfo = DeviceInfo(appVersion = "1.0.0", osVersion = "14")

    /** テスト対象の生成を共通化する。dispatcher は runTest の testScheduler に紐付ける。 */
    private fun TestScope.newReporter(apiClient: FakeApiClient = FakeApiClient()): CrashReporter =
        CrashReporter(crashFile, deviceInfo, apiClient, StandardTestDispatcher(testScheduler))

    // --- install / 永続化 ---

    @Test
    fun installは未捕捉例外発生時にクラッシュレポートをfilesDirへ永続化する() = runTest {
        val reporter = newReporter()
        reporter.install()

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(crashFile.exists())
        val persisted = NewsListenJson.decodeFromString(ClientErrorReport.serializer(), crashFile.readText())
        assertEquals("android", persisted.source)
        assertEquals("crash", persisted.kind)
    }

    @Test
    fun installは元のdefaultUncaughtExceptionHandlerを保持し委譲する() = runTest {
        var delegatedThrowable: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegatedThrowable = throwable }
        val reporter = newReporter()

        reporter.install()
        val boom = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(boom, delegatedThrowable)
    }

    @Test
    fun installはpersist失敗時もErrorを含め前のハンドラへ委譲する() = runTest {
        // catch (e: Exception) は Error を捕まえられないため、getPath() で Error を投げる File を
        // 使って persist() を Exception ではない Throwable で失敗させ、委譲が finally で
        // 保証されていることを検証する（IOException 等の Exception では新旧どちらの実装でも
        // 委譲されてしまい、この分岐の差分を検出できないため）。
        val unwritableFile = object : File(crashFile.path) {
            override fun getPath(): String = throw OutOfMemoryError("simulated persist failure")
        }
        var delegatedThrowable: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegatedThrowable = throwable }
        val reporter = CrashReporter(unwritableFile, deviceInfo, FakeApiClient(), StandardTestDispatcher(testScheduler))

        reporter.install()
        val boom = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(boom, delegatedThrowable)
    }

    // --- flush ---

    @Test
    fun flushは永続化済みレポートを送信し成功したら削除する() = runTest {
        val persisted = ClientErrorReport(source = "android", kind = "crash")
        crashFile.writeText(NewsListenJson.encodeToString(ClientErrorReport.serializer(), persisted))
        val apiClient = FakeApiClient(onReportClientError = {})
        val reporter = newReporter(apiClient)

        reporter.flush()

        assertEquals(listOf(persisted), apiClient.reportCalls)
        assertFalse(crashFile.exists())
    }

    @Test
    fun flushは送信失敗時に送信中ファイルを残し次回flushで再送する() = runTest {
        val persisted = ClientErrorReport(source = "android", kind = "crash")
        crashFile.writeText(NewsListenJson.encodeToString(ClientErrorReport.serializer(), persisted))
        var shouldFail = true
        val apiClient = FakeApiClient(onReportClientError = {
            if (shouldFail) throw ApiException.NetworkError(IOException("down"))
        })
        val reporter = newReporter(apiClient)

        reporter.flush() // 1回目: 送信失敗。送信中ファイルは削除されず残るはず。
        assertEquals(listOf(persisted), apiClient.reportCalls)

        shouldFail = false
        reporter.flush() // 2回目: 残っていた送信中ファイルが再送されて成功するはず。

        assertEquals(listOf(persisted, persisted), apiClient.reportCalls)
    }

    @Test
    fun flushは送信中に新しいクラッシュが永続化されても上書きしない() = runTest {
        // レース再現: flush が crashFile を読み取ってから削除するまでの間（＝送信中）に
        // 別スレッドの新しいクラッシュが persist() で crashFile を上書きするケース。
        // 旧実装は「送信成功時に無条件で crashFile を削除する」ため、この新しいクラッシュが
        // 消えてしまう（data loss）。rename 方式ではこれを防ぎ、新しいクラッシュは
        // crashFile にそのまま残る。
        val reportA = ClientErrorReport(source = "android", kind = "crash", message = "A")
        val reportB = ClientErrorReport(source = "android", kind = "crash", message = "B")
        crashFile.writeText(NewsListenJson.encodeToString(ClientErrorReport.serializer(), reportA))

        val apiClient = FakeApiClient(onReportClientError = {
            // apiClient への送信中（＝時間のかかる通信中）に新しいクラッシュが発生した状況を模す。
            crashFile.writeText(NewsListenJson.encodeToString(ClientErrorReport.serializer(), reportB))
        })
        val reporter = newReporter(apiClient)

        reporter.flush()

        assertEquals(listOf(reportA), apiClient.reportCalls)
        assertTrue(crashFile.exists())
        val remaining = NewsListenJson.decodeFromString(ClientErrorReport.serializer(), crashFile.readText())
        assertEquals(reportB, remaining)
    }

    @Test
    fun flushはデコード不能な永続化ファイルを破棄し送信しない() = runTest {
        crashFile.writeText("not valid json{{{")
        val apiClient = FakeApiClient(onReportClientError = { error("reportClientError は呼ばれないはず") })
        val reporter = newReporter(apiClient)

        reporter.flush()

        assertTrue(apiClient.reportCalls.isEmpty())
        assertFalse(crashFile.exists())
    }

    @Test
    fun flushは永続化ファイルが無ければ何もしない() = runTest {
        val apiClient = FakeApiClient(onReportClientError = { error("reportClientError は呼ばれないはず") })
        val reporter = newReporter(apiClient)

        reporter.flush()

        assertTrue(apiClient.reportCalls.isEmpty())
    }
}

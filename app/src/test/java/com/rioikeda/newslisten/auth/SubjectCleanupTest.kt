package com.rioikeda.newslisten.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * [SubjectCleanup] の手順列の実行を検証する。手順は記録と `delay` を持つ double で、
 * scope は runTest の scheduler に載せる（観測は `advanceUntilIdle()` の後。「待たない」だけ `runCurrent()` の後）。
 */
class SubjectCleanupTest {

    private val subjectA = Subject("u-1", "alice")
    private val subjectB = Subject("u-2", "bob")

    private fun TestScope.newCleanup(steps: List<CleanupStep>): SubjectCleanup =
        SubjectCleanup(steps, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    private fun recordingStep(name: String, log: MutableList<String>, body: suspend () -> Unit = {}) =
        CleanupStep(name) { subject ->
            log += "$name:${subject.username}"
            body()
        }

    @Test
    fun `android-contract-15 android-test-15 手順は宣言順に実行され全成功ならcleanupIncompleteはnull`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        val cleanup = newCleanup(listOf("a", "b", "c").map { recordingStep(it, log) })

        cleanup.start(subjectA)
        advanceUntilIdle()

        assertEquals(listOf("a:alice", "b:alice", "c:alice"), log)
        assertNull(cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `android-contract-15 android-test-15 手順の1つが失敗しても残りは実行されpartsに失敗した名前だけが入る`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        val cleanup = newCleanup(
            listOf(
                recordingStep("first", log),
                recordingStep("second", log) { throw RuntimeException("boom") },
                recordingStep("third", log),
            ),
        )

        cleanup.start(subjectA)
        advanceUntilIdle()

        assertEquals(listOf("first:alice", "second:alice", "third:alice"), log)
        assertEquals(CleanupIncomplete(setOf("second")), cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `android-contract-15 android-test-15 再実行で全成功するとnullに戻り後の失敗はpartsに残る`() = runTest {
        // verifies: android-contract-15
        val failures = ArrayDeque(listOf(true, false, true))
        val cleanup = newCleanup(
            listOf(
                CleanupStep("flaky") { if (failures.removeFirst()) throw RuntimeException("boom") },
                CleanupStep("steady") { },
            ),
        )

        cleanup.start(subjectA)
        advanceUntilIdle()
        assertEquals(CleanupIncomplete(setOf("flaky")), cleanup.cleanupIncomplete.value)

        cleanup.start(subjectA)
        advanceUntilIdle()
        assertNull(cleanup.cleanupIncomplete.value)

        cleanup.start(subjectA)
        advanceUntilIdle()
        assertEquals(CleanupIncomplete(setOf("flaky")), cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `android-contract-15 android-test-15 startは手順の完了を待たずに戻る`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        val cleanup = newCleanup(listOf(recordingStep("slow", log) { delay(1_000) }, recordingStep("after", log)))

        cleanup.start(subjectA)
        runCurrent()

        assertEquals(listOf("slow:alice"), log)
        assertNull(cleanup.cleanupIncomplete.value)
        advanceUntilIdle()
        assertEquals(listOf("slow:alice", "after:alice"), log)
    }

    @Test
    fun `android-contract-15 android-test-15 startは中断しない手順でも呼出元では実行せず戻ってから走る`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        val cleanup = newCleanup(listOf(recordingStep("quick", log)))

        cleanup.start(subjectA)

        assertEquals(emptyList<String>(), log)
        runCurrent()
        assertEquals(listOf("quick:alice"), log)
    }

    @Test
    fun `android-contract-15 android-test-15 2つのstartは交差せず到着順に直列で走る`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        val cleanup = newCleanup(
            listOf(
                recordingStep("one", log) { delay(500) },
                recordingStep("two", log) { delay(500) },
            ),
        )

        cleanup.start(subjectA)
        cleanup.start(subjectB)
        advanceUntilIdle()

        assertEquals(listOf("one:alice", "two:alice", "one:bob", "two:bob"), log)
    }

    @Test
    fun `android-contract-15 android-test-15 startの呼出順と実行順は一致し起動が入れ替わる dispatcher でもBはAの後に走る`() = runTest {
        // verifies: android-contract-15
        // 起動を後入れ先出しで処理する dispatcher（複数スレッドで起動順が入れ替わる状況の再現）。
        val pending = ArrayDeque<Runnable>()
        val lifo = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.addLast(block)
            }
        }
        val log = mutableListOf<String>()
        val cleanup = SubjectCleanup(
            listOf("one", "two").map { recordingStep(it, log) },
            CoroutineScope(SupervisorJob() + lifo),
        )

        cleanup.start(subjectA)
        cleanup.start(subjectB)
        while (pending.isNotEmpty()) pending.removeLast().run()

        assertEquals(listOf("one:alice", "two:alice", "one:bob", "two:bob"), log)
    }

    @Test
    fun `android-contract-15 android-test-15 CancellationExceptionは握らず後続を実行せずcleanupIncompleteを更新しない`() = runTest {
        // verifies: android-contract-15
        val log = mutableListOf<String>()
        var cancelNext = true
        val cleanup = newCleanup(
            listOf(
                recordingStep("first", log),
                recordingStep("second", log) { if (cancelNext) throw CancellationException("cancelled") },
                recordingStep("third", log),
            ),
        )

        cleanup.start(subjectA)
        advanceUntilIdle()

        assertEquals(listOf("first:alice", "second:alice"), log)
        assertNull(cleanup.cleanupIncomplete.value)

        // Mutex が解放されていれば、続く start は走る。
        cancelNext = false
        cleanup.start(subjectB)
        advanceUntilIdle()
        assertEquals(
            listOf("first:alice", "second:alice", "first:bob", "second:bob", "third:bob"),
            log,
        )
        assertNull(cleanup.cleanupIncomplete.value)
    }

    @Test
    fun `android-check-06 cleanupIncompleteが返したpartsをMutableSetへcastして書き換えても次の読みは変わらない`() = runTest {
        // verifies: android-contract-15, android-check-06
        val cleanup = newCleanup(listOf(CleanupStep("broken") { throw RuntimeException("boom") }))
        cleanup.start(subjectA)
        advanceUntilIdle()
        val parts = cleanup.cleanupIncomplete.value!!.parts

        attemptWrite(parts)

        assertEquals(setOf("broken"), cleanup.cleanupIncomplete.value!!.parts)
    }

    /** 書き換えを試みる。読み取り専用なら [UnsupportedOperationException] で弾かれるので握って次へ進む。 */
    private fun attemptWrite(set: Set<String>) {
        @Suppress("UNCHECKED_CAST")
        val mutable = set as MutableSet<String>
        try { mutable.add("x") } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
        try { mutable.clear() } catch (_: UnsupportedOperationException) { /* 期待どおり */ }
    }
}

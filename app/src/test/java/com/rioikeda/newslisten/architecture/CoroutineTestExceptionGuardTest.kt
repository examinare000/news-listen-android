@file:OptIn(ExperimentalCoroutinesApi::class)

package com.rioikeda.newslisten.architecture

import com.rioikeda.newslisten.architecture.fixture.failure.ThrowingDirectlyInScopeQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runner が所有者の内部 scope の未捕捉の例外を失敗として報告しても、kotlinx-coroutines-test の collector に例外が残って
 * 無関係な次の `runTest` を壊さない。
 */
class CoroutineTestExceptionGuardTest {

    private val world = FixtureWorld(
        anchor = ThrowingDirectlyInScopeQuery::class.java,
        packageName = "com.rioikeda.newslisten.architecture.fixture.failure",
        files = listOf("FailureFixtures.kt"),
        rows = listOf(probeQuery("ThrowingDirectlyInScopeQuery#observe")),
    )

    private val set = queryProbeSet(
        owner = "ThrowingDirectlyInScopeQuery",
        scenarios = listOf(scenarioA()),
        calls = listOf(CallSpec("ThrowingDirectlyInScopeQuery#observe")),
    ) { env -> ThrowingDirectlyInScopeQuery(env.apiClient, env.preferencesStore, env.newDispatcher()) }

    @Test(timeout = 60_000)
    fun 内部scopeの未捕捉の例外を失敗にした後も続くrunTestは壊れない() {
        // collector を有効にしておく（runTest が一度でも動いた JVM と同じ状態）。
        runTest { }

        val failure = assertThrows(ProbeRunFailure::class.java) { QueryProbeRunner.run(set, world.context) }
        assertEquals("内部 scope の例外は失敗として報告される", List(2) { FailureStage.BACKGROUND }, failure.trace!!.failures.map { it.stage })

        // 溜まった例外が残っていれば、次の runTest が UncaughtExceptionsBeforeTest で失敗する。
        runTest { }
    }

    @Test(timeout = 60_000)
    fun 実行単位の前から溜まっていた例外は取り除かずに戻す() {
        // collector を有効にしておく。
        runTest { }
        val leaked = IllegalStateException("他のテストが溜めた例外")
        val type = Class.forName("kotlinx.coroutines.test.internal.ExceptionCollector")
        type.getMethod("handleException", Throwable::class.java).invoke(type.getField("INSTANCE").get(null), leaked)

        CoroutineTestExceptionGuard.protect { }

        // 戻っていれば、次の runTest が溜まった例外で失敗する（失敗した時点で溜まりは空になる）。
        val error = assertThrows(Throwable::class.java) { runTest { } }
        val chain = generateSequence(error) { it.cause }.flatMap { sequenceOf(it) + it.suppressed.asSequence() }
        assertTrue("溜まっていた例外が失われた", chain.any { it === leaked })
    }
}

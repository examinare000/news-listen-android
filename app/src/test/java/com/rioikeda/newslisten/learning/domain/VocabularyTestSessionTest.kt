package com.rioikeda.newslisten.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 単語テストの状態機械（純粋な値）。coroutines を使わない JVM test。 */
class VocabularyTestSessionTest {

    private fun word(
        index: Int,
        meaning: String = "意味-$index",
        distractors: List<String> = listOf("誤答A-$index", "誤答B-$index", "誤答C-$index"),
    ) = VocabularyTestWord(
        vocabularyId = "v-$index",
        term = "term-$index",
        meaning = meaning,
        example = "example-$index",
        distractors = distractors,
    )

    private val identity: (List<String>) -> List<String> = { it }

    private fun failingShuffle(): (List<String>) -> List<String> = { error("shuffle は呼ばれないはず") }

    private fun started(count: Int) = VocabularyTestSession.started((1..count).map { word(it) })

    /** [knowns] の順に自己評価を答える。 */
    private fun assessed(count: Int, knowns: List<Boolean>, shuffle: (List<String>) -> List<String> = identity): VocabularyTestSession =
        knowns.fold(started(count)) { session, known -> session.assess(known, shuffle) }

    private fun retest(count: Int): VocabularyTestSession = assessed(count, List(count) { false })

    private fun submitting(): VocabularyTestSession = assessed(1, listOf(true))

    private fun everyPhase(): Map<VocabularyTestPhase, VocabularyTestSession> = mapOf(
        VocabularyTestPhase.Loading to VocabularyTestSession.loading(),
        VocabularyTestPhase.SelfAssessment to started(1),
        VocabularyTestPhase.Retest to retest(1),
        VocabularyTestPhase.Submitting to submitting(),
        VocabularyTestPhase.Result to submitting().submitted(),
        VocabularyTestPhase.Empty to VocabularyTestSession.started(emptyList()),
        VocabularyTestPhase.LoadError to VocabularyTestSession.loadFailed(),
        VocabularyTestPhase.SubmitError to submitting().submitFailed(),
    ).also { sessions ->
        sessions.forEach { (phase, session) -> assertEquals(phase, session.phase) }
    }

    private fun others(vararg excluded: VocabularyTestPhase) =
        everyPhase().filterKeys { it !in excluded }

    @Test
    fun `android-rule-learning-01 loadingは読みがすべて空のLoadingを返す`() {
        val session = VocabularyTestSession.loading()

        assertEquals(VocabularyTestPhase.Loading, session.phase)
        assertNull(session.currentWord)
        assertNull(session.progress)
        assertEquals(emptyList<String>(), session.currentChoices)
        assertEquals(emptyList<VocabularyTestAnswer>(), session.answers)
        assertFalse(session.isAnswerLocked)
        assertNull(session.retestFeedback)
        assertNull(session.summary)
    }

    @Test
    fun `android-rule-learning-01 startedは11語以上でも先頭10語までで自己評価を始める`() {
        val session = started(12)

        assertEquals(VocabularyTestPhase.SelfAssessment, session.phase)
        assertEquals(VocabularyTestProgress(1, 10), session.progress)
        assertEquals("term-1", session.currentWord?.term)
        assertEquals(10, session.answers.size)
        assertEquals((1..10).map { "v-$it" }, session.answers.map { it.vocabularyId })
    }

    @Test
    fun `android-rule-learning-01 startedは空の出題ならEmptyになる`() {
        val session = VocabularyTestSession.started(emptyList())

        assertEquals(VocabularyTestPhase.Empty, session.phase)
        assertNull(session.currentWord)
        assertNull(session.progress)
    }

    @Test
    fun `android-rule-learning-01 loadFailedはLoadErrorを返す`() {
        assertEquals(VocabularyTestPhase.LoadError, VocabularyTestSession.loadFailed().phase)
    }

    @Test
    fun `android-rule-learning-01 最後でない語のassessは次の語へ進みshuffleを呼ばない`() {
        var shuffleCount = 0

        val next = started(2).assess(true) { shuffleCount++; it }

        assertEquals(VocabularyTestPhase.SelfAssessment, next.phase)
        assertEquals(VocabularyTestProgress(2, 2), next.progress)
        assertEquals("term-2", next.currentWord?.term)
        assertEquals(0, shuffleCount)
    }

    @Test
    fun `android-rule-learning-01 知らないと答えた語だけを出題の順に再テストしshuffleは語ごとに1回呼ぶ`() {
        var shuffleCount = 0

        val session = assessed(3, listOf(false, true, false)) { shuffleCount++; it }

        assertEquals(VocabularyTestPhase.Retest, session.phase)
        assertEquals(VocabularyTestProgress(1, 2), session.progress)
        assertEquals("term-1", session.currentWord?.term)
        assertEquals(2, shuffleCount)
        val second = session.answerRetest("意味-1").advance()
        assertEquals(VocabularyTestProgress(2, 2), second.progress)
        assertEquals("term-3", second.currentWord?.term)
        assertEquals(2, shuffleCount)
    }

    @Test
    fun `android-rule-learning-01 選択肢は意味と誤答の候補3つまでから重複を除いてshuffleへ渡す`() {
        // (a) 誤答に重複: (listOf(meaning) + distractors.take(3)).distinct() = ["意味-1", "x", "y"]
        // (b) 誤答が意味と同じ: 同じ式で ["意味-1", "x", "y"]。4つ目の誤答 z は使わない
        val cases = listOf(
            listOf("x", "x", "y", "z"),
            listOf("意味-1", "x", "y", "z"),
        )
        cases.forEach { distractors ->
            val passed = mutableListOf<List<String>>()

            VocabularyTestSession.started(listOf(word(1, distractors = distractors)))
                .assess(false) { passed += it; it }

            assertEquals("distractors=$distractors", listOf(listOf("意味-1", "x", "y")), passed)
        }
    }

    @Test
    fun `android-rule-learning-01 選択肢はassessの時に確定しshuffleの結果をそのまま保つ`() {
        var shuffleCount = 0
        val session = VocabularyTestSession.started(listOf(word(1)))
            .assess(false) { shuffleCount++; it.reversed() }

        assertEquals(listOf("誤答C-1", "誤答B-1", "誤答A-1", "意味-1"), session.currentChoices)
        assertEquals(session.currentChoices, session.currentChoices)
        assertEquals(1, shuffleCount)
    }

    @Test
    fun `android-rule-learning-01 全語を知ってると答えたら再テストせずSubmittingへ進む`() {
        var shuffleCount = 0

        val session = assessed(2, listOf(true, true)) { shuffleCount++; it }

        assertEquals(VocabularyTestPhase.Submitting, session.phase)
        assertEquals(0, shuffleCount)
        assertNull(session.currentWord)
        assertEquals(
            listOf(VocabularyTestAnswer("v-1", true, null), VocabularyTestAnswer("v-2", true, null)),
            session.answers,
        )
    }

    @Test
    fun `android-dec-105 assessはSelfAssessment以外では同一instanceを返しshuffleを呼ばない`() {
        others(VocabularyTestPhase.SelfAssessment).forEach { (phase, session) ->
            assertSame("phase=$phase", session, session.assess(true, failingShuffle()))
        }
    }

    @Test
    fun `android-dec-105 answerRetestは正解でCorrectを確定し進みと選択肢を変えない`() {
        val before = retest(1)

        val after = before.answerRetest("意味-1")

        assertTrue(after.isAnswerLocked)
        assertEquals(RetestFeedback.Correct, after.retestFeedback)
        assertEquals(before.progress, after.progress)
        assertEquals(before.currentChoices, after.currentChoices)
    }

    @Test
    fun `android-dec-105 answerRetestは不正解で正解肢つきのIncorrectを確定する`() {
        val after = retest(1).answerRetest("誤答A-1")

        assertTrue(after.isAnswerLocked)
        assertEquals(RetestFeedback.Incorrect("意味-1"), after.retestFeedback)
    }

    @Test
    fun `android-dec-105 確定中のanswerRetestは同一instanceで二重回答を無視する`() {
        val locked = retest(1).answerRetest("意味-1")

        assertSame(locked, locked.answerRetest("誤答A-1"))
    }

    @Test
    fun `android-dec-105 answerRetestはRetest以外では同一instanceを返す`() {
        others(VocabularyTestPhase.Retest).forEach { (phase, session) ->
            assertSame("phase=$phase", session, session.answerRetest("意味-1"))
        }
    }

    @Test
    fun `android-dec-105 advanceは確定中の再テストを次の語へ進め確定を解く`() {
        val after = retest(2).answerRetest("意味-1").advance()

        assertEquals(VocabularyTestPhase.Retest, after.phase)
        assertEquals(VocabularyTestProgress(2, 2), after.progress)
        assertEquals("term-2", after.currentWord?.term)
        assertFalse(after.isAnswerLocked)
        assertNull(after.retestFeedback)
    }

    @Test
    fun `android-dec-105 advanceは最後の再テスト語でSubmittingへ進み確定を解く`() {
        val after = retest(1).answerRetest("意味-1").advance()

        assertEquals(VocabularyTestPhase.Submitting, after.phase)
        assertFalse(after.isAnswerLocked)
        assertNull(after.retestFeedback)
        assertNull(after.currentWord)
    }

    @Test
    fun `android-dec-105 advanceは確定中でなければ同一instanceを返す`() {
        everyPhase().forEach { (phase, session) ->
            assertSame("phase=$phase", session, session.advance())
        }
    }

    @Test
    fun `android-dec-105 retrySubmissionはSubmitErrorをSubmittingへ戻し回答を保つ`() {
        val failed = submitting().submitFailed()

        val retried = failed.retrySubmission()

        assertEquals(VocabularyTestPhase.Submitting, retried.phase)
        assertEquals(failed.answers, retried.answers)
    }

    @Test
    fun `android-dec-105 retrySubmissionはSubmitError以外では同一instanceを返す`() {
        others(VocabularyTestPhase.SubmitError).forEach { (phase, session) ->
            assertSame("phase=$phase", session, session.retrySubmission())
        }
    }

    @Test
    fun `android-dec-105 submittedは回答からsummaryを数えてResultになる`() {
        // v-1 は知ってる、v-2 は再テスト正解、v-3 は再テスト不正解
        val submitting = started(3)
            .assess(true, identity).assess(false, identity).assess(false, identity)
            .answerRetest("意味-2").advance()
            .answerRetest("誤答A-3").advance()

        val result = submitting.submitted()

        assertEquals(VocabularyTestPhase.Result, result.phase)
        assertEquals(VocabularyTestSummary(knownCount = 1, retestCount = 2, retestCorrectCount = 1), result.summary)
        assertEquals(
            listOf(
                VocabularyTestAnswer("v-1", true, null),
                VocabularyTestAnswer("v-2", false, true),
                VocabularyTestAnswer("v-3", false, false),
            ),
            result.answers,
        )
    }

    @Test
    fun `android-dec-105 submitFailedはSubmitErrorになり回答を保ちsummaryを持たない`() {
        val submitting = submitting()

        val failed = submitting.submitFailed()

        assertEquals(VocabularyTestPhase.SubmitError, failed.phase)
        assertEquals(submitting.answers, failed.answers)
        assertNull(failed.summary)
    }

    @Test
    fun `android-dec-105 submittedとsubmitFailedはSubmitting以外では同一instanceを返す`() {
        others(VocabularyTestPhase.Submitting).forEach { (phase, session) ->
            assertSame("submitted phase=$phase", session, session.submitted())
            assertSame("submitFailed phase=$phase", session, session.submitFailed())
        }
    }

    @Test
    fun `android-dec-105 遷移は元の値を変えない`() {
        data class Snapshot(
            val phase: VocabularyTestPhase,
            val progress: VocabularyTestProgress?,
            val answers: List<VocabularyTestAnswer>,
            val locked: Boolean,
        )

        fun VocabularyTestSession.snapshot() = Snapshot(phase, progress, answers, isAnswerLocked)

        val transitions: List<Pair<VocabularyTestSession, (VocabularyTestSession) -> VocabularyTestSession>> = listOf(
            started(2) to { it.assess(false, identity) },
            retest(2) to { it.answerRetest("意味-1") },
            retest(2).answerRetest("意味-1") to { it.advance() },
            submitting().submitFailed() to { it.retrySubmission() },
            submitting() to { it.submitted() },
            submitting() to { it.submitFailed() },
        )

        transitions.forEachIndexed { index, (before, transition) ->
            val expected = before.snapshot()
            transition(before)
            assertEquals("transition#$index", expected, before.snapshot())
        }
    }

    @Test
    fun `android-dec-105 VocabularyTestWordは5項目が等しければ等しく1項目でも違えば等しくない`() {
        val base = word(1)

        assertEquals(base, word(1))
        assertEquals(base.hashCode(), word(1).hashCode())
        assertNotEquals(base, VocabularyTestWord("v-x", "term-1", "意味-1", "example-1", base.distractors))
        assertNotEquals(base, VocabularyTestWord("v-1", "x", "意味-1", "example-1", base.distractors))
        assertNotEquals(base, VocabularyTestWord("v-1", "term-1", "x", "example-1", base.distractors))
        assertNotEquals(base, VocabularyTestWord("v-1", "term-1", "意味-1", "x", base.distractors))
        assertNotEquals(base, VocabularyTestWord("v-1", "term-1", "意味-1", "example-1", listOf("x")))
    }
}

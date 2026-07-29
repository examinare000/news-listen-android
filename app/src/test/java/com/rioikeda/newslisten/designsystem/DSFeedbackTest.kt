package com.rioikeda.newslisten.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class DSFeedbackTest {
    @Test
    fun `5語彙を触覚へ写像し音はcorrectとstreakUpだけにする`() {
        val fake = FakeFeedbackPlayer()
        var now = 0L
        val feedback = DSFeedback(fake, { true }, { true }) { now.also { now += 301 } }

        DSFeedbackVocabulary.entries.forEach(feedback::play)

        assertEquals(
            listOf(FeedbackSound.CORRECT, FeedbackSound.STREAK_UP),
            fake.sounds,
        )
        assertEquals(
            listOf(
                FeedbackHaptic.LIGHT,
                FeedbackHaptic.SOFT,
                FeedbackHaptic.MEDIUM,
                FeedbackHaptic.LIGHT,
                FeedbackHaptic.LIGHT,
            ),
            fake.haptics,
        )
    }

    @Test
    fun `同じ語彙は300ミリ秒未満の連打を間引く`() {
        val fake = FakeFeedbackPlayer()
        var now = 1_000L
        val feedback = DSFeedback(fake, { true }, { true }) { now }

        feedback.play(DSFeedbackVocabulary.CORRECT)
        now += 299
        feedback.play(DSFeedbackVocabulary.CORRECT)
        now += 1
        feedback.play(DSFeedbackVocabulary.CORRECT)

        assertEquals(2, fake.sounds.size)
        assertEquals(2, fake.haptics.size)
    }

    @Test
    fun `無効化した効果音とハプティクスは出力しない`() {
        val fake = FakeFeedbackPlayer()
        val feedback = DSFeedback(fake, { false }, { false }) { 1_000L }

        feedback.play(DSFeedbackVocabulary.CORRECT)

        assertEquals(emptyList<FeedbackSound>(), fake.sounds)
        assertEquals(emptyList<FeedbackHaptic>(), fake.haptics)
    }
}

private class FakeFeedbackPlayer : FeedbackPlayer {
    val sounds = mutableListOf<FeedbackSound>()
    val haptics = mutableListOf<FeedbackHaptic>()

    override fun playSound(sound: FeedbackSound) {
        sounds += sound
    }

    override fun performHaptic(haptic: FeedbackHaptic) {
        haptics += haptic
    }
}

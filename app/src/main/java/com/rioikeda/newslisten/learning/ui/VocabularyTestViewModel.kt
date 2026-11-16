package com.rioikeda.newslisten.learning.ui

import com.rioikeda.newslisten.learning.app.VocabularyTestCommands
import com.rioikeda.newslisten.learning.app.VocabularyTestView
import kotlinx.coroutines.flow.StateFlow

/** 単語テストの画面の状態の受け渡しと [VocabularyTestCommands] への中継だけを持つ（android-dec-107）。 */
class VocabularyTestViewModel(
    private val commands: VocabularyTestCommands,
) {
    val uiState: StateFlow<VocabularyTestView> = commands.vocabularyTest

    suspend fun load(): Unit = commands.startVocabularyTest()

    suspend fun assess(known: Boolean): Unit = commands.assess(known)

    suspend fun answerRetest(choice: String): Unit = commands.answerRetest(choice)

    suspend fun retrySubmission(): Unit = commands.retrySubmission()
}

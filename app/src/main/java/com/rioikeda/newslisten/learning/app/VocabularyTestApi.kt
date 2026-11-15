package com.rioikeda.newslisten.learning.app

/**
 * 単語テストの port。出題の取得（読み）と結果の送信（書き）を持つ。
 * 実装は失敗を `ApiException` のまま呼び手へ伝える。
 *
 * 戻り値と引数はこのファイルの部品だけ。DTO・JSON は出さない。
 */
interface VocabularyTestApi {
    suspend fun fetchVocabularyTestSession(): VocabularyTestSessionRecord

    suspend fun submitVocabularyTestResults(results: List<VocabularyTestAnswerRecord>)
}

data class VocabularyTestSessionRecord(
    val items: List<VocabularyTestItemRecord>,
)

data class VocabularyTestItemRecord(
    val vocabularyId: String,
    val term: String,
    val meaning: String,
    val example: String,
    val distractors: List<String>,
)

data class VocabularyTestAnswerRecord(
    val vocabularyId: String,
    val selfKnown: Boolean,
    val retestCorrect: Boolean?,
)

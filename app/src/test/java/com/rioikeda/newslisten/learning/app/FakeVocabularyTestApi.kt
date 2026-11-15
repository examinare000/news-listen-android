package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException

/**
 * 呼出を記録する [VocabularyTestApi] の fake。出題の応答と失敗は差し替えられる。
 * 送信は [failuresBeforeSubmitSuccess] 回だけ `ApiException.HttpError(500)` を投げてから成功する。
 */
class FakeVocabularyTestApi(
    items: List<VocabularyTestItemRecord> = emptyList(),
    private var failuresBeforeSubmitSuccess: Int = 0,
) : VocabularyTestApi {
    var onFetchVocabularyTestSession: suspend () -> VocabularyTestSessionRecord =
        { VocabularyTestSessionRecord(items) }
    var onSubmitVocabularyTestResults: suspend (List<VocabularyTestAnswerRecord>) -> Unit = {}

    /** `fetchVocabularyTestSession` を呼んだ回数。 */
    var fetchCount: Int = 0
        private set

    /** 失敗した呼出も含め、`submitVocabularyTestResults` に渡された内容。 */
    val submittedResults: MutableList<List<VocabularyTestAnswerRecord>> = mutableListOf()

    override suspend fun fetchVocabularyTestSession(): VocabularyTestSessionRecord {
        fetchCount++
        return onFetchVocabularyTestSession()
    }

    override suspend fun submitVocabularyTestResults(results: List<VocabularyTestAnswerRecord>) {
        submittedResults += results
        if (failuresBeforeSubmitSuccess > 0) {
            failuresBeforeSubmitSuccess--
            throw ApiException.HttpError(500)
        }
        onSubmitVocabularyTestResults(results)
    }
}

/** `term-N`・`意味-N`・`example-N`・誤答 `誤答A/B/C-N`、id `v-N` の出題 1 語。 */
fun vocabularyTestItem(index: Int) = VocabularyTestItemRecord(
    vocabularyId = "v-$index",
    term = "term-$index",
    meaning = "意味-$index",
    example = "example-$index",
    distractors = listOf("誤答A-$index", "誤答B-$index", "誤答C-$index"),
)

package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.core.ApiException
import com.rioikeda.newslisten.learning.domain.QuizGrade
import com.rioikeda.newslisten.learning.domain.QuizItemGrade
import com.rioikeda.newslisten.learning.domain.VocabularyKey
import com.rioikeda.newslisten.preferences.app.PreferencesSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 学習の入口（command と query）。既読の保存（android-cmd-learning-04）・クイズの送信（android-cmd-learning-01）・
 * 語彙の登録（android-cmd-learning-02）・登録済み語彙の読み（android-query-learning-03）を持つ。
 *
 * 語彙の key の読み書きと登録の失敗の知らせは [dispatcher] の上で直列化する。
 * 「全問に答えてから送る」は画面が保ち、ここでは検査しない（android-dec-103）。
 */
class LearningCommands(
    private val api: LearningApi,
    private val preferencesSync: PreferencesSync,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _registeredVocabularyKeys = MutableStateFlow<Set<VocabularyKey>>(emptySet())
    val registeredVocabularyKeys: StateFlow<Set<VocabularyKey>> = _registeredVocabularyKeys.asStateFlow()

    private val _savingVocabularyKeys = MutableStateFlow<Set<VocabularyKey>>(emptySet())
    val savingVocabularyKeys: StateFlow<Set<VocabularyKey>> = _savingVocabularyKeys.asStateFlow()

    private val _notice = MutableStateFlow<LearningNotice?>(null)
    val notice: StateFlow<LearningNotice?> = _notice.asStateFlow()

    /** [readAs] の主体が今の主体と違えば書かずに捨てる。捨てたことは返さない。 */
    suspend fun markAchievementsSeen(ids: Set<String>, readAs: ReadSubject) {
        preferencesSync.markAchievementsSeen(ids, readAs.subjectId)
    }

    /** 渡された [episodeId] だけで送る。失敗は [ApiException] のまま呼び手へ伝える。 */
    suspend fun submitQuiz(episodeId: String, answers: List<Int>): QuizGrade = withContext(dispatcher) {
        val record = api.submitQuizAnswers(episodeId, answers)
        QuizGrade(
            correctCount = record.correctCount,
            total = record.total,
            correctRate = record.correctRate,
            items = record.items.map { QuizItemGrade(it.questionIndex, it.selectedIndex, it.correctIndex, it.isCorrect) },
        )
    }

    /** 登録済みか登録中の語は送らない。[ApiException] は握って [notice] に出す。 */
    suspend fun registerVocabulary(episodeId: String, term: String): Unit = withContext(dispatcher) {
        val key = VocabularyKey.of(episodeId, term)
        if (key in _registeredVocabularyKeys.value || key in _savingVocabularyKeys.value) {
            return@withContext
        }
        _savingVocabularyKeys.value += key
        try {
            val saved = api.saveVocabulary(episodeId, term)
            _registeredVocabularyKeys.value += VocabularyKey.of(saved.episodeId, saved.term)
        } catch (_: ApiException) {
            _notice.value = LearningNotice.VocabularyFailed
        } finally {
            _savingVocabularyKeys.value -= key
        }
    }

    /** 呼び手の文脈で同期に消す。呼び手（`PodcastViewModel` の中継）が suspend でないため。 */
    fun dismissNotice() {
        _notice.value = null
    }

    /** 登録済み語彙を読み直して置き換える。失敗してもPodcast本体の利用を妨げない。 */
    suspend fun loadVocabularyRegistrations(): Unit = withContext(dispatcher) {
        try {
            _registeredVocabularyKeys.value = api.fetchVocabularySummary().items
                .mapTo(mutableSetOf()) { VocabularyKey.of(it.episodeId, it.term) }
        } catch (_: ApiException) {
            // best-effort: 未取得時はボタンを通常表示し、登録の冪等性へ委ねる。
        }
    }

    fun isVocabularyRegistered(episodeId: String, term: String): Boolean =
        VocabularyKey.of(episodeId, term) in _registeredVocabularyKeys.value
}

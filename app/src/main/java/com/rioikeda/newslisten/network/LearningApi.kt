package com.rioikeda.newslisten.network

import com.rioikeda.newslisten.model.VocabularyTestSessionResponse

/** 学習タブの読み取り境界。状態機械を既存の大きな ApiClient から独立して JVM テスト可能にする。 */
interface LearningApi {
    suspend fun fetchVocabularyTestSession(): VocabularyTestSessionResponse
}

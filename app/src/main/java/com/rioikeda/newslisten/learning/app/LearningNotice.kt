package com.rioikeda.newslisten.learning.app

/** 学習の操作が画面に出す知らせの種類（android-dec-100）。文言は種類が固定で持つ。 */
sealed interface LearningNotice {
    val message: String

    data object VocabularyFailed : LearningNotice {
        override val message = "語彙の登録に失敗しました"
    }
}

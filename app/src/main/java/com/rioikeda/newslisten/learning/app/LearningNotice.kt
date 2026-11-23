package com.rioikeda.newslisten.learning.app

/** 学習の操作が画面に出す知らせの種類（android-dec-100）。種類だけを持ち、文言は presentation の `learningNoticeMessage` が選ぶ（android-dec-144）。 */
sealed interface LearningNotice {
    data object VocabularyFailed : LearningNotice
}

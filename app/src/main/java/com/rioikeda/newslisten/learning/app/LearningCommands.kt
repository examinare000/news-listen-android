package com.rioikeda.newslisten.learning.app

import com.rioikeda.newslisten.preferences.app.PreferencesSync

/** 学習タブの書込（android-cmd-learning-04）。既読の保存を [PreferencesSync] に任せる。 */
class LearningCommands(private val preferencesSync: PreferencesSync) {
    /** [readAs] の主体が今の主体と違えば書かずに捨てる。捨てたことは返さない。 */
    suspend fun markAchievementsSeen(ids: Set<String>, readAs: ReadSubject) {
        preferencesSync.markAchievementsSeen(ids, readAs.subjectId)
    }
}

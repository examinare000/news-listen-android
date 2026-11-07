package com.rioikeda.newslisten.preferences

import com.rioikeda.newslisten.preferences.domain.PreferenceItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceItemTest {
    @Test
    fun `android-rule-prefs-04 主体依存の宣言は4件で共有仕様6_5の分類表と一致する`() {
        // verifies: android-rule-prefs-04
        // 関数名の '.' は JVM のメソッド名に使えないため、分類表の名前（共有仕様6.5）は失敗時のメッセージに持たせる。
        val spec = "android-rule-prefs-04 主体依存の宣言は4件で共有仕様6.5の分類表と一致する"
        val subjectScoped = PreferenceItem.entries.filter { it.subjectScoped }.map { it.key }.toSet()
        val deviceScoped = PreferenceItem.entries.filterNot { it.subjectScoped }.map { it.key }.toSet()

        assertEquals(
            spec,
            setOf("default_difficulty", "default_playback_speed", "weekly_goal_episodes", "seen_achievement_ids"),
            subjectScoped,
        )
        assertEquals(
            spec,
            setOf("article_open_mode", "time_format", "sfx_enabled", "haptics_enabled"),
            deviceScoped,
        )
        assertEquals(spec, 8, PreferenceItem.entries.size)
        assertEquals(spec, 8, PreferenceItem.entries.map { it.key }.toSet().size)
    }
}

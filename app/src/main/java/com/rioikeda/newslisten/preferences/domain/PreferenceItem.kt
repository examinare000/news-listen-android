package com.rioikeda.newslisten.preferences.domain

/**
 * 端末に保存する設定項目の宣言。[key] は保存先の key 名、[subjectScoped] は主体（ユーザー）に属する値か。
 *
 * 主体依存かどうかの宣言はここ 1 箇所に置く（android-rule-prefs-04、共有仕様 6.5 の分類表）。主体依存の項目は
 * 主体が離れるときに消し（shared-dec-02）、端末設定は残す。
 */
enum class PreferenceItem(val key: String, val subjectScoped: Boolean) {
    DEFAULT_DIFFICULTY("default_difficulty", true),
    DEFAULT_PLAYBACK_SPEED("default_playback_speed", true),
    ARTICLE_OPEN_MODE("article_open_mode", false),
    TIME_FORMAT("time_format", false),
    SFX_ENABLED("sfx_enabled", false),
    HAPTICS_ENABLED("haptics_enabled", false),
    WEEKLY_GOAL_EPISODES("weekly_goal_episodes", true),
    SEEN_ACHIEVEMENT_IDS("seen_achievement_ids", true),
}

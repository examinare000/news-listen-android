package com.rioikeda.newslisten.core

/**
 * サーバに保存された再生位置から、再生開始位置（秒）を決める。
 *
 * 規則（共有仕様 docs/design/shared-playback-spec.md §6.4、判定はこの順）:
 * 1. 総再生時間が正で、位置が末尾 2 秒窓に入っている → 0.0（ほぼ聴き終えたエピソードは頭から）
 * 2. 位置が正 → その位置
 * 3. それ以外（0・負値）→ 0.0
 *
 * WHY 純関数: 端末ごとの再生位置の解釈を 1 か所に固定し、RS 表の準拠テストで
 * 他プラットフォームと同じ結果になることを確認するため。
 */
fun resolveResumePosition(serverSeconds: Double, durationSeconds: Int): Double = when {
    durationSeconds > 0 && serverSeconds >= durationSeconds - RESUME_END_WINDOW_SECONDS -> 0.0
    serverSeconds > 0 -> serverSeconds
    else -> 0.0
}

private const val RESUME_END_WINDOW_SECONDS = 2

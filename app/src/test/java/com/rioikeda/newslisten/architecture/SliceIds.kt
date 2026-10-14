package com.rioikeda.newslisten.architecture

/**
 * 許可リスト・移行違反の表が `owner`・`fixTask`・`removeBy` に使える slice の識別子。
 *
 * WHY: 移行違反の表（[QueryWriteViolations]）が `ArchitectureStructureTest` の private な companion を
 * 参照できないため、値を変えずにここへ移した。
 */
object SliceIds {
    val all: Set<String> = setOf(
        "android-slice-playback-02", "android-slice-episode-01", "android-slice-episode-02", "android-slice-playback-03", "android-slice-playback-04", "android-slice-playback-05", "android-slice-ci-01", "android-slice-departure-01", "android-slice-departure-02",
        "android-slice-playapp-01", "android-slice-playapp-02", "android-slice-playapp-03", "android-slice-failure-01", "android-slice-prefs-01", "android-slice-prefs-02", "android-slice-account-01", "android-slice-account-02",
        "android-slice-learning-01", "android-slice-learning-02", "android-slice-learning-03", "android-slice-periphery-01", "android-slice-periphery-02", "android-slice-closure-01",
    )
}

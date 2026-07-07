// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // FCM (プッシュ通知・フェーズ9): google-services.json を読んで BuildConfig 等を生成する
    alias(libs.plugins.google.services) apply false
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // FCM (プッシュ通知・フェーズ9): app/google-services.json を読み込む
    alias(libs.plugins.google.services)
}

/**
 * Secrets (API_BASE_URL / API_KEY) を local.properties → 環境変数 → ダミー値の優先順で解決する。
 *
 * WHY: gradle.properties は git 追跡下のため実値を置けない。local.properties は .gitignore 済みで
 * 各開発者のローカル設定用（Android SDK の sdk.dir と同じ扱い）。CI ではダミー値でビルドが
 * green になることを保証し、実値は秘密情報として決してリポジトリに含めない。
 */
fun resolveSecret(propertyKey: String, envKey: String, default: String): String {
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }
    return localProperties.getProperty(propertyKey)
        ?: System.getenv(envKey)
        ?: default
}

android {
    namespace = "com.rioikeda.newslisten"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rioikeda.newslisten"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${resolveSecret("API_BASE_URL", "NEWS_LISTEN_API_BASE_URL", "http://localhost:8080")}\""
        )
        buildConfigField(
            "String",
            "API_KEY",
            "\"${resolveSecret("API_KEY", "NEWS_LISTEN_API_KEY", "ci-dummy-api-key")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // フェーズ14: R8/リソース縮小対応。本番署名鍵は Phase 7 で扱う予定のため、
            // ここでは暫定的に debug 署名を流用してビルド・検証を可能にする。
            // 本番リリース前に正式署名設定へ置き換え必須。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    // AndroidX
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // kotlinx.serialization: backend API 契約(schemas.py)の DTO ミラー用
    implementation(libs.kotlinx.serialization.json)

    // ApiClient: OkHttp 手書きクライアント + 認証 Interceptor
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    // Media3 ExoPlayer: 音声再生制御（フェーズ5）
    implementation(libs.androidx.media3.exoplayer)

    // Media3 Session: MediaSessionService + MediaStyle 通知（フェーズ7）
    implementation(libs.androidx.media3.session)

    // Firebase Cloud Messaging: プッシュ通知（FCM トークン登録・Podcast 生成完了通知。フェーズ9）
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // WHY: firebase-messaging が com.google.android.gms:play-services-base 経由で
    // androidx.fragment:1.1.0 を transitively 引き込み、MainActivity の
    // registerForActivityResult（フェーズ4・POST_NOTIFICATIONS 要求）に対して Android Lint の
    // InvalidFragmentVersionForActivityResult が発火する（ActivityResult API は fragment >= 1.3.0
    // を要求）。直接依存として新しい安定版を宣言し、解決バージョンを引き上げて回避する。
    implementation(libs.androidx.fragment)

    // Preferences DataStore: PreferencesStore の永続化実装（フェーズ10 P10 Task3）
    implementation(libs.androidx.datastore.preferences)

    // Splash Screen API: cold start でのブランドスプラッシュ表示（フェーズ15）
    implementation(libs.androidx.core.splashscreen)

    // Credential Manager: Passkey（WebAuthn）登録・認証（フェーズ17・issue #140 P17・ADR-066）
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

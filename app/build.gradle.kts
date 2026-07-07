import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

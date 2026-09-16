# verification-run（android・2026-09-16）

対象: `/Users/rio/git/news-listen/android`（`main` @ 89e8350、tracked dirty 0。untracked: `.kotlin/`、`app/src/debug/`）
実行者: router（メイン）＋ `test-execution` ロール（初回。JDK 不適合で未完、router が引き取り）

## 1. 環境
| 項目 | 値 |
|---|---|
| Gradle | 8.13 |
| AGP / Kotlin | 8.13.2 / 2.1.20（`gradle/libs.versions.toml`） |
| jvmTarget | 17（`app/build.gradle.kts:324-330`） |
| `JAVA_HOME` 既定 | Homebrew OpenJDK 26.0.2.1（`/usr/libexec/java_home -V` に 1 件のみ） |
| 使用 JDK | Android Studio 同梱 JBR（`/Applications/Android Studio.app/Contents/jbr/Contents/Home`） |
| CI | temurin 17、`./gradlew build --stacktrace`（`.github/workflows/ci.yml`）。同一コミットで success（`gh run list` 2026-09-13） |

## 2. 実行ログ

### V1 `./gradlew test`（JDK 26・test-execution ロール）— **未完（exit 1）**
`java.lang.IllegalArgumentException: 26.0.2.1`（Kotlin Gradle plugin の JavaVersion パーサが JDK 26 のバージョン文字列を解釈できない）。テストは 1 件も実行されず。

### V2 `JAVA_HOME=<JBR> ./gradlew test`（router）— **exit 1**
`compileDebugUnitTestKotlin` / `compileReleaseUnitTestKotlin` が `PodcastDecodingTest.kt:58` 等で `Unresolved reference 'NewsListenJson'`。同一パッケージの top-level val であり、同コミットの CI は green。**V1 の異常終了で汚れた増分コンパイルキャッシュ（`.kotlin/`・`app/build/`）が原因と判断**（再現条件: JDK 不適合の実行の直後に別 JDK で増分ビルド）。ログ: scratchpad `gradle-test.log`。

### V3 `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain`（router）— **exit 0、BUILD SUCCESSFUL in 2m 45s**
`app/build/test-results/testDebugUnitTest/*.xml` を集計（更新時刻 2026-09-16 11:11、V3 由来）:

| 集計 | 値 |
|---|---|
| suites（xml） | 67 |
| tests | **528** |
| failures + errors | **0** |
| skipped | 0 |

package 別 tests: network 127 / model 75 / podcast 64 / core 58 / feed 46 / settings 24 / auth 24 / account 20 / passkey 19 / preferences 17 / observability 13 / notification 12 / onboarding 10 / vocabulary 6 / engagement 5 / learning 4 / designsystem 3 / root 1。
上位 suite: PodcastViewModelTest 52 / OkHttpApiClientTest 46 / ApiEndpointTest 33 / PlaybackQueueConformanceTest 32 / FeedViewModelTest 30 / AuthViewModelTest 24 / SettingsViewModelTest 24 / RelativeTimeConformanceTest 17 / AudioCacheManagerTest 14。
（静的 `@Test` 数 534 との差 6 は release variant 未実行・parameterized 等の差ではなく、探索役の静的カウントに Fake 内の `@Test` 誤検出等を含む可能性。unexecuted: 差分の内訳特定）

### V4 lint — **unexecuted**
`./gradlew lint` は未実行（review の範囲外の時間コスト。CI の `build` が `lintVitalRelease` を含むため release 致命 lint は CI で green と推定）。planned: `JAVA_HOME=<JBR> ./gradlew lintDebug`。

### V5 conformance ID 機械照合（router）— pass
`grep -o 'Q-[0-9][0-9]' PlaybackQueueConformanceTest.kt | sort -u | wc -l` = 32。`RelativeTimeConformanceTest.kt` = RT-01〜RT-15 + RT-A01/A02（17）。spec §4 の 49 ID すべて存在、欠落 0。実行結果は V3 で 32 + 17 とも pass。

## 3. 定量計測（router の grep。定義行・import 行の扱いを明記）

### 3a. `ApiException.HttpError.code` の数値比較（main、定義行除く）
| file | 行 | 値 |
|---|---|---|
| auth/AuthViewModel.kt | 152 | 401 |
| account/AccountViewModel.kt | 122-124 | 400 / 422（when） |
| settings/SettingsViewModel.kt | 160 | 404 |
| engagement/ListeningStreakStore.kt | 56 | 404 |
| network/OkHttpApiClient.kt | 303 | 404 |
| podcast/QuizSheet.kt | 195 | 404（Composable） |
| onboarding/OnboardingViewModel.kt | 86 | 409 |
| passkey/PasskeyRegistrationViewModel.kt | 54 | 409 |
合計 8 ファイル 8 箇所（`OkHttpApiClient.kt:458` の `== 429` は生成側なので除外）。

### 3b. `_errorMessage.value = e.message`（例外 message を UI 文言へ）
podcast/PodcastViewModel.kt: 135, 212, 214, 251, 309（5）／ feed/FeedViewModel.kt: 89, 106, 204（3）。計 2 ファイル 8 箇所。

### 3c. コメントのみ／空の catch（main、`catch (` 直後の行が `//` か `}`）
PodcastViewModel 3 / CrashReporter 3 / AuthViewModel 3 / FcmTokenRegistrar 2 / KeystoreSessionStore 2 / ArticleOpener 2 / AppContainer 2 / ExoPlayerController 1 / PasskeyRegistrationViewModel 1 / PasskeyLoginViewModel 1 / OnboardingViewModel 1 / FeedViewModel 1 / DSFeedback 1。計 13 ファイル 23 箇所（いずれも WHY コメント付きの best-effort。無言の空 catch は 0）。

### 3d. `MutableStateFlow` 宣言数（main、上位）
PodcastViewModel 10 / InMemoryPreferencesStore 9 / AccountViewModel 9 / SettingsViewModel 8 / OnboardingViewModel 7 / FeedViewModel 7 / SessionsViewModel 6 / ExoPlayerController 5 / AuthViewModel 4。

### 3e. `mutableStateOf`（Screen 内ローカル状態、上位）
SettingsScreen 10 / QuizSheet 5 / AudioPlayerSection 5 / FeedScreen 5 / LoginScreen 4。

### 3f. ファイル行数（main、上位）
SettingsScreen.kt 1362 / FeedScreen.kt 592 / PodcastViewModel.kt 560 / AppContainer.kt 537 / AudioPlayerSection.kt 536 / LearningScreen.kt 505 / VocabularyTestScreen.kt 468 / OkHttpApiClient.kt 466。main 合計 12,555 行。

### 3g. テストダブル（test）
`ApiClient` 44 メソッド（abstract 35 + default 9）。Fake 9 ファイル・計 1,393 行:
settings/FakeApiClient 158（36 override）/ passkey/FakeApiClient 158（35）/ auth/FakeApiClient 146（35）/ notification/FakeNotificationApiClient 146（35）/ observability/FakeApiClient 142（35）/ feed/FakeFeedApiClient 156（35）/ podcast/FakePodcastApiClient 194（39）/ account/FakeApiClient 151（35）/ onboarding/FakeApiClient 142（35）。
production HTTP 経路（MockWebServer）を通る test: `OkHttpApiClientTest`（46）＋`OkHttpApiClientLearningTest`（7）= 53 / 528。`androidTest` ディレクトリ不在（0）。

### 3h. `BuildConfig` 参照（main）
`di/AppContainer.kt` のみ（import :10、:65 API_BASE_URL、:66 API_KEY、:171 VERSION_NAME）。他 0。

### 3i. `Log.*`（main）
KeystoreSessionStore.kt:51,82 / ExoPlayerController.kt:148 の 3 箇所、すべて固定文字列。

## 4. 未実行（unexecuted_validation）
- UV1: `./gradlew lintDebug`（owner: user、runner: test-execution）
- UV2: `./gradlew assembleRelease`（release 署名は debug 流用のため build のみ）
- UV3: 実機／エミュレータでの再生観測（resume 位置未適用・既定速度未適用・自動次再生失敗時の UI）。runner: user 手動。
- UV4: 静的 `@Test` 534 と実行 528 の差 6 の内訳

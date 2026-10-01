## android リファクタ A-T8b: Sources・Onboarding・Notifications の port とモデル。`AppContainer` を配線だけにする

## 1. 目的と、応える要求・設計・契約の ID

RSS ソースの管理・おすすめサイト・オンボーディング・生成の上限の表示・FCM の登録を、DTO と `ApiClient` の直接の利用から、domain の型と port（`SourcesApi`・`DeviceTokenApi`・`PushTokenSource`・`NotificationPermission`）へ移す。`model/FeaturedCategory.kt` から resource の依存を外し、`AppContainer` から FCM の断片を出す。これで TA-D2 の許可リストが空になる。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)、F-SET-01、F-FEED-04 ほか | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-5 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2・3・9、ADR-012（おすすめサイトとオンボーディング）、ADR-020（Push）、ADR-047（RSS の編集は admin） | `docs/adr/` |
| Spec | §5.6 Sources（`RssSourceEntry`・`FeaturedSiteEntry`・`FeaturedCategory`・`GenerationQuota`、TA-C-SR1・TA-Q-SR1、TA-R-SR1・SR2）、§5.7 Notifications（TA-C-NT1、port 3 つ）、TA-D2（4 ファイル＋`model/FeaturedCategory.kt`）・TA-D6（`AppContainer.kt:140-160` の FCM の断片）、§8.4「A-T8b」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |

## 2. 前提（着手条件）

- A-T8a の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い（A-T8a の文面は SG-D10 で決定済みで、本 slice はその内容に依らない。投入の順は A-T8a の後）。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| `model/` の resource | `grep -n '^import' app/src/main/java/com/rioikeda/newslisten/model/FeaturedCategory.kt` | `:3-4`（`androidx.annotation.StringRes`・`R`）。正規化 `:14,28-33` |
| 上限 0 = 無制限 | `grep -n 'limit == 0' app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` | `:511` |
| 409 / 404 の扱い | `grep -n 'Conflict\|NotFound\|409\|404' app/src/main/java/com/rioikeda/newslisten/{onboarding/OnboardingViewModel,settings/SettingsViewModel}.kt` | A-T4 の後は意味の variant（TA-R-SR1 は A-T4 で済み） |
| admin の判定 | `grep -n 'isAdminProvider\|isAdmin' app/src/main/java/com/rioikeda/newslisten/settings/SettingsViewModel.kt` | `:128` 付近（A-T6 の後は `Role.isAdmin` を読む） |
| FCM の断片 | `grep -n 'FirebaseMessaging\|POST_NOTIFICATIONS\|checkSelfPermission' app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt` | `:140-160` |
| `FcmTokenRegistrar` | `grep -n 'fun \|apiClient' app/src/main/java/com/rioikeda/newslisten/notification/FcmTokenRegistrar.kt` | `onAuthenticated`・`onNewToken`・`onLogout`（A-S4b の後は解除を呼ばない） |
| DTO の import | `grep -ln 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/{settings,onboarding,notification}/*.kt` | `SettingsViewModel`・`SettingsScreen`・`OnboardingViewModel`・`OnboardingScreen` |
| TA-D2 の残り | `Allowlist.kt` の TA-D2 の組 | 上の 4 ファイル＋`model/FeaturedCategory.kt` だけが残っていること（ほかは前の slice で消えている。残っていれば、その持ち主の slice を確かめる） |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt app/src/main/java/com/rioikeda/newslisten/onboarding/OnboardingScreen.kt` | **着手のときに全文を読む**（Spec §11） |

## 4. 対象の path と対象外

**作る（main）**: `settings/domain/`（`RssSourceEntry`・`FeaturedSiteEntry`・`FeaturedCategory`（正規化と表示順）・`GenerationQuota`（無制限 / 上限つき））、`settings/app/SourcesApi.kt`（port）、`network/SourcesApiAdapter.kt`、`notification/app/`（`DeviceTokenApi`・`PushTokenSource`・`NotificationPermission` の port）、`notification/infra/`（Firebase の token 取得と権限の判定の adapter）、`network/DeviceTokenApiAdapter.kt`。
**変える（main）**: `settings/SettingsViewModel.kt`・`settings/SettingsScreen.kt`・`onboarding/OnboardingViewModel.kt`・`onboarding/OnboardingScreen.kt`（domain とリードモデルを読む）、`model/FeaturedCategory.kt`（`FeaturedCategory` の正規化を domain へ出す。`model/` は `com.rioikeda.newslisten.*` と `android*` を import しない）、`notification/FcmTokenRegistrar.kt`（port を受ける）、`di/AppContainer.kt`（FCM の断片を adapter へ出し、配線だけにする）。カテゴリの表示名の resource は presentation の側へ。
**変える（test）**: `SettingsViewModelTest`・`OnboardingViewModelTest`・`FcmTokenRegistrarTest`（Fake を port に。期待値は変えない）、`FeaturedCategory` と `GenerationQuota` のテスト、`Allowlist.kt`。

**対象外**: RSS の検証の規則（backend）。通知の表示（`FcmTokenService` は認証の状態を見ない。Spec §11。挙動の変更なので扱わない）。通知からの再生の開始（新しい機能）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `RssSourceEntry`・`FeaturedSiteEntry`・`FeaturedCategory`（正規化と表示順）・`GenerationQuota`（上限 0 = 無制限の規則。TA-V4 の `limit == 0` の正本） |
| application | TA-C-SR1（`addSource`・`updateSource`・`removeSource`・`subscribeFeatured`・`finishOnboarding`。結果は無し。失敗は通知の種類。通信の応答が一覧の全体を返すので adapter が一覧の query を更新する）、TA-Q-SR1（`sources`・`featuredSites`（カテゴリの順に束ねた形）・`generationQuota`・`onboardingCompleted`）、TA-R-SR2（`Role.isAdmin` を読む）、TA-C-NT1（`onAuthenticated()`・`onNewToken(token)`・`onLogout()`） |
| port / adapter | `SourcesApi`・`DeviceTokenApi`（`ApiClient` を包む）、`PushTokenSource`・`NotificationPermission`（Firebase と Android の権限。`notification/infra/`） |
| composition root | `AppContainer` は生成と配線だけ（TA-D6）。Account の「主体が確立した」事象と `onAuthenticated()` を繋ぐ |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| ファイルの置き場（`settings/`・`onboarding/`・`notification/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-D2 の 4 ファイルと `model/FeaturedCategory.kt` が消え、**TA-D2 の許可リストが空**になる。TA-D6 の FCM の断片、TA-V4 の `limit == 0` が消える。増やさない。

## 7. 変わる挙動

無い。

## 8. 契約と検査

- TA-R-SR1・SR2 が正本へ。TA-D2 の許可リストが空。TA-D6（`di/AppContainer.kt` に業務の比較式と手順が無い）。
- TA-V6: `FeaturedSiteEntry` の束ねた形・`GenerationQuota` のカプセル化。
- 既存: 設定・オンボーディング・FCM の既存テストが期待値を変えずに green。A-S4b の「`onLogout()` は解除を呼ばない」が保たれる。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -rnE '^import (com\.rioikeda\.newslisten|android)' app/src/main/java/com/rioikeda/newslisten/model` = 0（集合 = `model/` の全 Kotlin）。
- `grep -rE '^import com\.rioikeda\.newslisten\.model\.' app/src/main/java/com/rioikeda/newslisten --include='*.kt' | grep -vE '/(network|model|observability)/' | wc -l` = 0（TA-D2 の import が adapter の外に無い）。完全修飾名も `grep -rn 'com\.rioikeda\.newslisten\.model\.' app/src/main --include='*.kt' | grep -v import | grep -vE '/(network|model|observability)/'` = 0。
- `grep -n 'FirebaseMessaging\|checkSelfPermission\|POST_NOTIFICATIONS' app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt` = 0。
- `grep -rn 'limit == 0' app/src/main` の一致が `settings/domain/` の 1 ファイルだけ（または 0 で別の表記）。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。TA-D2 の許可リストが空。ほかの組は増えていない。§9 の grep が期待値。
- 既存のテストの期待値が不変。

相互矛盾の突き合わせ: 「通知の表示の挙動を変えない」と「`FcmTokenRegistrar` を port にする」— 登録の経路だけを変え、`FcmTokenService` の表示は触らないので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 700 行（Spec §8.4）。1 PR。
- 返却事項: android-design §2「`core/` と `model/` は Android SDK に依存しない」が満たされた旨（Spec §10.2）。

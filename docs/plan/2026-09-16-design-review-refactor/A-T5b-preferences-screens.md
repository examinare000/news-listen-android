## android リファクタ A-T5b: Preferences の画面の切り替え（画面は `PreferencesView` を読んで command を呼ぶだけにし、port の直接の書込と選択肢の表を無くす）

> **2026-10-01 order の分割**: 旧 `A-T5-preferences.md`（1 ファイルに 2 PR）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T5b = 1 PR（画面の切り替え）**。前の PR は [A-T5a-preferences-types-and-sync.md](A-T5a-preferences-types-and-sync.md)（型と port・`PreferencesSync`・`PreferencesView`）。Spec の「A-T5」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

A-T5a で用意した `PreferencesView` と `PreferencesSync` の command を、設定画面・`DSFeedback`・`AppScaffold`・`MainActivity` から使う形に切り替える。presentation は `PreferencesStore` を受けず、選択肢の表を持たない。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (2)、F-SET-02・F-SET-04・F-LRN-09 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-3 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2〜4 | `docs/adr/110-refactor-target-domain-centered-onion-cqrs.md` |
| Spec | TA-R-PF1（週の目標の選択肢と換算の写し）、TA-R-PF2（難易度の添字と文言の対応）、TA-D4（`PreferencesStore` を受ける presentation 4 ファイルと setter 4 箇所）、§8.4「A-T5」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T15・T-T15、A-S4b の `clearSubjectScoped`（期待値を変えない） | 既存の Spec §4 |

## 2. 前提（着手条件）

- **A-T5a** の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値（A-T5a の後にずれる） |
|---|---|---|
| 値域の写し | `grep -rnF 'listOf(3, 5, 7, 10)' app/src/main`／`grep -rnF '/ 7.0' app/src/main` | `SettingsScreen.kt:1362`／`SettingsScreen.kt:305` |
| 難易度の対応 | `grep -n 'entries\[\|indexOfFirst' app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` | `:180,638`（添字と文言の並びの対応） |
| presentation が port を受ける | `grep -rln 'PreferencesStore' app/src/main/java/com/rioikeda/newslisten/{MainActivity.kt,AppScaffold.kt,designsystem,settings/SettingsScreen.kt}`／`grep -n 'preferencesStore\.set' app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` | 4 ファイル／4 箇所（`:245,259,271,278`） |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` | 1,362 行。**着手のときに全文を読む**（Spec §11: grep に掛からない規則が残っている可能性） |
| `PreferencesView` の公開面 | `grep -n 'val ' app/src/main/java/com/rioikeda/newslisten/preferences/app/PreferencesView.kt` | A-T5a の到達点を記録 |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**変える（main）**: `settings/SettingsScreen.kt`（選択肢の表と port の直接の呼出を無くし、`PreferencesView` を読む）、`MainActivity.kt`・`AppScaffold.kt`・`designsystem/DSFeedback.kt`（port を受けない。必要な値は `PreferencesView` かその部分）、`settings/SettingsViewModel.kt`（画面が呼ぶ command の入口が足りなければ足す。中身は `PreferencesSync` への委譲）、`di/AppContainer.kt`（画面へ port を渡す配線を外す）。
**変える（test）**: 画面の状態のテスト（あれば）・`SettingsViewModelTest`（期待値は変えない）、`Allowlist.kt`。

**対象外**: 型と port・`PreferencesSync`・`PreferencesView`（A-T5a で済んでいる）。DataStore の key（8 つ）と保存の形式。表示の文言と並び（変えない）。ファイルの package 移動（A-T9）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| presentation | 設定画面・`DSFeedback`・`AppScaffold`・`MainActivity` は `PreferencesView`（またはその部分の query）を読み、command を呼ぶ。週の目標の選択肢と 1 日あたりの換算は `WeeklyGoal`（`PreferencesView` の選択肢の list）から、難易度の並びと文言は `Difficulty` から取る |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| 画面が旧い選択肢の表を持ち、port（型つき）を直接呼ぶ | A-T5a | A-T5a | 画面が `PreferencesView` を読み command を呼ぶ | A-T5b（本 PR） |
| ファイルの置き場（`preferences/`・`settings/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-D4 の `PreferencesStore` を受ける 4 ファイルの組と setter 4 箇所、TA-V4 の `listOf(3, 5, 7, 10)`・`/ 7.0` が消える。増やさない。

## 7. 変わる挙動

無い。選択肢の値・並び・文言を変えない。

## 8. 契約と検査

- TA-R-PF1・PF2 の「今どこに居るか」が画面から消える（§9 の grep）。
- TA-D4: presentation のファイルが `PreferencesStore` を受けず、setter を呼ばない。
- 既存: CI-T15・T-T15・`clearSubjectScoped`・`PreferencesViewEncapsulationTest` が green。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（A-S3 の後。takt の実行中は worktree の外で走らせない）。
- `grep -rnF 'listOf(3, 5, 7, 10)' app/src/main` = 0、`grep -rnF '/ 7.0' app/src/main` の一致が `preferences/domain/WeeklyGoal.kt` だけ。
- `grep -rln 'PreferencesStore' app/src/main/java/com/rioikeda/newslisten/MainActivity.kt app/src/main/java/com/rioikeda/newslisten/AppScaffold.kt app/src/main/java/com/rioikeda/newslisten/designsystem app/src/main/java/com/rioikeda/newslisten/settings/SettingsScreen.kt` = 0（集合 = TA-D4 の 4 ファイル）。
- A-T5a の到達点を保つ: `grep -rn 'isSelectableSpeed\|PlaybackConstants.speeds' app/src/main` = 0、`grep -n 'syncPreferences\|syncSequence' app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt app/src/main/java/com/rioikeda/newslisten/settings/SettingsViewModel.kt` = 0、DataStore の key 文字列 8 つが着手前と同じ。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- 既存のテストの期待値が不変。
- TA-V10（PR の説明）: 「速度の段を足したら」「週の目標の選択肢を変えたら」の問いに、変わるファイル（`core/PlaybackSpeed.kt`／`preferences/domain/WeeklyGoal.kt`）を答える。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 350 行（A-T5 の全体 ≈ 800 行の後半。Spec §8.4）。
- 返却事項: 既存の Spec の保留（RF9）が画面まで解けた旨（Spec §10.2）。

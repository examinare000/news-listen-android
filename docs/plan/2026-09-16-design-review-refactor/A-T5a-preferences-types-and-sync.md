## android リファクタ A-T5a: Preferences の型（`PlaybackSpeed`・`WeeklyGoal`・`PreferenceItem`）・型つきの `PreferencesStore`・`PreferencesSync`・`PreferencesApi`・`PreferencesView`

> **2026-10-01 order の分割**: 旧 `A-T5-preferences.md`（1 ファイルに 2 PR。Spec §8.4「2 PR: 型と port ／ 画面の切り替え」）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T5a = 1 PR（型と port）**。後の PR は [A-T5b-preferences-screens.md](A-T5b-preferences-screens.md)（画面の切り替え）。Spec の「A-T5」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

Preferences の値域と既定値の正本を domain の型 1 箇所に置き、port を型つきにし、同期（サーバーとの往復）を application の `PreferencesSync` に集め、画面が読む `PreferencesView` を用意する。画面の切り替え（選択肢の表と port の直接の呼出を無くすこと）は A-T5b。DataStore の key は変えない。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-SET-02・F-SET-04・F-LRN-09 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-2・AQ-3・AQ-6 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2〜4、導出 A-25 | Spec §10.1 |
| 共有仕様 | §6.5 分類表（主体依存の 4 key）、§6.6（速度 8 段） | `docs/design/shared-playback-spec.md` |
| Spec | TA-M-PF、TA-C-PF1〜PF4・TA-Q-PF1、TA-R-PF1〜PF5、TA-R-PB5、§5.4「port」、§8.4「A-T5」、§10.2 の「保留 4 件（RF9）」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T15（値域外は拒否し直前の値を保つ）、T-T15、A-S4b の `clearSubjectScoped` と `PreferenceItem` | 既存の Spec §4、A-S2b1・A-S4b の order |

## 2. 前提（着手条件）

- A-T4 と **A-S4b** の android PR が main に merge 済み、かつ親ポインタが進んでいる（Spec §8.1。A-S4b は B-S5b 待ち。A-S4b が入るまで本 slice は投入しない）。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値（前の slice の到達点） |
|---|---|---|
| port の型 | `grep -nE 'val [a-zA-Z]+: StateFlow|suspend fun set' app/src/main/java/com/rioikeda/newslisten/preferences/PreferencesStore.kt` | 読み 8（`defaultDifficulty: String`・`defaultPlaybackSpeed: Double`・…・`weeklyGoalEpisodes: Int`・`seenAchievementIds`）、書き 8（`:43-60`）＋ A-S4b の `clearSubjectScoped` |
| 値域の写し | `grep -rnF 'listOf(3, 5, 7, 10)' app/src/main`／`grep -rnF 'setOf(3, 5, 7, 10)' app/src/main`／`grep -rnF '/ 7.0' app/src/main`／`grep -rn 'PLAYBACK_SPEEDS\|isSelectableSpeed' app/src/main` | `SettingsScreen.kt:1362`／`SettingsViewModel.kt:237`／`SettingsScreen.kt:305`／`PLAYBACK_SPEEDS` 0（A-S2c）・`isSelectableSpeed` 3 行（A-S2b1） |
| 既定値の写し | `grep -n 'weekly\|WEEKLY' app/src/main/java/com/rioikeda/newslisten/preferences/{DataStorePreferencesStore,InMemoryPreferencesStore}.kt` | `DataStorePreferencesStore.kt:119`・`InMemoryPreferencesStore.kt:21` |
| 同期 | `grep -n 'syncPreferences\|preferencesSyncFailed' app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt`／`grep -n 'syncSequence\|fun syncDefault\|fun setWeeklyGoal' app/src/main/java/com/rioikeda/newslisten/settings/SettingsViewModel.kt` | `AuthViewModel.kt:159-160,263-275` 付近／`SettingsViewModel.kt:177,187,193,217-231` |
| port の呼出元 | `grep -rln 'PreferencesStore' app/src/main` | 記録する（型を変えると追随が要るファイルの集合） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `core/PlaybackSpeed.kt`（8 段の値・既定 1.0。検査つきの生成。A-S2b1 の `isSelectableSpeed` を置き換える）、`preferences/domain/WeeklyGoal.kt`（3 / 5 / 7 / 10・既定 3、1 日あたりへの換算）、`preferences/domain/PreferenceItem.kt` は A-S4b で在る（型として広げる）、`preferences/app/PreferencesSync.kt`（後から来た操作を採る。TA-R-PF5）、`preferences/app/PreferencesView.kt`（現在の値と選択肢の list）、`preferences/app/PreferencesApi.kt`（port）、`network/PreferencesApiAdapter.kt`。
**変える（main）**: `preferences/PreferencesStore.kt`（型つき: 難易度 `Difficulty`・速度 `PlaybackSpeed`・週の目標 `WeeklyGoal`）、`preferences/DataStorePreferencesStore.kt`・`InMemoryPreferencesStore.kt`（型と key の写し。key 文字列は変えない）、`podcast/PlaybackConstants.kt`（`speeds`・`isSelectableSpeed` を `core/PlaybackSpeed.kt` へ）、`settings/SettingsViewModel.kt`（`PreferencesSync` へ委ね、TA-C-PF1・PF2 の command を持つ）、`auth/AuthViewModel.kt`（`syncPreferences` を `PreferencesSync` へ移す。Account は「主体が確立した」事象だけを出す）、`di/AppContainer.kt`（配線）。port を受けている presentation（`SettingsScreen.kt`・`MainActivity.kt`・`AppScaffold.kt`・`designsystem/DSFeedback.kt`）は、port の型の変更への**追随だけ**を行う（`Double` → `PlaybackSpeed` などの型の書き換え。選択肢の表と port の直接の呼出は残す。A-T5b で無くす）。
**変える（test）**: `PreferencesStore` の 2 実装のテスト、`SettingsViewModelTest`、`AuthViewModelTest`（同期の観測点の移動。期待値は変えない）、新しい型のテスト、`PreferencesViewEncapsulationTest`（TA-V6）、`Allowlist.kt`。

**対象外**: 画面の切り替え（A-T5b）。DataStore の key（8 つ）と保存の形式。難易度の値域（ADR-098 の 8 値への変更は別の決定）。`seen_achievement_ids` の既読の判定（A-T7a）。ファイルの package 移動のうち新規以外（A-T9）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `PlaybackSpeed`（TA-R-PB5・PF3。構築子は公開しない）、`WeeklyGoal`（TA-R-PF1。値域・既定値・1 日あたりの換算）、`Difficulty`（既存。TA-R-PF2 の値域・順序・ラベルの正本を 1 つに）、`PreferenceItem`（TA-R-PF4） |
| application | `PreferencesSync`: TA-C-PF1（`setDefaultDifficulty`・`setDefaultPlaybackSpeed`・`setWeeklyGoal`。端末に保存してサーバーへ送る。結果は失敗の意味）、TA-C-PF2（`setArticleOpenMode`・`setTimeFormat`・`setSfxEnabled`・`setHapticsEnabled`。端末だけ。結果は無し）、TA-C-PF3（`syncFromServer()`。主体の確立のとき。失敗は `preferencesSyncFailed`）、TA-C-PF4（`clearSubjectScoped()`）、TA-Q-PF1（`preferences: StateFlow<PreferencesView>`）。保存の競合は後から来た操作を採る（TA-R-PF5） |
| port / adapter | `PreferencesStore`（型つき。読みは `StateFlow`、書きは `suspend`）、`PreferencesApi` と `network/PreferencesApiAdapter.kt` |
| composition root | Account の「主体が確立した」事象と `PreferencesSync.syncFromServer()` を繋ぐ（Spec §5.3） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `PlaybackConstants.isSelectableSpeed`（A-S2b1） | A-S2b1 | A-S2b1 | `core/PlaybackSpeed.kt` に置き換わる | A-T5a（本 PR） |
| 画面が旧い選択肢の表を持ち、port（型つき）を直接呼ぶ | A-T5a | A-T5a | 画面が `PreferencesView` を読み command を呼ぶ | A-T5b |
| ファイルの置き場（`preferences/` 直下） | 各ファイル | — | 目標の package へ | A-T9 |

許可リスト: TA-V4 の `setOf(3, 5, 7, 10)`（`SettingsViewModel`）が消える。TA-D4 の `PreferencesStore` を受ける presentation 4 ファイルの組と setter 4 箇所、`listOf(3, 5, 7, 10)`・`/ 7.0` は A-T5b まで残る。増やさない。

## 7. 変わる挙動

無い。設定画面の速度の選択肢は A-S2c で 8 段（`PlaybackConstants.speeds`）になっており、本 slice は正本の置き場を移すだけ。DataStore の key は変えない。

## 8. 契約と検査

- TA-R-PF3・PF4・PF5 と TA-R-PF1・PF2 の値域・既定値の正本が domain の型に在る。
- CI-T15・T-T15 が `PlaybackSpeed` の生成で green（期待値不変）。A-S4b の `clearSubjectScoped`（4 key）と `PreferenceItemTest` が green。
- TA-V5: `PlaybackSpeed`・`WeeklyGoal` の構築子が公開でない。TA-V6: `PreferencesView`（実績の既読の集合を含む）のカプセル化。
- 同期の既存テスト（`AuthViewModelTest` の `syncPreferences`・`SettingsViewModelTest` の `syncSequence`）が観測点を移して期待値不変。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（A-S3 の後。takt の実行中は worktree の外で走らせない）。
- `grep -rn 'isSelectableSpeed\|PlaybackConstants.speeds' app/src/main` = 0（`core/PlaybackSpeed.kt` に置き換わる）。
- `grep -rnF 'setOf(3, 5, 7, 10)' app/src/main` の一致が `preferences/domain/WeeklyGoal.kt` だけ（または 0 で別の表記）。
- `grep -n 'syncPreferences\|syncSequence' app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt app/src/main/java/com/rioikeda/newslisten/settings/SettingsViewModel.kt` = 0。
- DataStore の key: `grep -n 'stringPreferencesKey\|doublePreferencesKey\|intPreferencesKey\|booleanPreferencesKey\|stringSetPreferencesKey' app/src/main/java/com/rioikeda/newslisten/preferences/DataStorePreferencesStore.kt` の key 文字列 8 つが着手前と同じ。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の許可リストの組（`setOf(3, 5, 7, 10)`）が消え、ほかは増えていない。§9 の grep が期待値。
- 既存のテストの期待値が不変（観測点を移したテストは PR に列挙）。
- 画面の差分は型の追随だけ（PR に画面のファイルごとの変更行の一覧を載せ、選択肢の表と port の呼出の数が着手前と同じであることを書く）。

相互矛盾の突き合わせ: 「DataStore の key を変えない」と「型つきの port」— adapter が key と型を写すので両立する。A-S4b の `PreferenceItem` の宣言（主体依存 4 key）を本 slice は変えない。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 450 行（A-T5 の全体 ≈ 800 行の前半。Spec §8.4）。
- 返却事項: 既存の Spec の保留（RF9: 週の目標・難易度の値域）を解いた旨（Spec §10.2）。CI-X01・X06 の扱いを親 docs へ。A-T5b の「着手前の前提点検」の値（画面に残る選択肢の表と port の呼出の行）。

## android リファクタ A-T6a: Account の domain（`AccountUser`・`Role`・`SubjectId`）と port `AuthApi`。認証状態が DTO を持つのをやめる

> **2026-10-01 order の分割**: 旧 `A-T6-account.md`（1 ファイルに 2 PR。Spec §8.4・旧 order「1 つ目の PR で domain と port、2 つ目の PR でリードモデルと画面」）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T6a = 1 PR（domain と認証の port）**。後の PR は [A-T6b-account-read-models.md](A-T6b-account-read-models.md)（`AccountApi`・セッションと passkey のリードモデルと画面）。Spec の「A-T6」は 2 つを合わせた呼び名。`AccountApi` は利用者が account・passkey の画面だけなので A-T6b に置いた。

## 1. 目的と、応える要求・設計・契約の ID

認証状態が DTO（`UserResponse`）を持つのをやめ、domain の `AccountUser` を持つ。admin の判定を `Role.isAdmin` の 1 箇所にし、`AppContainer` から比較式を無くす。`applyProfileUpdate` は表示名だけを受ける。認証の操作は port `AuthApi` を通す。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-ACC-01・02・07 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-6 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2〜4、ADR-104 決定 6・16（主体の id の形式） | `docs/adr/` |
| 共有仕様 | §6.3・§6.5、SL-01〜SL-10 | `docs/design/shared-playback-spec.md` |
| Spec | TA-M-AC（`AccountUser`・`Role`・`SubjectId`）、TA-C-AC1・AC2・TA-Q-AC1、TA-R-AC2・AC3、§5.3「port」の `AuthApi`、TA-D1（`AuthState.kt`）・TA-D2（`AuthState`・`AuthViewModel`）・TA-D6（`role == "admin"`）・TA-D7（`applyProfileUpdate(UserResponse)`）、TA-R-SR2、§8.4「A-T6」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T10〜T14・T21、CI-S0-*、SL-01〜SL-10（期待値を変えない） | 既存の Spec §4、共有仕様 §4.4 |

## 2. 前提（着手条件）

- **A-T5b** の android PR が main に merge 済み、かつ親ポインタが進んでいる（A-S4a・A-S4b・A-T5a も済んでいる）。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値（前の slice の到達点） |
|---|---|---|
| `AuthState` の保持値 | `grep -n 'UserResponse\|class Authenticated' app/src/main/java/com/rioikeda/newslisten/auth/AuthState.kt` | `:3` の import・`:21` `Authenticated(val user: UserResponse)`。A-S4 の `Subject`・`CleanupStep`・`CleanupIncomplete` が同居 |
| admin の比較 | `grep -rn 'role ==' app/src/main` | 2: `AppScaffold.kt:115`・`di/AppContainer.kt:416`（`== "` は AppContainer で 1） |
| `applyProfileUpdate` | `grep -rn 'applyProfileUpdate' app/src` | `auth/AuthViewModel.kt:398`（`UserResponse` を受ける）と呼出元 |
| DTO の import（auth） | `grep -ln 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/auth/*.kt` | `AuthState`・`AuthViewModel`（ほかにあれば記録） |
| `ApiClient` を受ける auth | `grep -rln 'ApiClient' app/src/main/java/com/rioikeda/newslisten/auth` | 記録する |
| `AuthViewModelTest` | `grep -c '@Test' app/src/test/java/com/rioikeda/newslisten/auth/AuthViewModelTest.kt` | 記録する（A-S4b・A-T5a の後の件数） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `auth/domain/AccountUser.kt`（`AccountUser`（`username`・`displayName`・`role`・`subjectId`）・`Role`（`isAdmin`）・`SubjectId`（形式 `[A-Za-z0-9_-]`。検査つきの生成））、`auth/app/AuthApi.kt`（`me`・`login`・`logout(token)`・passkey のログイン）、`network/AuthApiAdapter.kt`。
**変える（main）**: `auth/AuthState.kt`（`Authenticated(AccountUser)`。A-S4a の `Subject` の生成は `SubjectId` を使う）、`auth/AuthViewModel.kt`（`AuthApi` を受ける。`applyProfileUpdate(displayName)`）、`AppScaffold.kt`（`Role.isAdmin`）、`settings/SettingsViewModel.kt`（`isAdminProvider` が `Role.isAdmin` を読む。TA-R-SR2）、`di/AppContainer.kt`（比較式を無くし、`AuthApiAdapter` を配線）、`applyProfileUpdate` の呼出元（表示名を渡す）。
**変える（test）**: `AuthViewModelTest`（Fake を `AuthApi` の Fake に。期待値は変えない）、`SubjectIdTest`・`RoleTest`、`AccountUser` の TA-V6 のテスト、`Allowlist.kt`。

**対象外**: `AccountApi`・セッションと passkey のリードモデル・`AccountViewModel`・`SessionsViewModel`・passkey の ViewModel・`LoginScreen` の送れる条件・パスワード規則の案内文（A-T6b）。認証の遷移表・失効の競合（CI-S0-5・CI-S0-10）・後始末の手順（A-S4b で確定）。`KeystoreSessionStore` の保存の形式。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `AccountUser`・`Role`（TA-R-AC2）・`SubjectId`（TA-R-AC3。A-S4a の `Subject` の生成関数の形式の規則をここへ移す） |
| application | `AuthViewModel`（TA-C-AC1・AC2、TA-Q-AC1。遷移の導出 TA-R-AC1 は A-S4b のまま）。`applyProfileUpdate` は表示名だけを受ける（呼ぶ側が任意の `role` を入れられない。TA-D7） |
| port / adapter | `AuthApi`（戻り値は `AccountUser` などの domain の型）。adapter は `ApiClient` を包む（A-24） |
| composition root | `role ==` を書かない（TA-D6）。`currentSubject` は `AuthViewModel.currentSubjectId()` のまま |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `AuthState` が `UserResponse` を持つ（許可リスト） | A-S0 以前 | 既存 | `AccountUser` に替わる | A-T6a（本 PR） |
| `Subject` の形式の規則が `auth/AuthState.kt`（A-S4a） | A-S4a | A-S4a | `SubjectId` へ | A-T6a（本 PR） |
| account・passkey の ViewModel が `ApiClient` と DTO を直接受ける | 既存 | 既存 | `AccountApi` とリードモデル | A-T6b |
| ファイルの置き場 | 各ファイル | — | `auth/domain/`・`auth/app/`・`auth/ui/` へ | A-T9 |

許可リスト: TA-D1 の `auth/AuthState.kt`、TA-D2 の `AuthState`・`AuthViewModel`、TA-D6 の比較式、TA-D7 の DTO を受ける公開操作、TA-V4 の `role ==` 2 件が消える。増やさない。

## 7. 変わる挙動

無い。CI-T10〜T14・T21 と SL-01〜SL-10 の期待値を変えない。

## 8. 契約と検査

- TA-R-AC2・AC3 の「今どこに居るか」が正本へ（§9 の grep）。
- TA-V3: `di/AppContainer.kt` の `== "` が 1 → 0。TA-V5: `SubjectId` の構築子が公開でない。TA-V6: `AccountUser` のカプセル化。
- 既存: CI-T10〜T14・T21・CI-S0-*・SL-* のテストが期待値を変えずに green（テスト名の行 ID を保つ）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -rn 'role ==' app/src/main` の一致が `auth/domain/AccountUser.kt`（`Role` の中）だけ。`grep -n '== "' app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt` = 0。
- `grep -rn 'UserResponse' app/src/main/java/com/rioikeda/newslisten/auth app/src/main/java/com/rioikeda/newslisten/AppScaffold.kt` = 0。
- `grep -rn 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/auth` = 0（集合 = `auth` の全 Kotlin）。
- `grep -n 'fun applyProfileUpdate' app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt` の引数が表示名（`String`）。
- `grep -rn 'code == 401' app/src/main | wc -l` = 2、`grep -rn 'error("' app/src/main | wc -l` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の本 PR の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- 既存のテストの期待値が不変（Fake の型を直したテストは PR に列挙）。

相互矛盾の突き合わせ: 「認証の遷移と後始末を変えない（A-S4b で確定）」と「`AuthState` の保持値を替える」— 保持値の型だけを替え、遷移関数と `onSubjectLeave`・`onSubjectResolved` の呼出を変えないので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 450 行（A-T6 の全体 ≈ 900 行の前半。Spec §8.4）。
- 返却事項: 既存の Spec §3.2 の `AuthState` の型の記述を改める旨。iOS・web と port の名前（`AuthApi`）を親で並べる旨（Spec §11）。

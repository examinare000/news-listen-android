## android リファクタ A-T6b: port `AccountApi` と、セッション・passkey のリードモデルと画面。ログインの送れる条件とパスワード規則の案内の置き場

> **2026-10-01 order の分割**: 旧 `A-T6-account.md`（1 ファイルに 2 PR）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T6b = 1 PR（リードモデルと画面）**。前の PR は [A-T6a-account-domain-and-auth-port.md](A-T6a-account-domain-and-auth-port.md)（`AccountUser`・`Role`・`SubjectId`・`AuthApi`）。Spec の「A-T6」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

account・セッション・passkey の ViewModel が `ApiClient` と DTO を直接受けるのをやめ、port `AccountApi` とリードモデル（`AccountSummary`・`SessionRow`・`PasskeyRow`）を通す。ログインの送れる条件を ViewModel の 1 箇所にし、パスワード規則の案内文を presentation の文言 1 箇所にする。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-ACC-03・04、F-PKY-01〜03 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-6 | `docs/design/architecture.md` §2 |
| 決定 | ADR-110 決定 2〜4、ADR-101（パスワード規則は backend 正本） | `docs/adr/` |
| Spec | TA-M-AC（リードモデル）、TA-C-AC3〜AC5・TA-Q-AC2、TA-R-AC4〜AC6、§5.3「port」の `AccountApi`、TA-D2（`SessionsViewModel`・passkey の 3 つ）、§8.4「A-T6」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T10〜T14・T21、CI-S0-*（期待値を変えない） | 既存の Spec §4 |

## 2. 前提（着手条件）

- **A-T6a** の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値（前の slice の到達点） |
|---|---|---|
| DTO の import（account・passkey） | `grep -ln 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/{account/SessionsViewModel.kt,passkey/*.kt}` | `SessionsViewModel`・passkey の 3 つ（Spec §8.4） |
| `ApiClient` を受ける application | `grep -rln 'ApiClient' app/src/main/java/com/rioikeda/newslisten/{account,passkey}` | 記録する |
| パスワード規則の文言 | `grep -n '12' app/src/main/res/values/strings.xml \| head`／`grep -n 'PASSWORD_STRENGTH' app/src/main/java/com/rioikeda/newslisten/account/AccountViewModel.kt` | `strings.xml:163` と `AccountViewModel.kt:140` に同じ文（TA-R-AC5） |
| ログインの条件 | `grep -n 'isNotBlank\|isBlank' app/src/main/java/com/rioikeda/newslisten/auth/{AuthViewModel,LoginScreen}.kt` | `AuthViewModel.kt:287-290`・`LoginScreen.kt:159`（TA-R-AC4） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `account/app/AccountApi.kt`（表示名・パスワード・セッション・passkey の登録と一覧・オンボーディングの状態）、リードモデル（`AccountSummary`・`SessionRow`・`PasskeyRow`）、`network/AccountApiAdapter.kt`。
**変える（main）**: `account/AccountViewModel.kt`・`SessionsViewModel.kt`、`passkey/*ViewModel.kt` の 3 つ、`auth/LoginScreen.kt`（送れる条件を ViewModel から読む）、`auth/AuthViewModel.kt`（TA-R-AC4 の送れる条件の判定 1 箇所）、パスワード規則の案内文（presentation の文言 1 箇所に）、`di/AppContainer.kt`（`AccountApiAdapter` を配線）。
**変える（test）**: `AccountViewModelTest`・`SessionsViewModelTest`・passkey の各テスト（Fake を port の Fake に。期待値は変えない）、`SessionRow`・`PasskeyRow` の TA-V6 のテスト、`Allowlist.kt`。

**対象外**: `AccountUser`・`Role`・`SubjectId`・`AuthApi`（A-T6a で済んでいる）。認証の遷移表・失効の競合・後始末の手順（A-S4b で確定）。パスワード規則の検査を Android に足すこと（TA-R-AC5: 正本は backend。案内の文言だけ）。`KeystoreSessionStore` の保存の形式。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| application | `AccountViewModel`（TA-C-AC3。失敗は意味の型から文言を選ぶ）、`SessionsViewModel`（TA-C-AC4。`revokeOtherSessions` は receipt = 失効させた件数。TA-Q-AC2）、passkey（TA-C-AC5・TA-Q-AC2）、`AuthViewModel` の送れる条件（TA-R-AC4） |
| port / adapter | `AccountApi`（戻り値は `AccountUser` などの domain の型か、`SessionRow`・`PasskeyRow` の部品）。adapter は `ApiClient` を包む（A-24）。既に無いセッションの失効は成功（TA-R-AC6。adapter が写す） |
| presentation | `LoginScreen` は送れる条件を ViewModel から読む。パスワード規則の案内文は文言 1 箇所 |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| account・passkey の ViewModel が `ApiClient` と DTO を直接受ける | 既存 | 既存 | `AccountApi` とリードモデル | A-T6b（本 PR） |
| ファイルの置き場 | 各ファイル | — | `account/`・`passkey/` の目標の package へ | A-T9 |

許可リスト: TA-D2 の `SessionsViewModel`・passkey の 3 ファイルが消える。増やさない。

## 7. 変わる挙動

無い。CI-T10〜T14・T21 の期待値を変えない。案内の文言は変えない（置き場だけ 1 箇所にする）。

## 8. 契約と検査

- TA-R-AC4〜AC6 の「今どこに居るか」が正本へ（§9 の grep）。
- TA-V6: `SessionRow`・`PasskeyRow` のカプセル化。
- 既存: CI-T10〜T14・T21・CI-S0-* のテストが期待値を変えずに green（テスト名の行 ID を保つ）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -rn 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/{auth,account,passkey}` = 0（集合 = 3 package の全 Kotlin。`auth` は A-T6a で 0）。
- `grep -n 'isNotBlank\|isBlank' app/src/main/java/com/rioikeda/newslisten/auth/LoginScreen.kt` = 0（送れる条件は ViewModel の 1 箇所）。
- パスワード規則の案内文が 1 箇所: `grep -rn 'PASSWORD_STRENGTH' app/src/main/java` の一致が文言の参照だけで、文の写しが `AccountViewModel.kt` に無い（§3 の 2 箇所 → 1 箇所。PR に前後の grep を貼る）。
- A-T6a の到達点を保つ: `grep -rn 'role ==' app/src/main` の一致が `auth/domain/AccountUser.kt` だけ、`grep -n '== "' app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt` = 0。
- `grep -rn 'code == 401' app/src/main | wc -l` = 2、`grep -rn 'error("' app/src/main | wc -l` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- 既存のテストの期待値が不変（Fake の型を直したテストは PR に列挙）。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 450 行（A-T6 の全体 ≈ 900 行の後半。Spec §8.4）。
- 返却事項: iOS・web と port の名前（`AccountApi`）を親で並べる旨（Spec §11）。

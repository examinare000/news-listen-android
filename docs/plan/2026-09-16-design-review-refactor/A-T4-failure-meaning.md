## android リファクタ A-T4: 失敗の意味の型を `core/` へ移し、HTTP の code の比較を adapter へ寄せる

## 1. 目的と、応える要求・設計・契約の ID

`ApiException` を `core/ApiException.kt` へ移して意味の variant を足し、HTTP の code を意味へ写すのを adapter だけにする。application と presentation は code を比べない（TA-D9）。文言と挙動は変えない。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1) | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-1・AQ-5 | `docs/design/architecture.md` §2・§7 |
| 決定 | ADR-110 決定 3、導出 A-24 | Spec §10.1 |
| Spec | §5.8（失敗の意味の variant: `Unauthorized`・到達不能・`RateLimited`・見つからない・競合・入力が受け付けられない・サーバーの障害・応答が読めない）、TA-D3（`network.ApiException` の import）・TA-D9（code の比較 6 箇所）、TA-R-SR1（409 は成功扱い）・TA-R-LN8（404 の意味）・TA-R-AC6、§8.4「A-T4」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T10・CI-T11・CI-T21（`Unauthorized` の写像）、`code == 401` = 2 | 既存の Spec §4、README「grep oracle」 |

## 2. 前提（着手条件）

- A-T3a2（A-T3a の後半）の android PR が main に merge 済み、かつ親ポインタが進んでいる。技術的な依存は A-T3a1・A-T3a2 だけだが、投入の順は Spec §8.1 の表のとおり A-T3b の後（同じ submodule は 1 本ずつ）。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| code の比較（`network/` の外） | `grep -rnE 'e\.code|\.code (==|!=)' app/src/main/java/com/rioikeda/newslisten --include='*.kt' \| grep -v '/network/' \| grep -v 'it\.code\|entries'` | 6 箇所: `account/AccountViewModel.kt:122`（`when (e.code)` 400 / 422）・`settings/SettingsViewModel.kt:160`（`!= 404`）・`engagement/ListeningStreakStore.kt:56`（`!= 404`）・`onboarding/OnboardingViewModel.kt:86`（`== 409`）・`passkey/PasskeyRegistrationViewModel.kt:54`（`== 409`）・`podcast/QuizSheet.kt:195`（`== 404`）。`Difficulty`・`TimeFormat`・`ArticleOpenMode` の `it.code ==` は列挙の code で対象外 |
| `ApiException` の import | `grep -rln 'network.ApiException' app/src/main` | ファイル集合を記録（TA-D3 の内数） |
| variant | `grep -n 'class \|object ' app/src/main/java/com/rioikeda/newslisten/network/ApiException.kt` | 現行の variant を記録（`Unauthorized`・`HttpError(code)`・`NetworkError`・`DecodingError` など） |
| 写像の場所 | `grep -n 'fun validateResponse\|code == 401' app/src/main/java/com/rioikeda/newslisten/network/OkHttpApiClient.kt` | `validateResponse` と `:459` |
| oracle | `grep -rn 'code == 401' app/src/main \| wc -l` | 2 |

## 4. 対象の path と対象外

**変える（main）**: `network/ApiException.kt` → `core/ApiException.kt`（名前は保つ。package だけ移す）、`network/OkHttpApiClient.kt`（`validateResponse` が意味の variant へ写す）、code を比べている 6 ファイル（意味の variant の捕捉に替える）、`ApiException` を import する全ファイル（import の書き換え）。
**変える（test）**: `OkHttpApiClientTest`（status → variant の表）、import の書き換え、`Allowlist.kt`。

**対象外**: 文言（各画面・ViewModel の固定文言はそのまま）。`= e.message` の除去（A-T3b・A-T8a）。port の新設（A-T5〜A-T8b）。`AuthInterceptor` の 401 の発火条件（`code == 401` の 2 箇所は adapter の中なので残す）。

## 5. 変更の責務

| 層 | 変えること |
|---|---|
| domain（共有） | `core/ApiException.kt`: 既存の variant に、意味の variant（到達不能・`RateLimited`・見つからない・競合・入力が受け付けられない・サーバーの障害・応答が読めない）を足す。HTTP の code を公開の判定材料にしない |
| adapter | `OkHttpApiClient.validateResponse` が status を意味の variant へ写す（401 → `Unauthorized`、404 → 見つからない、409 → 競合、400・422 → 入力が受け付けられない（`AccountViewModel` の 400 と 422 の区別は、入力の不受理の中の理由として保つ）、429 → `RateLimited`、5xx → サーバーの障害）。既に無いセッションの失効を成功とする扱い（TA-R-AC6。`OkHttpApiClient.kt:303`）は変えない |
| application・presentation | 6 箇所が意味の variant を捕捉する。409 を成功として扱う（TA-R-SR1）・404 の意味（TA-R-LN8）は現行と同じ結果 |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `HttpError(code)` の variant（未分類の status 用に残す） | `core/ApiException.kt` | 既存 | 呼出元が code を比べない状態が保たれる限り残してよい（TA-D9 は比較を禁じ、型の存在は禁じない） | — |
| `QuizSheet.kt` の送信と失敗の分類（画面） | presentation | 既存 | `submitQuiz` の command | A-T7b |

許可リスト: TA-D9 の code の比較 6 → 0、TA-D3 の `network.ApiException` の import の組が消える。増やさない。

## 7. 変わる挙動

無い（文言と挙動は変えない）。

## 8. 契約と検査

- TA-D9: code の比較が `network/` の外で 0（TA-V4 の式「`HttpError` の `code` の比較」）。
- 写像の表駆動テスト（`OkHttpApiClientTest`。status 401・400・404・409・422・429・500・503 → variant）。テスト名に `TA-D9`。
- 6 箇所の呼出元の既存テスト（着手前に `grep -rln 'AccountViewModel\|SettingsViewModel\|ListeningStreakStore\|OnboardingViewModel\|PasskeyRegistrationViewModel\|QuizSheet\|QuizPresentation' app/src/test` で集合を確定して記録する）が期待値を変えずに green（Fake が投げる例外を意味の variant に直すだけ）。
- CI-T10・T11・T21 と `code == 401` = 2 が保たれる。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（A-S3 の前なら `JAVA_HOME=<JBR>`。takt の実行中は worktree の外で走らせない）。
- `grep -rnE 'e\.code|\.code (==|!=)' app/src/main/java/com/rioikeda/newslisten --include='*.kt' | grep -v '/network/' | grep -v 'it\.code\|entries'` = 0（集合 = main の全 Kotlin から `network/` を除く。`it.code` は列挙の code で除く）。
- `grep -rn 'network.ApiException' app/src` = 0。`ls app/src/main/java/com/rioikeda/newslisten/core/ApiException.kt` が在る。
- `grep -rn 'code == 401' app/src/main | wc -l` = 2。`grep -rn 'error("' app/src/main | wc -l` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§9 の grep が期待値。許可リストは減るだけ。
- 6 箇所の呼出元の既存テストの期待値が不変（変えたのは Fake の投げる型だけ）。

相互矛盾の突き合わせ: 「`code == 401` = 2 を保つ」と「code の比較を `network/` の外で 0」— 2 箇所はどちらも `network/` の中なので両立する。
- **公開面**（NFR-10・AQ-6。2026-10-01 追加: 新しい domain・リードモデルの型を作る slice は、その型を公開面の検査の対象に足す。Spec §7 TA-V5・TA-V6）: `core/ApiException.kt` の意味の variant（domain）を TA-V5（静的: 公開の `var` が無く、property が `val`）の対象に入れる（A-T1 の対応表で `core/` は domain）。variant は collection を持たないので TA-V6 の追加は無い（持たせる場合は TA-V6 に足す）。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 400 行（Spec §8.4）。1 PR。
- 返却事項: 意味の variant の一覧を Spec §5.8 に照合した結果。iOS の `S1-failure-meaning` と variant の名前を親で並べる旨（Spec §11）。

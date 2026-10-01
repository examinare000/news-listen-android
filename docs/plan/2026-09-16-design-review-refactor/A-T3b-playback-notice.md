## android リファクタ A-T3b: 再生の知らせを `PlaybackNotice` にし、例外の message を画面に出すのをやめる（SG-D6）

> **決定 SG-D6**（2026-10-01 user 採用。Spec §10.3 の 1・2 の推奨 (a)。台帳への登録は親 docs が行う）: 再生の失敗の固定文言は、取得の失敗「エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください」、再生エンジンの失敗「再生できませんでした。もう一度お試しください」。生成の失敗の識別子（`generation_failed` など）は文言へ写す。web の toast の写像（W-S4a の `failureMessage`）は 2026-10-01 時点で実装が無いので、識別子ごとの文言は本 order の表（§5）で決める。Spec §8.1 の「判断待ち」は本決定で解け、本 slice は ready の適用 slice である。
>
> **決定 SG-D11**（2026-10-01 user 判断。SG-D6 の補足。台帳 §5）: Android の中で記事（A-T8a の SG-D10）と規則をそろえる。再生の取得の失敗でも、通信の失敗（`ApiException.NetworkError`）は「オフラインです。接続を確認してから、もう一度お試しください」に分け、SG-D6 の取得の失敗の文言はそれ以外の失敗に使う。iOS と web の文言は変えない。
>
> **補正（2026-10-01）**: SG-D11 に合わせ、§5 の文言の表に `FetchOffline`（通信の失敗）の 1 行を足し、`FetchFailed` をそれ以外の失敗に限った。§7・§8・§10 の行数と決定 ID を合わせた。

## 1. 目的と、応える要求・設計・契約の ID

`errorMessage: StateFlow<String?>`（例外の `message` がそのまま入る）を、知らせの種類のリードモデル `PlaybackNotice` に替え、文言は presentation が種類から選ぶ。`podcast/` から `= e.message` を無くす（既存の Spec の leakage guard）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1) | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-1・AQ-4 | `docs/design/architecture.md` §2 |
| 決定 | **SG-D6**・**SG-D11**（上）、ADR-102（失敗の識別子は契約。文言はクライアントの写像が所有する）、ADR-108（値域 3 値: `generation_failed`・`quota_exhausted`・`null`） | `docs/adr/102-generation-failure-kind-contract.md`・`108-…md` |
| 共有仕様 | §2.11（利用者向けの文言はプラットフォームが所有する。オフライン起因は「オフライン」と分かる文言） | `docs/design/shared-playback-spec.md` |
| Spec | TA-M-PB の `PlaybackNotice`、TA-Q-PB4・TA-C-PB7、TA-D9（`= e.message` の `podcast/` の分）、§5.1「整合性と失敗」（domain が理由、`PlaybackNotice` が種類）、§8.4「A-T3b」、§10.3 の 1・2 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | leakage guard（例外の message を画面に出さない） | `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §5 |

## 2. 前提（着手条件）

- A-T3a2（A-T3a の後半。前半 A-T3a1 はその前に入っている）の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断: SG-D6・SG-D11 で確定（判断待ちは無い）。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 期待（2026-10-01 の値と、A-T3a 後の見込み） |
|---|---|---|
| 例外の message の代入 | `grep -rn '= e\.message' app/src/main/java/com/rioikeda/newslisten/podcast` | 2026-10-01: 5 箇所（`PodcastViewModel.kt:137,214,216,253,311`）。A-T3a・A-S4a の後は `PlaybackCoordinator.kt`・`OfflineLibrary.kt`・`EpisodeCatalog.kt` に移っている。移った先の行を記録する |
| 文言の書込 | `grep -rn 'errorMessage\|lastError\|notice' app/src/main/java/com/rioikeda/newslisten/{podcast,catalog}` | 書込の箇所と、画面の読み（`PodcastScreen.kt` の dialog）を記録 |
| 生成の失敗の文言 | `grep -rn '生成に失敗しました\|生成中のため再生できません\|オフラインのため再生できません' app/src/main` | 現行の固定文言 3 つ（`playabilityError` とオフライン） |
| 識別子の値域 | backend の ADR-108 決定 4 | `generation_failed`・`quota_exhausted`・`null`（Firestore に残る `partial_failed` は出口で `generation_failed`） |
| web の写像 | `grep -rn 'generation_failed\|quota_exhausted' web/lib web/app web/components`（親リポで） | 2026-10-01: 0 件。実装されていれば、その文言に §5 の表を揃える（揃えるのは文言だけで、種類の集合は変えない） |

## 4. 対象の path と対象外

**変える（main）**: `podcast/app/PlaybackReadModels.kt`（`PlaybackNotice` の種類）、`podcast/app/PlaybackCoordinator.kt`・`podcast/OfflineLibrary.kt`・`catalog/app/EpisodeCatalog.kt`（`notice` に種類を置く。`= e.message` を無くす）、`podcast/PodcastViewModel.kt`（`errorMessage` の写しをやめ `notice` を中継）、`podcast/PodcastScreen.kt`（dialog が種類から文言を選ぶ）、文言の置き場（presentation の 1 箇所。`podcast/` の画面のファイルか `res/values/strings.xml`）。
**変える（test）**: 知らせの種類と文言の対応の表駆動テスト、Coordinator のテストのうち `errorMessage` の文字列を見ていたもの（種類で見るように直す。理由に SG-D6）。

**対象外**: `feed/` の `= e.message` 3 箇所（A-T8a。文面は SG-D10）。`ApiException` の意味の variant（A-T4）。再生の手順・遷移。生成の失敗の識別子の値域（backend の契約）。

## 5. 変更の責務

`PlaybackNotice` の種類と、presentation が選ぶ文言（SG-D6・SG-D11。種類の集合は Spec §5.1 の `SessionErrorReason` と開始前の判定から導く）:

| 種類 | 起きる場面 | 文言 | 根拠 |
|---|---|---|---|
| `Offline` | オフラインで未キャッシュ（`SourceUnavailable`） | オフラインのため再生できません | 現行の固定文言（共有仕様 §2.11） |
| `StillGenerating` | `Episode.Generating` を開始しようとした | 生成中のため再生できません | 現行の固定文言 |
| `GenerationFailed(kind)` | `Episode.Failed` を開始しようとした | 下の識別子の表 | SG-D6（識別子を文言へ写す） |
| `FetchOffline` | 取得の失敗（`FetchFailed`・一覧の読み込み・ダウンロードの取得）のうち、例外が `ApiException.NetworkError` のもの | オフラインです。接続を確認してから、もう一度お試しください | SG-D11（A-T8a の SG-D10 と同じ文） |
| `FetchFailed` | 取得の失敗（同上）のうち、`ApiException.NetworkError` 以外 | エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください | SG-D6 |
| `PlayerFailed` | 再生エンジンの失敗（`Player(reason)`）・保存の失敗 | 再生できませんでした。もう一度お試しください | SG-D6 |

生成の失敗の識別子 → 文言（SG-D6 で本 order が定める。web に写像が入ったら文言をそれに揃える）:

| 識別子 | 文言 |
|---|---|
| `generation_failed` | 生成に失敗しました |
| `quota_exhausted` | 生成の上限に達したため、生成できませんでした |
| `null`・それ以外の値（未知・旧い自由文） | 生成に失敗しました（fail-closed。web W-S4a と同じ倒し方） |

- domain は理由（`SessionErrorReason`・`Episode.Failed` の識別子）を持ち、application は種類（`PlaybackNotice`）を置き、presentation が文言を選ぶ。文言の対応は presentation の 1 箇所。
- 例外の `message` を `notice` にも画面にも渡さない。
- `FetchOffline` と `FetchFailed` の分け方は、例外を捕まえる箇所（Coordinator・`OfflineLibrary`・`EpisodeCatalog`）が例外の型（`is ApiException.NetworkError`）で種類を選ぶ。型の比較を adapter へ寄せるのは A-T4。

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `notice` が `errorMessage` の写し（A-T3a） | `PlaybackCoordinator` | A-T3a | 本 slice で削除 | A-T3b（本 slice） |
| 失敗の表示に識別子をそのまま出す（Spec §8.2） | `PodcastViewModel` | 既存 | 本 slice で削除 | A-T3b（本 slice） |

許可リスト: TA-V4 の `= e.message` のうち `podcast/`・`catalog/` の分が消える。増やさない。

## 7. 変わる挙動（決定 ID つき）

**SG-D6**: (1) 取得の失敗の表示が、例外の message（`Network error: …` など）から「エピソードを取得できませんでした。通信状況を確かめて、もう一度お試しください」になる。(2) 再生エンジンの失敗・保存の失敗の表示が「再生できませんでした。もう一度お試しください」になる。(3) 生成に失敗したエピソードの表示が、識別子（`generation_failed`・`quota_exhausted`）から §5 の文言になる。**SG-D11**: (4) 取得の失敗のうち通信の失敗（`ApiException.NetworkError`）の表示が、例外の message から「オフラインです。接続を確認してから、もう一度お試しください」になる（(1) はそれ以外の取得の失敗）。ほかは変えない（「オフラインのため再生できません」「生成中のため再生できません」は現行のまま）。

## 8. 契約と検査

- TA-D9: `podcast/`・`catalog/` の `= e.message` が 0。TA-Q-PB4（`notice: StateFlow<PlaybackNotice?>`）・TA-C-PB7（`dismissNotice()`）。
- 表駆動テスト: §5 の種類 6 行 ＋ 識別子 3 行（`generation_failed`・`quota_exhausted`・未知の値）。テスト名に `SG-D6`（`FetchOffline` の行は `SG-D11`）を含める。取得の失敗は `ApiException.NetworkError(IOException())` → `FetchOffline`、`ApiException.HttpError(500)` → `FetchFailed` の 2 つを注入して確かめる。
- 反転するテスト（`errorMessage` の文字列を見ていたもの）は理由に `SG-D6` を持つ。PS-03（「オフライン」と分かる文言）・PS-11 のテストは種類で green。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（A-S3 の前なら `JAVA_HOME=<JBR>` を付ける。takt の実行中は worktree の外で走らせない）。
- `grep -rn '= e\.message\|e\.message' app/src/main/java/com/rioikeda/newslisten/podcast app/src/main/java/com/rioikeda/newslisten/catalog` = 0（集合 = `podcast/` と `catalog/` の全 Kotlin）。
- `grep -rn 'errorMessage' app/src/main/java/com/rioikeda/newslisten/podcast app/src/main/java/com/rioikeda/newslisten/catalog` = 0（`notice` に置き換わる）。
- 負の対照: 取得の失敗を注入するテストで、例外の message（例 `"boom"`）が画面の文言に含まれないこと。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§9 の grep が期待値。許可リストは減るだけ。
- §5 の 6 種類と識別子 3 行のテストがあり、テスト名に `SG-D6` か `SG-D11` を含む。
- 反転したテストがすべて `SG-D6` か `SG-D11` を理由に持つ（PR に「テスト名 → 決定 ID」の表）。
- `feed/` の `= e.message` 3 箇所が変わっていない（A-T8a）。

相互矛盾の突き合わせ: 「`feed/` は対象外」と §9 の grep（集合を `podcast/`・`catalog/` に限る）は両立する。
- **公開面**（NFR-10・AQ-6。2026-10-01 追加: 新しい domain・リードモデルの型を作る slice は、その型を公開面の検査の対象に足す。Spec §7 TA-V5・TA-V6）: `PlaybackNotice`（リードモデル）を TA-V5（静的: property が `val`）と TA-V6（実行時: `notice` が返した値の入れ子を書き換えても次の読みが変わらない。`PlaybackReadModelsEncapsulationTest` に追記）の対象に足す。

## 11. 決定 slice か適用 slice か

適用 slice（決定 SG-D6 は 2026-10-01 に採用済み）。

## 12. 規模の目安と返却事項

- 規模 ≈ 200 行（Spec §8.4）。1 PR。
- 返却事項: SG-D6 を台帳と Spec §10.3（1・2 を「決定済み」へ）・§8.1（A-T3b を ready へ）に反映する旨。android-design §3「文言写像は次サイクル」を改める旨。識別子の文言の表を web・iOS と揃える旨（web W-S4a の `failureMessage` が入ったら文言を合わせる）。

## android リファクタ A-T7b2: Learning の単語テスト（`VocabularyTestSession` を純粋な domain の値に）

> **2026-10-01 order の分割**: 旧 `A-T7b-learning-quiz-vocabulary.md`（1 ファイルに 2 PR）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T7b2 = 1 PR（単語テスト）**。前の PR は [A-T7b1-learning-quiz-vocabulary.md](A-T7b1-learning-quiz-vocabulary.md)（クイズと語彙の登録・中継 3 操作の移動）。Spec の「A-T7b」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

単語テストの状態機械を純粋な domain の値（`VocabularyTestSession`）にし、手順（`delay` を含む）を application に置き、port `VocabularyTestApi` を DTO を返さない形にする。画面は `VocabularyTestView` を読む。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-LRN-05・06 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-6 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 5・8・9、ADR-087（単語テスト） | `docs/adr/` |
| 学習仕様 | L-R05・L-R06 | `docs/design/learning-engagement-spec.md`、Spec §5.5 |
| Spec | TA-M-LN（`VocabularyTestSession`・`VocabularyTestView`）、TA-C-LN3・TA-Q-LN4、TA-R-LN1・LN2、TA-D2（`VocabularyTestViewModel`）、§8.4「A-T7b」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | 単語テストの既存テスト（期待値を変えない） | 既存の Spec §4 |

## 2. 前提（着手条件）

- **A-T7b1** の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。module をまたぐ「習得」の語・「今日の復習」の論点（前回の監査 §6 の 1・2）は `VocabularyTestSession` の語に効くだけで、本 slice は現行の語（`known`・`retest`）で進める（Spec §10.3 末尾）。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| 単語テストの状態機械 | `grep -n 'phase\|delay\|VocabularyTestItemResponse' app/src/main/java/com/rioikeda/newslisten/vocabulary/VocabularyTestViewModel.kt` | `:14-23` の phase、`:74,77,87-116,118,152,188` |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/vocabulary/VocabularyTestScreen.kt` | **着手のときに全文を読む**（Spec §11） |
| `VocabularyTestApi` | `grep -n 'suspend fun' app/src/main/java/com/rioikeda/newslisten/network/VocabularyTestApi.kt` | 2 操作（戻り値が DTO） |
| `learning/` の到達点 | `ls app/src/main/java/com/rioikeda/newslisten/learning/domain app/src/main/java/com/rioikeda/newslisten/learning/app` | A-T7a・A-T7b1 のファイルを記録 |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `learning/domain/VocabularyTestSession.kt`（自己申告 → 再テスト → 送信 → 結果の状態機械。純粋な値）、`learning/app/VocabularyTest`（TA-C-LN3・TA-Q-LN4。`delay` はここに残す）、`learning/app/VocabularyTestApi.kt`（port）、`network/VocabularyTestApiAdapter.kt`。
**変える（main）**: `vocabulary/VocabularyTestViewModel.kt`（`learning/app/` の入口へ委ねる）、`vocabulary/VocabularyTestScreen.kt`（`VocabularyTestView` を読む）、`di/AppContainer.kt`。
**変える（test）**: `VocabularyTestViewModelTest`、`VocabularyTestSession` の domain のテスト、TA-V6 のテスト、`Allowlist.kt`。

**対象外**: クイズと語彙の登録（A-T7b1 で済んでいる）。スワイプの向きの意味（TA-R-LN2。presentation のまま）。出題と採点の規則（backend。ADR-087）。語彙の登録の解除の導線（L-R05。新しい機能）。復習語彙の再出現・推定レベル（L-R07・L-R08。未実装）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `VocabularyTestSession`（TA-R-LN1: 1 回 10 語まで。「知らない」と答えた語だけ再テスト。選択肢は正しい意味と誤答の候補 3 つまで） |
| application | 単語テスト（TA-C-LN3・TA-Q-LN4）。`delay` は application |
| port / adapter | `VocabularyTestApi`（DTO を返さない）と adapter |
| presentation | `VocabularyTestScreen` は `VocabularyTestView` を読む。スワイプの向きの写し方は画面に残す（TA-R-LN2） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `vocabulary/` の画面と ViewModel が今の package に居る | 既存 | — | `learning/ui/` へ | A-T9 |

許可リスト: TA-D2 の `VocabularyTestViewModel` が消える。増やさない。

## 7. 変わる挙動

無い。

## 8. 契約と検査

- TA-R-LN1 の「今どこに居るか」が正本へ（§9 の grep）。
- TA-V5・TA-V6: `VocabularyTestSession`・`VocabularyTestView` の公開面とカプセル化。
- 既存: 単語テストの既存テストが期待値を変えずに green。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -n 'import com.rioikeda.newslisten.model' app/src/main/java/com/rioikeda/newslisten/vocabulary/VocabularyTestViewModel.kt` = 0。
- `grep -rn 'kotlinx.coroutines\|delay(' app/src/main/java/com/rioikeda/newslisten/learning/domain` = 0（`delay` は application）。
- A-T7b1 の到達点を保つ: `grep -n 'loadVocabularyRegistrations\|saveVocabulary\|submitQuizAnswers\|ApiClient' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- 移したテストの期待値が不変（PR に「旧テスト名 → 新テスト名」の表）。

相互矛盾の突き合わせ: 「スワイプの向きは presentation のまま」と「状態機械を domain へ」— 状態機械は `assess(known)` の入力を受けるだけで、向きの写し方を持たないので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 400 行（A-T7b の全体 ≈ 900 行の後半。Spec §8.4）。
- 返却事項: 「習得」の語の決定が出たら `VocabularyTestSession` の語を揃える旨。

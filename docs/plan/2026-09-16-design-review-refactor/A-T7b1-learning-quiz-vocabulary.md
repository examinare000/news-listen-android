## android リファクタ A-T7b1: Learning のクイズと語彙の登録。学習の中継 3 操作を `PodcastViewModel` から出す

> **2026-10-01 order の分割**: 旧 `A-T7b-learning-quiz-vocabulary.md`（1 ファイルに 2 PR。Spec §8.4・旧 order「1 つ目の PR でクイズと語彙の登録（中継 3 操作の移動を含む）、2 つ目の PR で単語テスト」）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T7b1 = 1 PR（クイズと語彙の登録）**。後の PR は [A-T7b2-learning-vocabulary-test.md](A-T7b2-learning-vocabulary-test.md)（単語テスト）。Spec の「A-T7b」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

語彙の同一性（`VocabularyKey`）とクイズの成績（`QuizGrade`）を domain に置く。クイズの送信と失敗の分類を画面から出し、学習の中継 3 操作を `PodcastViewModel` から `learning/app/` へ移す（既存の Spec の「RF8 保留」を解く）。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (1)(2)・NFR-10、F-LRN-01・02 | `docs/prd/2026-05-31-news-listen.md` |
| 品質 scenario | AQ-1・AQ-3・AQ-6 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 5・8・9、導出 A-22（クイズの採点は command の receipt） | Spec §10.1 |
| 学習仕様 | L-R18・L-R19（クイズ）、語彙の登録 | `docs/design/learning-engagement-spec.md`、Spec §5.5 |
| Spec | TA-M-LN（`VocabularyKey`・`QuizGrade`）、TA-C-LN1・LN2・TA-Q-LN3、TA-R-LN3・LN7・LN8、TA-D2（`QuizSheet`・`PodcastViewModel`）・TA-D4（`QuizSheet` の `network` の import）・TA-D8（`submitQuizAnswers` が DTO を返す）、§8.4「A-T7b」、§10.2 の 4 行目 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T16（`PodcastViewModel` の学習の中継 3 操作は A-T7b1 まで `ApiClient` のまま）、`NoThrowingDefaultStructureTest` の 2 件目 | 既存の Spec §4 |

## 2. 前提（着手条件）

- A-T7a の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テストと `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 2026-10-01 の値 |
|---|---|---|
| 学習の中継 3 操作 | `grep -n 'fun loadVocabularyRegistrations\|fun saveVocabulary\|fun submitQuizAnswers\|fun isVocabularyRegistered\|registeredVocabularyKeys\|savingVocabularyKeys' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` | 2026-10-01: `:143,152,489` と `:101,104,168`。A-T3a1・A-T3a2 の後は `PlaybackCoordinator` と `PodcastViewModel` のどちらに居るかを記録 |
| 語彙の同一性 | `grep -n 'lowercase\|trim' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` | `:171-172`（TA-R-LN7） |
| クイズの画面 | `grep -n 'correctRate\|e.code\|import com.rioikeda.newslisten.network\|submitQuizAnswers' app/src/main/java/com/rioikeda/newslisten/podcast/QuizSheet.kt` | `:46`（`network` の import）・`:49-50`（閾値 0.5）・`:191-200`（送信と失敗の分類）。`:195` の code の比較は A-T4 で意味の variant に替わっている |
| 画面の全文 | `wc -l app/src/main/java/com/rioikeda/newslisten/podcast/QuizSheet.kt` | **着手のときに全文を読む**（Spec §11） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `learning/domain/VocabularyKey.kt`、`learning/domain/QuizGrade.kt`、`learning/app/QuizCommands`（`submitQuiz(episodeId, answers)` → receipt `QuizGrade`）、`learning/app/VocabularyRegistry`（`registerVocabulary`・TA-Q-LN3 の key の集合）、`LearningApi` の語彙・クイズの操作と adapter。
**変える（main）**: `podcast/PodcastViewModel.kt`（学習の中継 3 操作と語彙の key の状態を外す）、`podcast/QuizSheet.kt`（送信と失敗の分類を出し、command を呼ぶ。`network` を import しない）、`podcast/AudioPlayerSection.kt`（語彙の登録の入口を Learning へ）、`di/AppContainer.kt`。
**変える（test）**: `PodcastViewModelTest` の学習の部分を移す（期待値は変えない）、`QuizPresentationTest`、新しい domain のテスト、`NoThrowingDefaultStructureTest` の 2 件目（`PodcastViewModel` が `ApiClient` を受けなくなる）、`Allowlist.kt`。

**対象外**: 単語テスト（`VocabularyTestSession`・`VocabularyTestApi`・`VocabularyTestViewModel`・`VocabularyTestScreen`。A-T7b2）。出題と採点の規則（backend。ADR-087）。語彙の登録の解除の導線（L-R05。導線が無く、新しい機能）。復習語彙の再出現・推定レベル（L-R07・L-R08。未実装）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| domain | `VocabularyKey`（TA-R-LN7: エピソード id と、前後の空白を除いて小文字にした語）、`QuizGrade`（TA-R-LN3: 正答率・設問ごとの正誤・合格 = 正答率 0.5 以上） |
| application | `submitQuiz`（TA-C-LN1。事前条件 = 全問に答えている。receipt = `QuizGrade`。A-22）、`registerVocabulary`（TA-C-LN2）、TA-Q-LN3（`registeredVocabularyKeys`・`savingVocabularyKeys`・`isVocabularyRegistered`） |
| adapter | クイズが提供されていない（404）を失敗の意味へ写す（TA-R-LN8） |
| presentation | `QuizSheet` は command を呼び、`QuizGrade` と失敗の種類から表示を選ぶ。鳴らす場面は `feedbackEvents`（A-T7a）から |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| 学習の中継 3 操作が `PodcastViewModel` に居る（RF8 保留） | 既存 | 既存 | 本 PR で `learning/app/` へ | A-T7b1（本 PR） |
| `podcast/QuizSheet.kt` が `podcast/` に居る | 既存 | — | `learning/ui/` へ | A-T9 |

許可リスト: TA-D2 の `QuizSheet`・`PodcastViewModel`、TA-D4 の `QuizSheet` の `network` の import、TA-D8 の `submitQuizAnswers`、TA-V4 の `correctRate >=` が消える。増やさない。

## 7. 変わる挙動

無い。

## 8. 契約と検査

- TA-R-LN3・LN7・LN8 の「今どこに居るか」が正本へ（§9 の grep）。
- TA-D8: command `submitQuiz` の戻り値は receipt `QuizGrade`（DTO を返さない）。TA-V7 の静的な検査の対象に入る。
- TA-V5・TA-V6: `VocabularyKey`・`QuizGrade` の公開面（構築子と property）とカプセル化（Spec §9 の NFR-10 の行。新しい domain の型を作る slice が公開面の検査の対象を足す）。
- 既存: 語彙・クイズの既存テストが期待値を変えずに green。`NoThrowingDefaultStructureTest` の 2 件目を「`PodcastViewModel` が `ApiClient` を受けない」に更新（CI-T16）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain` → exit 0（takt の実行中は worktree の外で走らせない）。
- `grep -n 'loadVocabularyRegistrations\|saveVocabulary\|submitQuizAnswers\|ApiClient' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0。
- `grep -n 'import com.rioikeda.newslisten.network\|import com.rioikeda.newslisten.model\|correctRate >=' app/src/main/java/com/rioikeda/newslisten/podcast/QuizSheet.kt` = 0。
- `grep -rn 'kotlinx.coroutines\|delay(' app/src/main/java/com/rioikeda/newslisten/learning/domain` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§6 の組が許可リストから消え、ほかは増えていない。§9 の grep が期待値。
- 移したテストの期待値が不変（PR に「旧テスト名 → 新テスト名」の表）。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 500 行（A-T7b の全体 ≈ 900 行の前半。Spec §8.4）。
- 返却事項: 既存の Spec §2「語彙登録・クイズ中継の 3 操作は `ApiClient` のまま（RF8 保留）」と android-design §7.2 を改める旨（Spec §10.2）。

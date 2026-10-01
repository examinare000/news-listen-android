## android リファクタ A-T3a2: `QueueView`・`EpisodeRow`・id を受ける command に切り替える（画面は `NowPlaying`・`QueueView`・`EpisodeRow` だけを読む）

> **2026-10-01 order の分割**: 旧 `A-T3a-playback-application.md`（1 ファイルに 2 PR）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T3a2 = 1 PR**。前の PR は [A-T3a1-playback-coordinator.md](A-T3a1-playback-coordinator.md)（Coordinator の取り出し・`AudioStore`・Media3 の分類）。Spec の「A-T3a」は 2 つを合わせた呼び名。

## 1. 目的と、応える要求・設計・契約の ID

A-T3a1 で取り出した `PlaybackCoordinator` の command を id で受ける形にし、query をリードモデル（`NowPlaying`・`QueueView`・`EpisodeRow`）にする。画面は `NowPlaying`・`QueueView`・`EpisodeRow` だけを読み、id で command を呼ぶ。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (3)・NFR-10 | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-4・AQ-6 | `docs/design/architecture.md` §2・§6 |
| 決定 | ADR-110 決定 3〜5・8、導出 A-21（command は id を受け、Coordinator が Catalog から解決する） | Spec §10.1 |
| 共有仕様 | §2.7（offset の規約）、§4.4（`session`・`queue` はテストの oracle）、§6.8（id からの再生開始） | `docs/design/shared-playback-spec.md` |
| Spec | TA-M-PB（`NowPlaying`・`QueueView`・`QueueEntry`・`UpNextEntry`）、TA-C-PB1〜PB8・TA-Q-PB1〜PB5、TA-R-PB7・PB8、TA-Q-CT1・`EpisodeRow`（§5.2）、TA-V6・TA-V7、§8.4「A-T3a」 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T1〜T9・T19・T20（期待値を変えない）、PS-01〜PS-11、SL-01（再生停止部分） | 既存の Spec §4、共有仕様 §4.4 |

## 2. 前提（着手条件）

- **A-T3a1** の android PR が main に merge 済み、かつ親ポインタが進んでいる。
- baseline: 全 unit テスト（A-T3a1 の `PlaybackCoordinatorTest` を含む）と `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（投入の直前に数え直す）

| 確かめること | コマンド | 期待（前の slice の到達点） |
|---|---|---|
| Coordinator の公開面 | `grep -nE 'fun [a-zA-Z]+\(|val [a-zA-Z]+: StateFlow' app/src/main/java/com/rioikeda/newslisten/podcast/app/PlaybackCoordinator.kt` | A-T3a1 の一覧（command が `Episode` を受ける形）を記録 |
| 画面の判定 | `grep -n 'moveUpNext(index\|index > 0\|totalCount - 1' app/src/main/java/com/rioikeda/newslisten/podcast/QueueSheet.kt`／`grep -n 'nowPlaying?.episodeId ==' app/src/main/java/com/rioikeda/newslisten/podcast/{PodcastScreen,QueueSheet}.kt` | TA-R-PB8: `:137,142,241,258` 付近、TA-R-PB7: `PodcastScreen.kt:160`・`QueueSheet.kt:83` 付近（A-S2c 後の行） |
| `NowPlaying` の置き場 | `grep -n 'class NowPlaying' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt` | 1（A-S2b2a の置き場） |
| 画面が読むもの | `grep -rn 'viewModel\.\(queue\|session\)\b' app/src/main` | 記録する |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `podcast/app/PlaybackReadModels.kt`（`NowPlaying` を `podcast/PlaybackSession.kt` から移す、`QueueView`・`QueueEntry`・`UpNextEntry`、`PlaybackMetadata`）、`catalog/app/EpisodeCatalog.kt`（`episodes: StateFlow<List<EpisodeRow>>`・`refresh()`、`EpisodeRow`）。
**変える（main）**: `podcast/app/PlaybackCoordinator.kt`（command を id で受ける。query をリードモデルに）、`podcast/PodcastViewModel.kt`（画面の状態と中継だけ）、`podcast/PlaybackSession.kt`（`NowPlaying` を出す）、`podcast/PlaybackMetadata.kt`（`PlaybackReadModels.kt` へ）、`podcast/OfflineLibrary.kt`（TA-C-PB8 `download(episodeId)`・`removeDownload(episodeId)`）、画面 4 ファイル（`PodcastScreen`・`PodcastRowView`・`AudioPlayerSection`・`QueueSheet`）、`di/AppContainer.kt`。
**変える（test）**: `PlaybackCoordinatorTest`（id の command へ。期待値は変えない）、`QueueViewTest`・`EpisodeCatalogTest`・`PlaybackReadModelsEncapsulationTest`（TA-V6）、`architecture/LayerMap.kt`・`Allowlist.kt`。

**対象外**: Coordinator の取り出し・`AudioStore`・Media3 の分類（A-T3a1 で済んでいる）。知らせの文言と `errorMessage` の型（A-T3b）。学習の中継 3 操作（A-T7b1）。`PlaybackQueue` と `PlaybackSession` の遷移関数・`moveUpNext` の意味（TA-R-PB8「`PlaybackQueue.moveUpNext` は変えない」）。通知（`podcast_id`）からの開始の配線（Spec §5.1。新しい機能なので範囲外）。ファイルの package 移動のうち、上に挙げた新規以外（A-T9）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| application | `PlaybackCoordinator`: command = TA-C-PB1〜PB7（`playNow(episodeId)`・`playNext(episodeId)`・`addToQueue(episodeId)`・`removeFromQueue(episodeId)`・`moveUp(episodeId)`・`moveDown(episodeId)`・`togglePlayPause()`・`skipBackward()`・`skipForward()`・`seekTo(seconds)`・`setSpeed(speed)`・`retry()`・`stopForSubjectLeave()`・`dismissNotice()`）。戻り値は無し（A-T3b までは `errorMessage` を `dismissNotice` が消す）。query = TA-Q-PB1〜PB5（`nowPlaying`・`queueView`・`isPlaying`・`positionSeconds`・`durationSeconds`・`playbackSpeed`・`notice`（A-T3b までは `errorMessage` の写し）・`downloadingIds`・`downloadedIds`）。id から `Episode` への解決は Catalog（`EpisodeCatalog`）から行う（A-21）。`session`・`queue` は Coordinator に残し、テストの oracle にだけ使う（画面へ渡さない） |
| application | `OfflineLibrary`: TA-C-PB8 `download(episodeId)`・`removeDownload(episodeId)` |
| application | `EpisodeCatalog`（TA-Q-CT1）: `episodes: StateFlow<List<EpisodeRow>>`（`episodeId`・`displayTitle`・`difficultyCode`・`durationSeconds`・`createdAt`・状態の種類・`canDownload`・`isNowPlaying`）・`refresh()` |
| リードモデル | `QueueView`（`nowPlaying: QueueEntry?`・`upNext: List<UpNextEntry>`・`upNextCount`）。`UpNextEntry` は `canMoveUp`・`canMoveDown` を持つ（TA-R-PB8。画面の `index > 0` 判定を置き換える）。「現在再生中」の判定は `nowPlaying` と `EpisodeRow.isNowPlaying`（TA-R-PB7）。値は書き換えられない（list は写しを返す） |
| presentation | `PodcastViewModel` は画面の状態と中継だけ。画面は `NowPlaying`・`QueueView`・`EpisodeRow` を読み、id で command を呼ぶ |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| command が `Episode` を受ける・画面が `Episode` と `queue` を読む（A-T2b の一時経路） | `PlaybackCoordinator` | A-T2b | 本 PR で削除 | A-T3a2（本 PR） |
| `PodcastViewModel` が Coordinator の公開面を写すだけの委譲を持つ | `PodcastViewModel` | A-T3a1 | 本 PR で画面の状態と中継だけにする | A-T3a2（本 PR） |
| `notice` が `errorMessage` の写し（例外の message を含む） | `PlaybackCoordinator` | A-T3a2 | `PlaybackNotice` と SG-D6 の文面 | A-T3b |
| ファイルの置き場（既存のファイルは今の package） | 各ファイル | — | 目標の package へ | A-T9 |

## 7. 変わる挙動

無い。既存の PS・CI-T のテストの期待値を変えない（Spec §8.4）。

## 8. 契約と検査

- TA-R-PB7・PB8 の「今どこに居るか」が 0 件（§9 の grep）。
- TA-V6: `QueueView`・`EpisodeRow`・`NowPlaying` のカプセル化（テスト名に `TA-V6`）。
- TA-V7: id の command に替わった `PlaybackCommands`・`PlaybackQueries` が引き続き静的な検査を通る。
- 既存: CI-T1〜T9・T19・T20、PS-01〜PS-11、T-SL01p、`OfflineLibraryTest` が期待値を変えずに green（テスト名の行 ID を保つ）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain`（A-S3 の後。前なら `JAVA_HOME=<JBR>` を付ける）→ exit 0。takt の実行中は worktree の外で走らせない。
- `grep -rn 'moveUpNext(index\|index > 0\|totalCount - 1' app/src/main/java/com/rioikeda/newslisten/podcast/QueueSheet.kt` = 0（TA-R-PB8）。
- `grep -rn 'episodeId ==\|\.id ==' app/src/main/java/com/rioikeda/newslisten/podcast/{PodcastScreen,QueueSheet,PodcastRowView}.kt` = 0（TA-R-PB7。集合 = 画面 3 ファイル）。
- `grep -rn 'viewModel\.\(queue\|session\)\b' app/src/main` = 0（画面が `queue`・`session` を読まない）。
- A-T3a1 の到達点を保つ: `grep -rn 'androidx.media3' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackState.kt` = 0、`grep -rn 'AudioCacheManager' app/src/main/java/com/rioikeda/newslisten/podcast` = 0。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。許可リストが着手前の記録から増えていない。§9 の grep がすべて期待値。
- 既存のテストの期待値を変えていない（移したテストは名前と行 ID を保つ）。
- TA-V10（PR の説明）: 「キューの行に項目を足したら」の問いに、変わるファイル（`QueueView` と `podcast/QueueSheet.kt`）を答える。

相互矛盾の突き合わせ: 「`moveUpNext` の意味を変えない」と「画面の offset 計算を無くす」— Coordinator の `moveUp(id)`・`moveDown(id)` が削除前 offset の規約で `PlaybackQueue.moveUpNext` を呼ぶので両立する。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 500 行（A-T3a の全体 ≈ 1,000 行の後半。Spec §8.4）。
- 返却事項: 既存の Spec §5 CP5 の公開面を改める旨（Spec §10.2）。共有仕様 §6.8 の Android 欄を「`playNow(id)`」に直せる旨（A-21）。A-T3b の「着手前の前提点検」の値（`errorMessage` の書込箇所）。

## android リファクタ A-T3a1: `PlaybackCoordinator` を取り出して `PodcastViewModel` が委譲する。`AudioStore` を入れ、Media3 の分類を adapter へ移す

> **2026-10-01 order の分割**: 旧 `A-T3a-playback-application.md`（1 ファイルに 2 PR。Spec §8.4「1 つ目の PR で Coordinator を取り出して `PodcastViewModel` が委譲し、2 つ目の PR でリードモデルと id の command に切り替える」）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-T3a1 = 1 PR**。後の PR は [A-T3a2-playback-read-models.md](A-T3a2-playback-read-models.md)（リードモデルと id の command）。Spec の「A-T3a」は 2 つを合わせた呼び名。`AudioStore` と Media3 の分類の移動は、画面とリードモデルに触れないので本 PR に入れた。

## 1. 目的と、応える要求・設計・契約の ID

再生の use case を `PodcastViewModel` から application の `PlaybackCoordinator` へ取り出し、`PodcastViewModel` は Coordinator へ委譲するだけにする。command と query の入口を `PlaybackCommands`・`PlaybackQueries` の interface に分ける（本 PR では command の引数と画面の読みは今のまま。id の command とリードモデルは A-T3a2）。あわせて、`OfflineLibrary` が adapter（`AudioCacheManager`）を直接受けるのをやめて port `AudioStore` を受け、Media3 のエラーコードの分類を adapter（`ExoPlayerController`）へ移す。

| 種類 | ID | 正本の path と節 |
|---|---|---|
| 品質要求 | NFR-09 (4)・NFR-10 | `docs/prd/2026-05-31-news-listen.md` §6 |
| 品質 scenario | AQ-4・AQ-5 | `docs/design/architecture.md` §2・§5 |
| 決定 | ADR-110 決定 3〜5・8 | `docs/adr/110-refactor-target-domain-centered-onion-cqrs.md` |
| 共有仕様 | §4.4（`session`・`queue` はテストの oracle） | `docs/design/shared-playback-spec.md` |
| Spec | TA-C-PB1〜PB8・TA-Q-PB1〜PB5 の入口（interface）、TA-R-PB9、`AudioStore` の port（§5.1）、TA-D1（`PlaybackState.kt`）、TA-V7、§8.4「A-T3a」の中間状態、§10.2 の 3 行目 | `android/docs/design/2026-09-30-implementation-spec-target-architecture.md`（以下「Spec」） |
| 既存の契約 | CI-T1〜T9・T19・T20（期待値を変えない）、PS-01〜PS-11、SL-01（再生停止部分） | 既存の Spec §4、共有仕様 §4.4 |

## 2. 前提（着手条件）

- A-S2c と A-S4a の android PR が main に merge 済み、かつ親ポインタが進んでいる（Spec §8.1）。A-S3 も済んでいれば検証コマンドは `./gradlew clean testDebugUnitTest`。
- baseline: 全 unit テスト（A-S2b2a・A-S2b2b の T-T*・PS-*・T-SL01p、A-S4a の `OfflineLibraryTest` を含む）と `ArchitectureStructureTest` が green。
- 判断待ち: 無い。

## 3. 着手前の前提点検（後の wave なので、全部を投入の直前に数え直す）

| 確かめること | コマンド | 期待（前の slice の到達点） |
|---|---|---|
| 公開面 | `grep -nE '^\s*(suspend )?fun [a-zA-Z]+\(|^\s*val [a-zA-Z]+: StateFlow' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` | A-S2c 後の公開操作の一覧（`playNow`・`playNext`・`addToQueue`・`removeFromQueue`・`moveUpNext`・`togglePlayPause`・`skipBackward`・`skipForward`・`seekTo`・`setSpeed`・`retry`・`stopForSubjectLeave`・`clearError`・`download`・`removeDownload`・`fetchPodcasts`・学習の中継 3）を記録 |
| Media3 の分類 | `grep -n 'androidx.media3\|fun classifyPlaybackError' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackState.kt` | `:3` の import と `:63` の関数 |
| `OfflineLibrary` の依存 | `grep -n 'AudioCacheManager' app/src/main/java/com/rioikeda/newslisten/podcast/OfflineLibrary.kt` | 構築子の 1 件（A-S4a が許可リストに足した組） |
| `PodcastViewModel(` の呼出 | `grep -rn 'PodcastViewModel(' app/src` | `di/AppContainer.kt` と test helper（記録） |
| TA-V7 の静的な検査 | `ArchitectureStructureTest` の TA-V7 の対象の件数 | 0 件で skip（A-T1 の到達点） |
| 許可リスト | `Allowlist.kt` の件数 | 記録する |

## 4. 対象の path と対象外

**作る（main）**: `podcast/app/PlaybackCoordinator.kt`（`PlaybackCommands`・`PlaybackQueries` の interface と実装。command の引数は本 PR では今の `PodcastViewModel` の公開操作と同じ型）、`podcast/app/AudioStore.kt`（port。主体つきの保存・削除・回収。引数と戻り値は `String`・`ByteArray`・`Boolean`）。
**変える（main）**: `podcast/PodcastViewModel.kt`（再生の手順を Coordinator へ移し、公開操作は Coordinator への 1 行の委譲。公開の型と名前は変えない）、`podcast/PlaybackState.kt` と `podcast/ExoPlayerController.kt`（`classifyPlaybackError` と Media3 の import を adapter へ。TA-R-PB9）、`podcast/OfflineLibrary.kt`（`AudioCacheManager` ではなく `AudioStore` を受ける）、`network/AudioCacheManager.kt`（`AudioStore` を実装）、`di/AppContainer.kt`（Coordinator と `AudioStore` の配線）。
**変える（test）**: Coordinator のテスト（`PodcastViewModelTest` の再生の部分を `PlaybackCoordinatorTest` へ移す。期待値は変えない）、`PlaybackStateTest`（分類のテストを adapter の側へ）、`OfflineLibraryTest`（`AudioStore` の Fake）、`architecture/LayerMap.kt`・`Allowlist.kt`。

**対象外**: リードモデル（`NowPlaying` の移動・`QueueView`・`EpisodeRow`・`EpisodeCatalog`）、id を受ける command、画面 4 ファイル（A-T3a2）。知らせの文言と `errorMessage` の型（A-T3b）。学習の中継 3 操作（A-T7b1）。`PlaybackQueue` と `PlaybackSession` の遷移関数・`moveUpNext` の意味。通知（`podcast_id`）からの開始の配線（Spec §5.1。新しい機能なので範囲外）。ファイルの package 移動のうち、上に挙げた新規以外（A-T9）。

## 5. 変更の責務

| 層 | 置くもの |
|---|---|
| application | `PlaybackCoordinator`: command = TA-C-PB1〜PB7 の入口（本 PR では `playNow(episode)`・`playNext(episode)`・`addToQueue(episode)`・`removeFromQueue`・`moveUpNext`・`togglePlayPause()`・`skipBackward()`・`skipForward()`・`seekTo(seconds)`・`setSpeed(speed)`・`retry()`・`stopForSubjectLeave()`・`clearError()` を今の型のまま）。戻り値は無し。query = `nowPlaying`・`isPlaying`・`positionSeconds`・`durationSeconds`・`playbackSpeed`・`errorMessage`・`downloadingIds`・`downloadedIds`（今の型のまま）。`session`・`queue` は Coordinator に残し、テストの oracle に使う |
| application | `OfflineLibrary`: `AudioStore` を受ける（公開操作は変えない） |
| adapter | `ExoPlayerController`（Media3 のエラーコードの分類 TA-R-PB9。`PlaybackState.kt` は Media3 を import しない）、`AudioCacheManager`（`AudioStore` を実装） |
| presentation | `PodcastViewModel` は Coordinator へ委譲する。画面は今の `PodcastViewModel` の公開面をそのまま読む（A-T3a2 で `NowPlaying`・`QueueView`・`EpisodeRow` へ） |

## 6. 移行の中間状態

| 一時経路 | 持ち主 | 導入 | 削除の条件 | 削除する slice |
|---|---|---|---|---|
| `OfflineLibrary` が `AudioCacheManager` を直接受ける（許可リストの 1 件） | A-S4a | A-S4a | `AudioStore` を受ける | A-T3a1（本 PR） |
| command が `Episode` を受ける・画面が `Episode` と `queue` を読む（A-T2b の一時経路） | `PodcastViewModel` → `PlaybackCoordinator` | A-T2b | id の command とリードモデル | A-T3a2 |
| `PodcastViewModel` が Coordinator の公開面を写すだけの委譲を持つ | `PodcastViewModel` | A-T3a1 | 画面がリードモデルと id の command を読む | A-T3a2 |
| ファイルの置き場（既存のファイルは今の package） | 各ファイル | — | 目標の package へ | A-T9 |

## 7. 変わる挙動

無い。既存の PS・CI-T のテストの期待値を変えない（Spec §8.4）。

## 8. 契約と検査

- TA-R-PB9 の「今どこに居るか」が 0 件（§9 の grep）。TA-D1 の `PlaybackState.kt`（`androidx.media3`）が許可リストから消える。`OfflineLibrary` → `AudioCacheManager` の組が消える。
- TA-V7: `PlaybackCommands`・`PlaybackQueries` が静的な検査の対象に入る（`*Queries` の関数は `Unit` を返さず書き込む port を構築子に取らない。`*Commands` の戻り値は無しか失敗の意味）。A-T1 が skip していた検査が有効になる。
- 既存: CI-T1〜T9・T19・T20、PS-01〜PS-11、T-SL01p、`OfflineLibraryTest` が期待値を変えずに green（移したテストは名前と行 ID を保つ）。

## 9. 受入とテストのコマンド

- `./gradlew clean testDebugUnitTest --console=plain`（A-S3 の後。前なら `JAVA_HOME=<JBR>` を付ける）→ exit 0。takt の実行中は worktree の外で走らせない。
- `grep -rn 'androidx.media3' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackState.kt` = 0、`grep -rn 'fun classifyPlaybackError' app/src/main` の一致が `podcast/ExoPlayerController.kt` だけ（TA-R-PB9）。
- `grep -rn 'AudioCacheManager' app/src/main/java/com/rioikeda/newslisten/podcast` = 0。
- `grep -n 'interface PlaybackCommands\|interface PlaybackQueries' app/src/main/java/com/rioikeda/newslisten/podcast/app/PlaybackCoordinator.kt` = 2。

## 10. 完了条件

- 全 unit テストと `ArchitectureStructureTest` が green。§8 の組が許可リストから消え、ほかは増えていない。§9 の grep がすべて期待値。
- 既存のテストの期待値を変えていない（移したテストは名前と行 ID を保つ。PR に「旧テスト名 → 新テスト名」の表）。
- `PodcastViewModel` の公開操作の名前と型が §3 の記録と同じ（画面のファイルに差分が無い: `git diff --stat` に画面 4 ファイルが現れない）。

## 11. 決定 slice か適用 slice か

適用 slice。

## 12. 規模の目安と返却事項

- 規模 ≈ 500 行（A-T3a の全体 ≈ 1,000 行の前半。Spec §8.4）。
- 返却事項: 既存の Spec §3.1「Coordinator は `PodcastViewModel` 内の関数群」を改める旨（Spec §10.2。§5 CP5 の公開面は A-T3a2 で改める）。A-T3a2 の「着手前の前提点検」の値（Coordinator の公開操作の一覧）。

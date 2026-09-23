## android リファクタ A-S2c: 旧再生実装の削除（`_currentPodcast`・TP-A1・TP2・設定画面の速度 5 段・UI 3 ファイルの読み替え完了）

## 概要
A-S2b2 で入口を差し替えた後に残った旧実装と暫定互換を**削除だけ**する。挙動変更は無い（A-S2b2 の変更行以外は不変のまま）。正本は Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1 INV-P1 の UI 読み替え・§4 CI-T17・§5 naming_decisions・leakage guard・§6 TP2）、親 docs `docs/design/android-design.md` §7.2（現在再生中の正本・速度）・§7.3（A-S2c 行）。旧 `S2-playback.md` を 3 段に分けた第 3 段（親 plan「3 段分割の型 ③」）。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T17 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S2b2 → **A-S2c** → A-S3。

## 前提・着手条件
- 依存 slice: A-S2b2 の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。merge 済みの状態で `nowPlaying` / `session` が存在し、`PodcastViewModel` が `state.Ended` だけを購読している（TP2 の削除条件）。
- baseline: A-S2b2 完了時点の全 unit テストが green。
- **分母は着手時に再計測する**: 下記の行番号は 2026-09-23（A-S2b1 / A-S2b2 着手前）の実測で、A-S2b2 が `PodcastViewModel` の再生 use case を書き換えるため位置はずれる。完了条件は行番号ではなく grep の 0 件で判定する。
- Selection Gate 依存なし。
- 棄却済み案（再提案しない）: `currentPodcast` を公開名として残す案（正本一意・SG-R1）。
- `docs/trial-log/`（android・親）を最初に読む。

## 対象（android サブモジュールのみ。削除／読み替えの列挙。2026-09-23 の grep 実測を分母にする）
**削除（main）**
1. `podcast/PodcastViewModel.kt`: `_currentPodcast`（`:83`）・`currentPodcast`（`:86`。TP-A1 の派生値）・session 適用関数内の `_currentPodcast` 書込（A-S2b2 で 1 箇所に集約済み）・`stopInternal(keepCurrentPodcast)` の引数と分岐（`:502-510` 相当。A-S2b2 で呼出 0）・コメント中の `currentPodcast` 言及（`:343-344, 349, 412, 484, 494`）。2026-09-23 時点の main の `currentPodcast` 一致は本ファイル 14 箇所（上記のほかコード行 `:325, 351, 391, 401` は A-S2b2 が `session` 参照へ書き換える）＋ `AppContainer.kt` 2 ＋ UI 3 ファイル 14 = **30**。加えて **`stopPlayback()`**（A-S2b2 で `Stopped` へ写した暫定の公開操作。§7.2 の残責務 15 操作に無く main の呼出 0）を削除し、`PodcastViewModelTest` の呼出 33 箇所（2026-09-23 実測 `:127, 189, 206, 226, 267, 289, 311, 314, 317, 329, 362, 385, 405, 430, 490, 508, 517, 534, 549, 588, 614, 634, 653, 668, 686, 701, 719, 748, 790, 809, 833, 853, 1137`）を `stopForSubjectLeave()` か `FakePlayerController.setState` による状態注入へ置換する（期待値は変えない）。
2. `podcast/PlayerController.kt:42` `onPlaybackCompleted`（TP2）・`podcast/ExoPlayerController.kt:67, 88-90` の override と `invoke`（`STATE_ENDED` は `state = Ended` の発行だけ残す）。
3. `settings/SettingsScreen.kt:1361` `PLAYBACK_SPEEDS`（5 段）。参照 `:182-183, 654, 658-659, 1201` は `PlaybackConstants.speeds`（A-S2b1 で Double 8 段）へ置換。
4. `di/AppContainer.kt:338, 342` のコメント中の `currentPodcast` 言及を `nowPlaying` へ。

**読み替え（main。挙動不変）**
5. `podcast/AudioPlayerSection.kt:65, 75, 101, 115, 125-126, 142`: `viewModel.currentPodcast` → `viewModel.nowPlaying`（`id` → `episodeId`、他 field は同名）。
6. `podcast/PodcastScreen.kt:53（コメント）, 61, 160, 204`: `nowPlaying` へ。`:160` の `isPlaying = currentPodcast?.id == podcast.id` は `isNowPlaying = nowPlaying?.episodeId == podcast.id`（一時停止中もハイライト維持＝現状の観測挙動）。`:204` の表示条件は `nowPlaying != null`。
7. `podcast/PodcastRowView.kt:41, 64-66`: 引数名 `isPlaying` → `isNowPlaying`（LF11。文言「再生中」は不変）。
8. `podcast/QueueSheet.kt:58, 82-83`: `nowPlaying` へ。両者揃い条件 `currentPodcast != null && queue.current != null` は `nowPlaying != null` に縮む（INV-P1 により `queue.current` は非 null）。

**削除／読み替え（test）**
9. `podcast/PodcastViewModelTest.kt` の `currentPodcast` 一致 **27 箇所**（2026-09-23 実測。読み 23 = `:124, 203, 224, 333, 443, 456, 487, 515, 537, 578, 610, 630, 650, 665, 682, 698, 715, 737, 763, 785, 807, 830, 1135`、コメント・テスト名 4 = `:768, 771, 816, 1101`。A-S2b2 の反転後に再計測する）を `nowPlaying?.episodeId` または `session` の観測へ置換。期待値は変えない。
10. `podcast/FakePlayerController.kt:31, 131` `onPlaybackCompleted` と `completePlayback()`（A-S2a の `setState(Ended(duration))` が代替。呼出側テストを置換）。

**旧 `invalidate` 経路について**: 親 plan の A-S2c 行にある「`invalidate` の旧経路」は 2026-09-23 の実測で**空集合**（`grep -rn 'invalidate' app/src` = 0、再生失敗時に `cacheManager.remove` を呼ぶ経路 0）。A-S2b1 で新設し A-S2b2 で接続した `invalidate` の他に削除する旧経路は無い（本 slice で `invalidate` に触る箇所は 0）。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T17 | `currentPodcast` は存在せず、UI は `nowPlaying` だけを読む。`QueueSheet` の両者揃い条件が消える | T-T17: 構造検査（下記 grep）＋既存テストの置換後 green |

## 特性テスト（baseline）
A-S2b2 完了時点の全 unit テスト（`PodcastViewModelTest` 52＋A-S2b2 追加分を含む）。削除中は各段で green を維持する。

## 手順
1. baseline green を記録。
2. UI 3 ファイル＋`PodcastRowView` を `nowPlaying` へ読み替え（5〜8）→ コンパイル・green。
3. `PodcastViewModelTest` の `currentPodcast` 27 箇所と `stopPlayback` 33 箇所を置換（1・9）→ green。
4. `_currentPodcast` / `currentPodcast` / `keepCurrentPodcast` / `stopPlayback`・コメントを削除（1・4）→ green。
5. `onPlaybackCompleted` を interface・ExoPlayer・Fake から削除（2・10）→ green。
6. `SettingsScreen.PLAYBACK_SPEEDS` を削除し `PlaybackConstants.speeds` へ（3）→ `SettingsViewModelTest` 24 件 green。
7. T-T17（構造検査）を追加 → green。1 slice = 1 PR。temporary path なし（TP-A1・TP2 を本 slice で閉じる）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。既存テストの期待値を変えていない（置換のみ）。
- **参照 0 件**（対象集合 = `app/src/main` と `app/src/test` の全 Kotlin。除外範囲 = `docs/`・`build/`）:
  - `grep -rn 'currentPodcast\|_currentPodcast\|keepCurrentPodcast' app/src` = 0
  - `grep -rn 'onPlaybackCompleted\|completePlayback' app/src` = 0（2026-09-23 実測: main 5・test 8）
  - `grep -rn 'stopPlayback' app/src` = 0（2026-09-23 実測: main は宣言 `PodcastViewModel.kt:473` と `PlayerController.kt:14` のコメント、test 33）
  - `grep -rn 'PLAYBACK_SPEEDS' app/src` = 0（2026-09-23 実測: `SettingsScreen.kt` 7 箇所）
  - `grep -rn 'isPlaying = ' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastScreen.kt` = 0（`isNowPlaying` へ）
- `grep -rn 'nowPlaying' app/src/main/java/com/rioikeda/newslisten/podcast/AudioPlayerSection.kt app/src/main/java/com/rioikeda/newslisten/podcast/PodcastScreen.kt app/src/main/java/com/rioikeda/newslisten/podcast/QueueSheet.kt` ≥ 1 ずつ。
- T-T17 が `verifies: CI-T17` をテスト名またはコメントに持つ。
- grep oracle 回帰なし: `error("` = 0。`code == 401` の一致は `network/AuthInterceptor.kt:48` と `network/OkHttpApiClient.kt:459` の 2 箇所から増えない（対象集合 = `app/src/main`）。
- `PlayerController` の公開面: `state` / `isPlaying` / `positionSeconds` / `durationSeconds` / `playbackSpeed` / `prepare` / `play` / `pause` / `seekTo` / `setSpeed` / `stop` / `release`（`onPlaybackCompleted` 無し）。

## 禁止事項 / scope 外
- 新しい状態・操作・field を足さない（`NowPlaying` の 7 field を増やさない。`durationSeconds` は player が正本）。
- 挙動を変えない（行 UI のハイライト条件・`AudioPlayerSection` の表示条件は A-S2b2 の `nowPlaying` の非 null 条件どおり。`stopPlayback` の削除で test の期待値を変えない）。
- `isPlaying`（派生値）は削除しない（Spec: 段階移行のため残す）。
- CI の変更（A-S3）・主体別キャッシュ（A-S4）はしない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep の結果を PR 本文に貼る。

## 記録
- TP-A1・TP2 を閉じた旨を PR 本文に書く。
- 親 docs への返却事項: A-S2a〜c 完了で `design/android-design.md` §7.2「現在再生中の正本」「速度」「再生状態と失敗」「完聴の順序」「位置同期の送信条件」「Queue の不変条件」の行を現状記述へ書き換えられる旨。

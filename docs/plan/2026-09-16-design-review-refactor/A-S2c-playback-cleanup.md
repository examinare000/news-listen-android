## android リファクタ A-S2c: 旧再生実装の削除（`_currentPodcast`・TP-A1・TP2・設定画面の速度 5 段・UI 3 ファイルの読み替え完了）

## 概要
A-S2b2 で入口を差し替えた後に残った旧実装と暫定互換を**削除だけ**する。挙動変更は無い（A-S2b2 の変更行以外は不変のまま）。正本は Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1 INV-P1 の UI 読み替え・§4 CI-T17・§5 naming_decisions・leakage guard・§6 TP2）、親 docs `docs/design/android-design.md` §7.2（現在再生中の正本・速度）・§7.3（A-S2c 行）。旧 `S2-playback.md` を 3 段に分けた第 3 段（親 plan「3 段分割の型 ③」）。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T17 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S2b2a → A-S2b2b → **A-S2c** → A-S3（A-T3a1 は A-S2c と A-S4a の後）。本文の「A-S2b2」は A-S2b2a・A-S2b2b の 2 つを合わせた呼び名（2026-10-01 に order を 2 ファイルに分けた）。

> **2026-10-01 目標アーキテクチャ（ADR-110・Spec §8.3）による補正**（正本: `android/docs/design/2026-09-30-implementation-spec-target-architecture.md` §8.3「A-S2c」の 7 項目と導出 A-13・A-20（§10.1）。本文を直接直した。番号は Spec の補正番号）:
> 1. 「A-S2b2 で `Stopped` へ写した」→「`NothingPlaying` を代入する形へ写した」（SG-C51）。
> 2. `stopPlayback` の置換の分母を 2026-09-30 の実測（一致 33 行 = 呼出 29・コメント 3・テスト名 1）に直した。3. `:317-340` の 1 件は置き換えず、操作の削除に合わせてテストを削除する（完了条件の「置換のみ」の例外）。
> 4. main の `stopPlayback` の分母を 7 行（宣言 1・コメント 6）に直した。5. 恒久の契約「Android は明示の停止の入口を持たない」（A-13）を書いた。PS-09 の観測点は A-S2c の後は `stopForSubjectLeave()`。
> 6. 対象に「公開の `play(episode)` を非公開にし、テストの 25 箇所を `playNow(` に置換」を足した（A-20）。7. 完了条件に「TA-V5 の許可リストから `onPlaybackCompleted` が消える（公開の `var` 3 → 2）」を足した。
> 補正の外で直した点: 型の語（DTO → `Episode`）、「着手前の前提点検」の節、許可リストの完了条件。

## 前提・着手条件
- 依存 slice: **A-S2b2b**（A-S2b2 の後半。前半 A-S2b2a はその前に入っている）の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。merge 済みの状態で `nowPlaying` / `session` が存在し、`PodcastViewModel` が `state.Ended` だけを購読している（TP2 の削除条件）。
- baseline: A-S2b2b 完了時点の全 unit テストが green。
- **着手前の前提点検**（下記の行番号は 2026-09-23 と 2026-09-30 の実測で、A-T2b と A-S2b2 が `PodcastViewModel` とテストを書き換えるため位置はずれる。投入の直前に `android/` で数え直し、違えば order を直してから投入する。完了条件は行番号ではなく grep の 0 件で判定する）:
  - `grep -n 'stopPlayback' app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt`（2026-09-30: 33 行。内訳は呼出 29・コメント 3（`:190,315,535`）・テスト名 1（`:318`）。呼出 29 のうち 27 は末尾の後片付け（次の行が `}`）、1 は途中の停止（`:509`）、1 は `stopPlayback` そのものの検証（`:330`。テストは `:317-340`））。A-S2b2 が T-PS09 を足しているので、その分は「T-PS09 のテスト」として別に数える。
  - `grep -rn 'stopPlayback' app/src/main`（2026-09-30: 7 行 = 宣言 `podcast/PodcastViewModel.kt:475` とコメント 6 行 `:292,473,494,506,519`・`podcast/PlayerController.kt:14`）。
  - `grep -c 'viewModel\.play(' app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt` = 25。`grep -rn 'currentPodcast' app/src/main | wc -l`（2026-09-30: 30）、`app/src/test`（27）。`grep -rn 'onPlaybackCompleted\|completePlayback' app/src`（2026-09-23: main 5・test 8）。`grep -rn 'PLAYBACK_SPEEDS' app/src`（7 行。`settings/SettingsScreen.kt:182,183,654,658,659,1201,1361`）。
  - A-T1 の許可リスト（`test/…/architecture/Allowlist.kt`）に TA-V5 の公開の `var` 3 件（`podcast/PlayerController.kt:45` `onPlaybackCompleted`・`feed/FeedViewModel.kt:43`・`engagement/ListeningStreakStore.kt:38`）が載っていること。
- Selection Gate 依存なし。
- 棄却済み案（再提案しない）: `currentPodcast` を公開名として残す案（正本一意・SG-R1）。
- `docs/trial-log/`（android・親）を最初に読む。

## 対象（android サブモジュールのみ。削除／読み替えの列挙。2026-09-23 の grep 実測を分母にする）
**削除（main）**
1. `podcast/PodcastViewModel.kt`: `_currentPodcast`（`:83`）・`currentPodcast`（`:86`。TP-A1 の派生値）・session 適用関数内の `_currentPodcast` 書込（A-S2b2 で 1 箇所に集約済み）・`stopInternal(keepCurrentPodcast)` の引数と分岐（`:502-510` 相当。A-S2b2 で呼出 0）・コメント中の `currentPodcast` 言及（`:343-344, 349, 412, 484, 494`）。2026-09-23 時点の main の `currentPodcast` 一致は本ファイル 14 箇所（上記のほかコード行 `:325, 351, 391, 401` は A-S2b2 が `session` 参照へ書き換える）＋ `AppContainer.kt` 2 ＋ UI 3 ファイル 14 = **30**。加えて **`stopPlayback()`**（A-S2b2 で「位置同期 1 回 → `stop()` → `NothingPlaying` を代入」へ写した暫定の公開操作。§7.2 の残責務 15 操作に無く main の呼出 0。main の分母は宣言 1 とコメント 6 の 7 行）を削除し、`PodcastViewModelTest` の呼出 29 箇所のうち **末尾の後片付け 27 と途中の停止 1（`:509`）を `stopForSubjectLeave()` に置き換える**（後ろの assert は位置の送信を見ていない。期待値は変えない）。**`:317-340` の 1 件（`stopPlayback` そのものの検証）は置き換えず、操作の削除に合わせてテストを削除する**（期待値「停止で位置を 1 回送る」「`stop()` 1 回」「以後は送らない」は、`stopForSubjectLeave()` が位置を送らないので保てない。「停止直前の 1 回」は切り替えのテスト（`:522` からの「直接切り替える」）、「以後は送らない」は T-T19 と T-SL01p が持つ）。コメント 3 行・テスト名 1 行は `stopForSubjectLeave` の語へ直す。A-S2b2 の T-PS09 は観測点を `stopPlayback()` から `stopForSubjectLeave()` へ書き換える（期待値「`NothingPlaying`・`stop()` 1 回・`NothingPlaying` からは何も起きない」は同じ）。
1b. `podcast/PodcastViewModel.kt` の公開の **`play(episode)`** を非公開にし（A-S2b2 の補正 8・導出 A-20。手動の開始の入口は `playNow` だけにする）、`PodcastViewModelTest` の `viewModel.play(` 25 箇所を `playNow(` に置き換える（期待値は変えない。A-S2b2 で `play` は `playNow` と同じ手動の開始になっている）。
1c. **恒久の契約（導出 A-13。SG-C51・SG-C63 と同じ書き方）**: Android は明示の停止の入口を持たない。停止は「Coordinator が `NothingPlaying` を代入し、player の読み込みを外す」で、主体離脱（`stopForSubjectLeave()`）と、開始の切り替えの内部にだけ現れる。入口を足すときは SG-C51 に従う。PS-09 のテストは、A-S2c の後は `stopForSubjectLeave()` で観測する。
2. `podcast/PlayerController.kt:45` `onPlaybackCompleted`（TP2。TA-V5 の公開の `var` 3 件の 1 つ）・`podcast/ExoPlayerController.kt:67, 88-90` の override と `invoke`（`STATE_ENDED` は `state = Ended` の発行だけ残す）。
3. `settings/SettingsScreen.kt:1361` `PLAYBACK_SPEEDS`（5 段）。参照 `:182-183, 654, 658-659, 1201` は `PlaybackConstants.speeds`（A-S2b1 で Double 8 段）へ置換。
4. `di/AppContainer.kt:338, 342` のコメント中の `currentPodcast` 言及を `nowPlaying` へ。

**読み替え（main。挙動不変）**
5. `podcast/AudioPlayerSection.kt:65, 75, 101, 115, 125-126, 142`: `viewModel.currentPodcast` → `viewModel.nowPlaying`（`id` → `episodeId`、他 field は同名）。
6. `podcast/PodcastScreen.kt:53（コメント）, 61, 160, 204`: `nowPlaying` へ。`:160` の `isPlaying = currentPodcast?.id == podcast.id` は `isNowPlaying = nowPlaying?.episodeId == episode.id`（一時停止中もハイライト維持＝現状の観測挙動。行の要素は A-T2b で `Episode`）。`:204` の表示条件は `nowPlaying != null`。
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
3. `PodcastViewModelTest` の `currentPodcast` 27 箇所と `stopPlayback` の呼出 28 箇所を置換、`:317-340` の 1 件を削除、`play(` 25 箇所を `playNow(` へ（1・1b・9）→ green。
4. `_currentPodcast` / `currentPodcast` / `keepCurrentPodcast` / `stopPlayback`・コメントを削除（1・4）→ green。
5. `onPlaybackCompleted` を interface・ExoPlayer・Fake から削除（2・10）→ green。
6. `SettingsScreen.PLAYBACK_SPEEDS` を削除し `PlaybackConstants.speeds` へ（3）→ `SettingsViewModelTest` 24 件 green。
7. T-T17（構造検査）を追加 → green。1 slice = 1 PR。temporary path なし（TP-A1・TP2 を本 slice で閉じる）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。既存テストの期待値を変えていない（置換のみ。**例外は `PodcastViewModelTest.kt:317-340` の `stopPlayback` そのものの検証 1 件で、操作の削除に合わせて削除する**。PR 本文に削除した理由と、期待値を引き継ぐテスト（切り替え `:522`〜・T-T19・T-SL01p）を書く）。
- **参照 0 件**（対象集合 = `app/src/main` と `app/src/test` の全 Kotlin。除外範囲 = `docs/`・`build/`）:
  - `grep -rn 'currentPodcast\|_currentPodcast\|keepCurrentPodcast' app/src` = 0
  - `grep -rn 'onPlaybackCompleted\|completePlayback' app/src` = 0（2026-09-23 実測: main 5・test 8）
  - `grep -rn 'stopPlayback' app/src` = 0（2026-09-30 実測: main 7 行 = 宣言 `PodcastViewModel.kt:475`・コメント `:292,473,494,506,519`・`PlayerController.kt:14`、test 33 行）
  - `grep -n 'fun play(' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` の一致が `private` か 0（公開の `play` が無い）。`grep -c 'viewModel\.play(' app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt` = 0
  - `grep -rn 'PLAYBACK_SPEEDS' app/src` = 0（2026-09-23 実測: `SettingsScreen.kt` 7 箇所）
  - `grep -rn 'isPlaying = ' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastScreen.kt` = 0（`isNowPlaying` へ）
- `grep -rn 'nowPlaying' app/src/main/java/com/rioikeda/newslisten/podcast/AudioPlayerSection.kt app/src/main/java/com/rioikeda/newslisten/podcast/PodcastScreen.kt app/src/main/java/com/rioikeda/newslisten/podcast/QueueSheet.kt` ≥ 1 ずつ。
- T-T17 が `verifies: CI-T17` をテスト名またはコメントに持つ。
- grep oracle 回帰なし: `error("` = 0。`code == 401` の一致は `network/AuthInterceptor.kt:48` と `network/OkHttpApiClient.kt:459` の 2 箇所から増えない（対象集合 = `app/src/main`）。
- `PlayerController` の公開面: `state` / `isPlaying` / `positionSeconds` / `durationSeconds` / `playbackSpeed` / `prepare` / `play` / `pause` / `seekTo` / `setSpeed` / `stop` / `release`（`onPlaybackCompleted` 無し）。
- TA-V5（A-T1）: 許可リストから `podcast/PlayerController.kt` の `onPlaybackCompleted`（公開の `var`）が消え、公開の `var` の許可は 3 → 2（残り = `feed/FeedViewModel.kt` `onStarConfirmed`（A-T8a）・`engagement/ListeningStreakStore.kt` `onStreakIncreased`（A-T7a））。許可リストのほかの件数は増えていない。`ArchitectureStructureTest` が green。

## 禁止事項 / scope 外
- 新しい状態・操作・field を足さない（`NowPlaying` の 7 field を増やさない。`durationSeconds` は player が正本）。明示の停止の入口を足さない（A-13）。`PodcastResponse`・`model.*` を `podcast/` の新しいコードに持ち込まない。
- 挙動を変えない（行 UI のハイライト条件・`AudioPlayerSection` の表示条件は A-S2b2 の `nowPlaying` の非 null 条件どおり。`stopPlayback` の削除で test の期待値を変えない）。
- `isPlaying`（派生値）は削除しない（Spec: 段階移行のため残す）。
- CI の変更（A-S3）・主体別キャッシュ（A-S4）はしない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep の結果を PR 本文に貼る。

## 記録
- TP-A1・TP2 を閉じた旨を PR 本文に書く。
- 親 docs への返却事項: A-S2a〜c 完了で `design/android-design.md` §7.2「現在再生中の正本」「速度」「再生状態と失敗」「完聴の順序」「位置同期の送信条件」「Queue の不変条件」の行を現状記述へ書き換えられる旨。導出 A-13（明示の停止の入口を持たない）を共有仕様 §6.8 の Android 欄に注記する旨。A-T1 の許可リストの減少（TA-V5 3 → 2）。

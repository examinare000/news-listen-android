## android リファクタ A-S2b1: core / preferences / network の契約適用（Queue 不変条件・速度値域 8 段 Double・`invalidate` 操作）

## 概要
旧 A-S2b（入口差し替え）が抱えていた決定のうち、`PlaybackSession` に依存しない 3 つの確定済み契約を先に適用する: (1) `PlaybackQueue.init` の不変条件検査と `setQueue` の先勝ち dedupe、(2) 速度の値域を `PlaybackConstants.speeds: List<Double>` 8 段 1 本にし `setDefaultPlaybackSpeed` の値域外を拒否、(3) `AudioCacheManager.invalidate(id)` の追加。**`PodcastViewModel` の再生 use case は変えない**（`setSpeed` の引数型追随のみ）。正本は親 docs `docs/design/android-design.md` §7.2「Queue の不変条件」「速度」（値域と拒否の部分。既定速度のセッション適用は A-S2b2）「再生状態と失敗」（`invalidate` 操作の追加部分。Coordinator 側の遷移は A-S2b2）、Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §3.1（Queue・速度・PlaybackSource / OfflineLibrary）・§4 CI-T7・CI-T15・CI-T9（操作の存在部分）、共有仕様 §2・§6.6。2026-09-23 夜の点検で旧 A-S2b を「Coordinator に触らない契約」と「Coordinator の差し替え」の 2 境界で分けた第 1 段。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T7・CI-T15・CI-T9 の抜粋で足り、新しい契約 ID を作らない。

> **2026-09-30 の前提点検**: 現行 main（`5ffe1f4`）と照合した。件数と grep の到達点は本文どおり（テスト件数 32 / 52 / 5 / 6 / 14 / 24 / 45、`error("` 0 件、`code == 401` 2 件、`currentPodcast` main 30・test 27、`invalidate(` 0 件）。行番号のずれと、型を変えたときに落ちる既存テスト、書かれていなかった規則を直した。判断が要る 2 点は user が確定済み（親 docs 監査レポート §5 の **SG-C49・SG-C50**）。点検の記録は親 docs `research-reports/2026-09-30-wave3-order-premise-check.md`。再提案しない。

着手順: A-S2a → **A-S2b1** → A-S2b2 → A-S2c。**A-S4 は本 slice の android PR が main に入った後に投入する**（SG-C49。A-S4 は `AudioCacheManager` の全操作を主体つきに変えるため、先に入ると本 slice の `invalidate(id)` と `isCached(id)` を使うテストが成り立たない）。

## 前提・着手条件
- 依存 slice: A-S2a の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。技術的な依存は A-S1 だけで A-S2a とファイルは重ならないが、同一 submodule の slice は直列で投入する。
- baseline: A-S2a 完了時点の全 unit テストが `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` で exit 0。
- Selection Gate: 本 slice で新たに効くものは無い（SG-X5 は iOS 向け。Android の 8 段は Spec で確定済み）。
- 正本間の差の扱い（決定ではなく採用先の明記）: CI-T15 の拒否時の挙動は、Spec §4 が「既定へ正規化」、親 docs §7.2 が「直前の妥当値を維持」と書いている。**親 docs §7.2 を採る**（本フォルダの README「正本」の順。Spec 側の文言は A-S2 系完了時の docs 反映で §7.2 に揃える）。
- 棄却済み案（再提案しない。Spec §5 rejected_overdesign）: `Episode` decode、`PlaybackService` の interface 化。
- `docs/trial-log/`（android・親）を最初に読む。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `core/PlaybackQueue.kt`: `init` で不変条件 1〜3（id 一意・`currentIndex` 範囲内・空なら `current == null`）を `require` で検査し、違反は throw（programmer error）。`setQueue` は入力を先勝ちで dedupe してから構築する。**開始位置は元の入力で clamp して「開始する id」を決め、dedupe 後のその id の位置を `currentIndex` にする**（SG-C50。共有仕様 §2.4 と Q-33。web の `lib/playback/queue.ts` と同じ）。`copy` も `init` を通る。他の公開操作（Q-01〜Q-32）は不変。現行の production は `setQueue` と `start` を呼んでいない（定義だけ）ので、この変更で既存の呼出経路は変わらない。
2. `podcast/PlaybackConstants.kt`: `speeds: List<Float>`（現行 `:12`）を `speeds: List<Double>`（0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0 / 2.5）にする。値域の正本は Double 1 本。
3. `podcast/PodcastViewModel.kt:467` `fun setSpeed(speed: Float)` → `fun setSpeed(speed: Double)` にし、`playerController.setSpeed(speed.toFloat())` へ境界で変換する（**型追随のみ**。`playbackSpeed: StateFlow<Float>` と `PlayerController.setSpeed(Float)` は不変）。
4. `podcast/AudioPlayerSection.kt:282-285`（`SpeedSegmentedControl` の呼出側）: `speeds = PlaybackConstants.speeds.map { it.toFloat() }` を渡し、`onSpeedChange = { viewModel.setSpeed(it.toDouble()) }` に変える。**`SpeedSegmentedControl`（`:471-473` の引数、`:486` の比較 `speed == currentSpeed`）と `speedLabel(speed: Float)`（`:530`）は Float のまま触らない**（`playbackSpeed: StateFlow<Float>` が不変なので、部品の中を Double にすると Float との比較がコンパイルできない）。0.5〜2.5 の 8 値は 2 進で厳密に表せるので、往復の変換で誤差は出ない。
5. `preferences/DataStorePreferencesStore.kt`・`InMemoryPreferencesStore.kt`: `setDefaultPlaybackSpeed(value)` は `PlaybackConstants.speeds` に無い値を拒否し、直前の妥当値を維持する（初期値は現行既定のまま）。`AuthViewModel.syncPreferences` の server 同期経路も同じ関数を通るため追加の分岐は要らない。**server から 8 段外の値（backend は `gt=0` しか検証しない）が来た場合は、無言で無視して直前の妥当値を保つ**（§7.2 どおり。server と local が食い違うが、Android の UI は 8 段外を送らないので実害は無い）。`SettingsViewModel.syncDefaultPlaybackSpeed` の戻り値と、`InMemoryPreferencesStore` の初期値引数・DataStore に既に入っている値の読み出し側は変えない。
6. `network/AudioCacheManager.kt`: `invalidate(id)` を追加（`remove(id)` と同じ削除だが意味を分ける。`validateId` を通し、不正な id は `remove` と同じく `AudioCacheException.InvalidId` を投げる。存在しない id は何もしない＝冪等）。**呼び手は A-S2b2 で接続する**（本 slice では main の呼出 0）。

**変更（test）**: `core/PlaybackQueueConformanceTest.kt`（T-T7 を追加。既存 32 件は名前・期待値とも不変）、`preferences/DataStorePreferencesStoreTest.kt`・`InMemoryPreferencesStoreTest.kt`（T-T15）、`network/AudioCacheManagerTest.kt`（`invalidate` の観測）、`podcast/PodcastViewModelTest.kt:402,404`（`setSpeed` の引数型追随。`:404` の `assertEquals(PlaybackConstants.speeds, player.speedCalls)` は、`speeds` が `List<Double>` になると `speedCalls: MutableList<Float>`（`FakePlayerController.kt:51`）と要素の型が違い、コンパイルは通るが**実行時に落ちる**。期待値を `PlaybackConstants.speeds.map { it.toFloat() }` に変える。期待する速度の並びは不変で、型の変換だけを足す）。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T7 | `PlaybackQueue.init` が不変条件 1〜3 違反で throw、`setQueue` は先勝ち dedupe、Q-01〜Q-32 不変 | T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）と `copy` が throw、`setQueue([a,a,b], 0)` → `[a,b]`、**`setQueue([a,b,a,c], 2)` → `items=[a,b,c]`・`current=a`（Q-33。テスト名に行 ID を含める）** |
| CI-T15 | `setDefaultPlaybackSpeed` は 8 段以外を拒否して直前の妥当値を維持（§7.2） | T-T15: 境界値（0.0・3.0・1.0001・負値 −1.0・NaN）を拒否し直前値を維持。`DataStorePreferencesStoreTest`・`InMemoryPreferencesStoreTest` の両方。DataStore は書込みが非同期なので、「妥当値 1.5 を書く → `advanceUntilIdle()` → 拒否される値を書く → `advanceUntilIdle()` → 1.5 のまま」の順で確かめる（`advanceUntilIdle()` を挟まないと、書込みが反映される前に「維持」と誤判定する。既存 `DataStorePreferencesStoreTest.kt:86-101` と同じ型） |
| CI-T9（操作の存在部分） | `invalidate(id)` 後は `isCached(id) == false`・`cachedFileUri(id) == null` | T-T9 のうち `AudioCacheManagerTest` の観測。不正な id で `InvalidId`、存在しない id で例外なし（冪等）も足す（Coordinator 経由の `Failed(Source\|Decode)` → `invalidate` → `Errored(Player)` は A-S2b2 の T-T9） |

## 特性テスト（baseline。着手前に green）
`core/PlaybackQueueConformanceTest`（32）、`podcast/PodcastViewModelTest`（52）、`preferences/DataStorePreferencesStoreTest`（5）、`preferences/InMemoryPreferencesStoreTest`（6）、`network/AudioCacheManagerTest`（14）、`settings/SettingsViewModelTest`（24）、`auth/AuthViewModelTest`（45。`syncPreferences` 経路）。

## 手順
1. baseline green を記録。
2. T-T7 → RED → `PlaybackQueue` → GREEN（既存 32 件不変を確認）。
3. T-T15 → RED → `PlaybackConstants.speeds: List<Double>` → `PodcastViewModel.setSpeed(Double)`・`AudioPlayerSection.kt:282-285`・`PodcastViewModelTest.kt:402,404` の型追随 → `DataStorePreferencesStore` / `InMemoryPreferencesStore` の拒否 → GREEN。
4. `AudioCacheManagerTest` に `invalidate` の観測 → RED → `AudioCacheManager.invalidate` → GREEN。
5. 1 slice = 1 PR。temporary path なし。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。既存 `PlaybackQueueConformanceTest` 32 件（Q-01〜Q-32）が名前・期待値とも不変。
- T-T7・T-T15 が `verifies: CI-T7` / `CI-T15` を、`AudioCacheManagerTest` の追加分が `verifies: CI-T9` をテスト名またはコメントに持つ。
- `grep -n 'val speeds' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackConstants.kt` の要素型が `List<Double>`。`grep -n 'fun setSpeed' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` の引数型が `Double`。
- `grep -rn 'invalidate(' app/src/main` の一致は、コメント行を除いて `network/AudioCacheManager.kt` の宣言 1 行のみ（呼出 0。対象集合 = `app/src/main` の全 Kotlin。着手前は 0 件。KDoc やコメントに `invalidate(` と書くと一致が増えるので、説明では括弧を付けずに書く）。
- `SettingsScreen.kt:1361` `PLAYBACK_SPEEDS`（5 段。`listOf(0.75, 1.0, 1.25, 1.5, 2.0)` の Double）は本 slice では触らない（5 段は 8 段の部分集合で CI-T15 と両立。削除は A-S2c）。
- grep oracle 回帰なし: `error("` = 0（A-S1）。`code == 401` の一致は `network/AuthInterceptor.kt:48`（発火条件）と `network/OkHttpApiClient.kt:459`（`validateResponse` の写像）の 2 箇所から増えない（A-S0 の到達点。対象集合 = `app/src/main`）。`currentPodcast` の件数は本 slice で変えない（`grep -rn 'currentPodcast' app/src/main | wc -l` = 30、`app/src/test` = 27）。
- レビュー観点: `core/` が `kotlinx.coroutines`・`android.*`・`network` を import しない。`PodcastViewModel` の再生 use case（A-S2b2 が書き換える 8 関数: `play` / `playNow` / `playNext` / `addToQueue` / `handlePlaybackEnded` / `stopInternal` / `startPositionSync` / `syncPosition`）に差分が無い。

## 禁止事項 / scope 外
- `PodcastViewModel` の再生 use case・`PlaybackSession` の接続・`nowPlaying`・`stopForSubjectLeave`・既定速度のセッション適用（PS-08）はしない（A-S2b2）。
- `invalidate` を呼ぶ経路を作らない（A-S2b2）。
- UI 3 ファイルの読み替え・`SettingsScreen.PLAYBACK_SPEEDS` の削除・`_currentPodcast` の削除はしない（A-S2c）。
- CI-T15 の拒否時挙動を「既定へ正規化」にしない（親 docs §7.2 を採る。上記「前提」）。
- 棄却済み案を持ち込まない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 完了条件の grep 6 本（`val speeds`・`fun setSpeed`・`invalidate(`・`error("`・`code == 401`・`currentPodcast` の件数）の結果を PR 本文に貼る。コマンドはすべて `android/` で実行する。

## 記録
- 逸脱・棄却があれば `android/docs/trial-log/` に追記。
- 親 docs への返却事項: 共有仕様 §2（Queue の不変条件）と §6.6「速度の選択肢は 8 段」の android 追随は本 slice で満たす。§4.4 の PS-* 保留解除は A-S2b2 の完了時。

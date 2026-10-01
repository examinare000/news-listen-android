## android リファクタ A-S2b1: core / preferences / network の契約適用（Queue 不変条件・速度値域 8 段 Double・`invalidate` 操作）

## 概要
旧 A-S2b（入口差し替え）が抱えていた決定のうち、`PlaybackSession` に依存しない 3 つの確定済み契約を先に適用する: (1) `PlaybackQueue.init` の不変条件検査と `setQueue` の先勝ち dedupe、(2) 速度の値域を `PlaybackConstants.speeds: List<Double>` 8 段 1 本にし `setDefaultPlaybackSpeed` の値域外を拒否、(3) `AudioCacheManager.invalidate(id)` の追加。**`PodcastViewModel` の再生 use case は変えない**（`setSpeed` の引数型追随のみ）。正本は親 docs `docs/design/android-design.md` §7.2「Queue の不変条件」「速度」（値域と拒否の部分。既定速度のセッション適用は A-S2b2）「再生状態と失敗」（`invalidate` 操作の追加部分。Coordinator 側の遷移は A-S2b2）、Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md` §3.1（Queue・速度・PlaybackSource / OfflineLibrary）・§4 CI-T7・CI-T15・CI-T9（操作の存在部分）、共有仕様 §2・§6.6。2026-09-23 夜の点検で旧 A-S2b を「Coordinator に触らない契約」と「Coordinator の差し替え」の 2 境界で分けた第 1 段。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T7・CI-T15・CI-T9 の抜粋で足り、新しい契約 ID を作らない。

> **2026-10-01 目標アーキテクチャ（ADR-110・Spec §8.3）による補正**（正本: `android/docs/design/2026-09-30-implementation-spec-target-architecture.md` §8.3「A-S2b1」の 2 項目。本文を直接直した）:
> - 補正 1（TA-R-PF3・導出 A-25）: 速度の値域の判定を `podcast/PlaybackConstants.kt` の純粋な関数 1 つに置き、`DataStorePreferencesStore` と `InMemoryPreferencesStore` はそれを呼ぶだけにする（対象 2・5 と契約 T-T15 を書き換えた。関数の表のテストを T-T15 に足した）。
> - 補正 2（A-T1）: 着手順を A-T1 → A-S2b1 に改め（A-T1 は test だけで main に触らない）、完了条件に「`ArchitectureStructureTest` の許可リストが増えていない」を足した。A-T1 が `PlaybackQueue` の実行時カプセル化テスト（TA-V6）を「A-S2b1 で green にする」と印を付けて登録した場合、本 slice が `init` / `setQueue` の写しの範囲で green にする（Spec §8.4 A-T1 の注記）。
> - 前提点検の値は 2026-10-01 に main `7e400f0b` で数え直した（`error("` 0 件、`code == 401` 2 件、`currentPodcast` main 30・test 27、`invalidate(` 0 件、`PodcastViewModelTest` 52 件、`PlaybackQueueConformanceTest` 32 件）。A-T1 は main を変えないので、この値は A-T1 の後も変わらない。

> **2026-09-30 の前提点検**: 現行 main（`5ffe1f4`）と照合した。件数と grep の到達点は本文どおり（テスト件数 32 / 52 / 5 / 6 / 14 / 24 / 45、`error("` 0 件、`code == 401` 2 件、`currentPodcast` main 30・test 27、`invalidate(` 0 件）。行番号のずれと、型を変えたときに落ちる既存テスト、書かれていなかった規則を直した。判断が要る 2 点は user が確定済み（親 docs 監査レポート §5 の **SG-C49・SG-C50**）。点検の記録は親 docs `research-reports/2026-09-30-wave3-order-premise-check.md`。再提案しない。

着手順: A-S2a → A-T1 → **A-S2b1** → A-T2a → A-T2b → A-S2b2a → A-S2b2b → A-S2c（Spec §8.1。本文の「A-S2b2」は A-S2b2a・A-S2b2b の 2 つ、「A-S4」は A-S4a・A-S4b の 2 つを合わせた呼び名）。**A-S4a は本 slice の android PR が main に入った後に投入する**（SG-C49。A-S4 は `AudioCacheManager` の全操作を主体つきに変えるため、先に入ると本 slice の `invalidate(id)` と `isCached(id)` を使うテストが成り立たない）。

## 前提・着手条件
- 依存 slice: A-S2a と **A-T1**（`test/…/architecture/ArchitectureStructureTest.kt`・`LayerMap.kt`・`Allowlist.kt`・`core/PlaybackQueueEncapsulationTest.kt`）の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。技術的な依存は A-S1 だけで A-S2a・A-T1 とファイルは重ならないが、同一 submodule の slice は直列で投入する。
- baseline: A-T1 完了時点の全 unit テストが `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` で exit 0（A-T1 が「A-S2b1 で green にする」と印を付けて登録したテストがあれば、それは baseline の red として記録し、本 slice の完了条件に含める）。
- 着手前の前提点検（投入の直前に `android/` で数え直す。値が違えば order を直してから投入する）: `grep -rn 'invalidate(' app/src/main | wc -l` = 0、`grep -rn 'error("' app/src/main | wc -l` = 0、`grep -rn 'code == 401' app/src/main | wc -l` = 2、`grep -rn 'currentPodcast' app/src/main | wc -l` = 30・`app/src/test` = 27、`grep -c '@Test' app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt` = 52、`grep -c '@Test' app/src/test/java/com/rioikeda/newslisten/core/PlaybackQueueConformanceTest.kt` = 32、`grep -n 'val speeds' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackConstants.kt` = `:12`（`List<Float>`）、`grep -n 'fun setSpeed' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = `:467`（`Float`）。A-T1 の許可リスト（`test/…/architecture/Allowlist.kt`）の件数を記録する（完了条件で「増えていない」を判定する分母）。
- Selection Gate: 本 slice で新たに効くものは無い（SG-X5 は iOS 向け。Android の 8 段は Spec で確定済み）。
- 正本間の差の扱い（決定ではなく採用先の明記）: CI-T15 の拒否時の挙動は、Spec §4 が「既定へ正規化」、親 docs §7.2 が「直前の妥当値を維持」と書いている。**親 docs §7.2 を採る**（本フォルダの README「正本」の順。Spec 側の文言は A-S2 系完了時の docs 反映で §7.2 に揃える）。
- 棄却済み案（再提案しない。既存の Spec §5 rejected_overdesign）: `PlaybackService` の interface 化。`Episode` decode は棄却ではなく **A-T2a の範囲**になった（ADR-110 決定 8・新しい Spec §10.2）。本 slice では `Episode` を導入せず、DTO を前提にした新しいコードも書かない（`PlaybackQueue` は型引数のまま、速度の判定は `Double`、`invalidate(id)` は `String`。Spec §8.2）。
- `docs/trial-log/`（android・親）を最初に読む。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `core/PlaybackQueue.kt`: `init` で不変条件 1〜3（id 一意・`currentIndex` 範囲内・空なら `current == null`）を `require` で検査し、違反は throw（programmer error）。`setQueue` は入力を先勝ちで dedupe してから構築する。**開始位置は元の入力で clamp して「開始する id」を決め、dedupe 後のその id の位置を `currentIndex` にする**（SG-C50。共有仕様 §2.4 と Q-33。web の `lib/playback/queue.ts` と同じ）。`copy` も `init` を通る。他の公開操作（Q-01〜Q-32）は不変。現行の production は `setQueue` と `start` を呼んでいない（定義だけ）ので、この変更で既存の呼出経路は変わらない。
2. `podcast/PlaybackConstants.kt`: `speeds: List<Float>`（現行 `:12`）を `speeds: List<Double>`（0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0 / 2.5）にする。値域の正本は Double 1 本。**同じファイルに「選べる速度か」を返す純粋な関数 1 つ**（`fun isSelectableSpeed(value: Double): Boolean`。`speeds` に含まれるときだけ true。`NaN` は false。`kotlinx.coroutines`・`android.*`・`network` を import しない）を置く（Spec §8.3 A-S2b1 補正 1。TA-R-PF3「判定は domain の純粋な関数 1 つ。保存の adapter はそれを呼ぶだけ」・導出 A-25。関数名は Spec が定めていないので本 order が置く名前で、A-T5 で `core/PlaybackSpeed.kt` の型に置き換わる）。`speeds` の検索や比較式（`contains`・`==`・`any`）を持つのはこの関数だけで、ほかのファイルには書かない。
3. `podcast/PodcastViewModel.kt:467` `fun setSpeed(speed: Float)` → `fun setSpeed(speed: Double)` にし、`playerController.setSpeed(speed.toFloat())` へ境界で変換する（**型追随のみ**。`playbackSpeed: StateFlow<Float>` と `PlayerController.setSpeed(Float)` は不変）。
4. `podcast/AudioPlayerSection.kt:282-285`（`SpeedSegmentedControl` の呼出側）: `speeds = PlaybackConstants.speeds.map { it.toFloat() }` を渡し、`onSpeedChange = { viewModel.setSpeed(it.toDouble()) }` に変える。**`SpeedSegmentedControl`（`:471-473` の引数、`:486` の比較 `speed == currentSpeed`）と `speedLabel(speed: Float)`（`:530`）は Float のまま触らない**（`playbackSpeed: StateFlow<Float>` が不変なので、部品の中を Double にすると Float との比較がコンパイルできない）。0.5〜2.5 の 8 値は 2 進で厳密に表せるので、往復の変換で誤差は出ない。
5. `preferences/DataStorePreferencesStore.kt`・`InMemoryPreferencesStore.kt`: `setDefaultPlaybackSpeed(value)` は **`PlaybackConstants.isSelectableSpeed(value)` が false なら拒否**し、直前の妥当値を維持する（初期値は現行既定のまま）。2 つの adapter は判定関数を呼ぶだけで、`speeds` の検索や比較式を自分で持たない（Spec §8.3 A-S2b1 補正 1。同じ判定を 2 つの adapter に書く形にしない）。`AuthViewModel.syncPreferences` の server 同期経路も同じ関数を通るため追加の分岐は要らない。**server から 8 段外の値（backend は `gt=0` しか検証しない）が来た場合は、無言で無視して直前の妥当値を保つ**（§7.2 どおり。server と local が食い違うが、Android の UI は 8 段外を送らないので実害は無い）。`SettingsViewModel.syncDefaultPlaybackSpeed` の戻り値と、`InMemoryPreferencesStore` の初期値引数・DataStore に既に入っている値の読み出し側は変えない。
6. `network/AudioCacheManager.kt`: `invalidate(id)` を追加（`remove(id)` と同じ削除だが意味を分ける。`validateId` を通し、不正な id は `remove` と同じく `AudioCacheException.InvalidId` を投げる。存在しない id は何もしない＝冪等）。**呼び手は A-S2b2a で接続する**（本 slice では main の呼出 0）。

**変更（test）**: `core/PlaybackQueueConformanceTest.kt`（T-T7 を追加。既存 32 件は名前・期待値とも不変）、`preferences/DataStorePreferencesStoreTest.kt`・`InMemoryPreferencesStoreTest.kt`（T-T15）、`network/AudioCacheManagerTest.kt`（`invalidate` の観測）、`podcast/PodcastViewModelTest.kt:402,404`（`setSpeed` の引数型追随。`:404` の `assertEquals(PlaybackConstants.speeds, player.speedCalls)` は、`speeds` が `List<Double>` になると `speedCalls: MutableList<Float>`（`FakePlayerController.kt:51`）と要素の型が違い、コンパイルは通るが**実行時に落ちる**。期待値を `PlaybackConstants.speeds.map { it.toFloat() }` に変える。期待する速度の並びは不変で、型の変換だけを足す）。

## 契約（CI → T の対応）
| CI | 内容 | T-T |
|---|---|---|
| CI-T7 | `PlaybackQueue.init` が不変条件 1〜3 違反で throw、`setQueue` は先勝ち dedupe、Q-01〜Q-32 不変 | T-T7: 不正構築 3 例（index 範囲外・空で index 0・id 重複）と `copy` が throw、`setQueue([a,a,b], 0)` → `[a,b]`、**`setQueue([a,b,a,c], 2)` → `items=[a,b,c]`・`current=a`（Q-33。テスト名に行 ID を含める）** |
| CI-T15 / TA-R-PF3 | `setDefaultPlaybackSpeed` は 8 段以外を拒否して直前の妥当値を維持（§7.2）。判定は `PlaybackConstants.isSelectableSpeed` の 1 関数 | T-T15: **判定関数の表**（`podcast/PlaybackConstantsTest.kt` に新設。8 値は true、0.0・3.0・1.0001・−1.0・`NaN` は false。テスト名に `TA-R-PF3` を含める）＋ 境界値（0.0・3.0・1.0001・負値 −1.0・NaN）を拒否し直前値を維持。`DataStorePreferencesStoreTest`・`InMemoryPreferencesStoreTest` の両方。DataStore は書込みが非同期なので、「妥当値 1.5 を書く → `advanceUntilIdle()` → 拒否される値を書く → `advanceUntilIdle()` → 1.5 のまま」の順で確かめる（`advanceUntilIdle()` を挟まないと、書込みが反映される前に「維持」と誤判定する。既存 `DataStorePreferencesStoreTest.kt:86-101` と同じ型） |
| CI-T9（操作の存在部分） | `invalidate(id)` 後は `isCached(id) == false`・`cachedFileUri(id) == null` | T-T9 のうち `AudioCacheManagerTest` の観測。不正な id で `InvalidId`、存在しない id で例外なし（冪等）も足す（Coordinator 経由の `Failed(Source\|Decode)` → `invalidate` → `Errored(Player)` は A-S2b2 の T-T9） |

## 特性テスト（baseline。着手前に green）
`core/PlaybackQueueConformanceTest`（32）、`podcast/PodcastViewModelTest`（52）、`preferences/DataStorePreferencesStoreTest`（5）、`preferences/InMemoryPreferencesStoreTest`（6）、`network/AudioCacheManagerTest`（14）、`settings/SettingsViewModelTest`（24）、`auth/AuthViewModelTest`（45。`syncPreferences` 経路）。

## 手順
1. baseline green を記録。
2. T-T7 → RED → `PlaybackQueue` → GREEN（既存 32 件不変を確認）。
3. T-T15（判定関数の表 → 保存の拒否の順）→ RED → `PlaybackConstants.speeds: List<Double>` と `isSelectableSpeed` → `PodcastViewModel.setSpeed(Double)`・`AudioPlayerSection.kt:282-285`・`PodcastViewModelTest.kt:402,404` の型追随 → `DataStorePreferencesStore` / `InMemoryPreferencesStore` が `isSelectableSpeed` を呼んで拒否 → GREEN。
4. `AudioCacheManagerTest` に `invalidate` の観測 → RED → `AudioCacheManager.invalidate` → GREEN。
5. 1 slice = 1 PR。temporary path なし。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。既存 `PlaybackQueueConformanceTest` 32 件（Q-01〜Q-32）が名前・期待値とも不変。
- T-T7・T-T15 が `verifies: CI-T7` / `CI-T15` を、`AudioCacheManagerTest` の追加分が `verifies: CI-T9` をテスト名またはコメントに持つ。
- `grep -n 'val speeds' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackConstants.kt` の要素型が `List<Double>`。`grep -n 'fun setSpeed' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` の引数型が `Double`。
- 速度の判定が 1 箇所（TA-R-PF3）: `grep -rn 'isSelectableSpeed' app/src/main` の一致は `podcast/PlaybackConstants.kt` の宣言 1 行と、`preferences/DataStorePreferencesStore.kt`・`preferences/InMemoryPreferencesStore.kt` の呼出 1 行ずつの 3 行。`grep -rn 'speeds' app/src/main/java/com/rioikeda/newslisten/preferences` = 0（2 つの adapter が `speeds` を直接読まない。対象集合 = `preferences/` の全 Kotlin）。
- 許可リストが増えていない（A-T1）: `test/…/architecture/Allowlist.kt` の件数が着手前の記録と同じか少ない。`ArchitectureStructureTest` が green。
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
- 完了条件の grep 8 本（`val speeds`・`fun setSpeed`・`isSelectableSpeed`・`preferences/` の `speeds`・`invalidate(`・`error("`・`code == 401`・`currentPodcast` の件数）と、許可リストの件数（着手前 → 完了後）を PR 本文に貼る。コマンドはすべて `android/` で実行する。
- TA-V10（PR の説明）: 「速度の段を足したら」の問いに、本 slice の後で変わるファイルの一覧（`podcast/PlaybackConstants.kt` と、5 段が残る `settings/SettingsScreen.kt`）を書く。

## 記録
- 逸脱・棄却があれば `android/docs/trial-log/` に追記。
- 親 docs への返却事項: 共有仕様 §2（Queue の不変条件）と §6.6「速度の選択肢は 8 段」の android 追随は本 slice で満たす。§4.4 の PS-* 保留解除は A-S2b2 の完了時。

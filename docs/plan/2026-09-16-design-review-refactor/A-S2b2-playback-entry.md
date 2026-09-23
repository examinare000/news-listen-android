## android リファクタ A-S2b2: 再生の入口を `PlaybackSession` へ差し替える（`nowPlaying`・完聴順序・送信条件・既定速度適用・`invalidate` 接続・`stopForSubjectLeave`）

## 概要
`PodcastViewModel` の再生 use case（開始・完聴・位置同期・主体離脱時の停止）を A-S2a で新設した `PlaybackSession` / `ResumeRule` / `PlaybackState` 経由に差し替える。共有仕様 §2・Q-01〜Q-32 は**挙動不変**（特性テストで判定）。**変わる挙動は下記「変更行」に限る**（準拠テストで判定）。Queue の不変条件・速度値域・`invalidate` 操作の追加は A-S2b1 で適用済みであり、本 slice は **Coordinator（`PodcastViewModel`）と `AppContainer` の配線だけ**を変える。正本は Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1・§4 CI-T4〜T6・T8・T9・T19・T20・§5 CP2/CP5/CP6・§6 S2 行・TP2）、親 docs `docs/design/android-design.md` §7.2（「現在再生中の正本」「速度」の既定速度適用・「再生状態と失敗」「完聴の順序」「位置同期の送信条件」「`PodcastViewModel` に残る責務」）・§7.3（A-S2b 行）、共有仕様 §4.4・§6.4・§6.6、ADR-103。旧 `S2-playback.md` を 3 段に分けた第 2 段のうち、2026-09-23 夜の点検で A-S2b1（Coordinator に触らない契約）と分けた **Coordinator 差し替え側**。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T4〜T6・T8・T9・T19・T20 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S2b1 → **A-S2b2** → A-S2c。

## 前提・着手条件
- 依存 slice: A-S2a（`PlaybackSession`・`ResumeRule`・`PlayerController.state`・`FakePlayerController.setState`）と A-S2b1（`PlaybackQueue.init`・`speeds: List<Double>`・`setDefaultPlaybackSpeed` の拒否・`AudioCacheManager.invalidate`）の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。
- **入口条件**: 下記「特性テスト（baseline）」8 ファイル＋T-T20 が green。
- Selection Gate は確定済み（共有仕様 §6.7、ADR-103）。本 slice で実装する: **SG-X1** 完聴時に `duration` を 1 回明示送信（順序 `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。PS-06）。**SG-X4** 一時停止中は 15 秒周期送信をしない（PS-05b）。
- 棄却済み案（再提案しない）: 旧 `_currentPodcast` を owner のまま残す併存移行、`AuthState` 4 状態化、preferences 消去契約の追加（A-S4 で SG-A6 により別途扱う）、`Episode` decode。
- `docs/trial-log/`（android・親）を最初に読む。

## 変更行（挙動が変わるのはこの行だけ。準拠テスト名に行 ID を含める）
| 行 | 変わる内容 | 検証 |
|---|---|---|
| PS-01 | advance 後の NETWORK 取得失敗 → `Errored(FetchFailed)`・`queue.current` は次のまま・自動で進まない・player 停止（現行: `errorMessage` のみで session 概念なし） | T-T5 |
| PS-02 | PS-01 の状態から手動 `retry()` → `startEpisode(queue.current)` を再実行 | T-T5 |
| PS-03 | advance 後にオフライン未キャッシュ → network 取得なし・`Errored(SourceUnavailable)`・文言「オフライン」 | T-T6 |
| PS-05 | `Errored` / `Stopped` / `NothingPlaying` では位置同期を送らない（現行は `_currentPodcast ?: return` が代替） | T-T19 |
| PS-05b（SG-X4） | 一時停止中は周期送信しない。pause への遷移時に 1 回送る | T-T19b |
| PS-06（SG-X1） | 完聴順序 = `markCompleted`（1 セッション 1 回）→ `updatePlaybackPosition(duration)` 1 回 → `advance`（現行: `markCompleted` → advance → 次 play の `stopInternal` が現在位置を送る） | T-T8 |
| PS-08 | セッション速度は既定速度で開始し、セッション中の変更は既定速度を書き換えない（現行: 既定速度を player に適用していない） | T-T4 |
| CI-T4（resume 適用） | 開始位置に `resolveResumePosition` の値を適用（`resume > 0` のとき `seekTo`）。CACHED は一覧 DTO、NETWORK は fresh DTO の値 | T-T4 |
| CI-T9（Coordinator 部分） | CACHED 経路で `Failed(Source\|Decode)` → `invalidate(id)`（A-S2b1 で追加済み）→ `Errored(Player)`、次回は NETWORK | T-T9 |

PS-04（INV-P1）は A-S2a の述語を Coordinator の全公開操作後に assert する形で本 slice でも検証する。**PS-07 は対象外**（学習サイクル。`PodcastStatusBadge` の gate は現状維持）。CI-T7（Queue）・CI-T15（速度値域）は A-S2b1 で green 済みであり、本 slice では再検証しない（回帰は baseline で拾う）。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `podcast/PodcastViewModel.kt`（PlaybackCoordinator）: 追加 `session: StateFlow<PlaybackSession>`・`nowPlaying: StateFlow<NowPlaying?>`（`episodeId` / `displayTitle` / `japaneseIntroText` / `segments` / `vocabulary` / `quiz` / `difficulty` の 7 field。`NothingPlaying` と `Errored(episodeRef)` で `null`）・`retry()`・`stopForSubjectLeave()`。`PreferencesStore` をコンストラクタ注入（既定速度の読み手）。`play` / `playNow` / `playNext` / `addToQueue` / `handlePlaybackEnded` / `stopInternal` / `startPositionSync` / `syncPosition` を `startEpisode` / `onEnded` / 送信条件に置き換える。完聴の検知は `playerController.state` の `Ended` を購読（`onPlaybackCompleted` は登録しない）。`Errored` の `ref` は prepare 前の失敗（`NotPlayable` / `SourceUnavailable` / `FetchFailed`）では `episodeRef`、`Player` では `episode`。**`errorMessage: StateFlow<String?>` は維持**し、`Errored` への遷移時に現行と同じ文言（取得失敗は `e.message`、オフラインは「オフラインのため再生できません」、gate は `playabilityError`）を書く（`PodcastScreen.kt:60, 212, 216` の dialog は不変）。**`stopPlayback()`**（`:473`。main の呼出 0・`PodcastViewModelTest` の呼出 33 箇所）は §7.2 の残責務 15 操作に無いが本 slice では残し、「位置同期 1 回 → `stop()` → `Stopped`」（queue の `currentIndex` は末尾のまま）へ写す。削除は A-S2c。**TP-A1（暫定互換）**: `currentPodcast: StateFlow<PodcastResponse?>` は `session` からの派生値（`nowPlaying` と同じ非 null 条件で DTO を返す）として残し、`_currentPodcast` への書込は 1 箇所（session 適用関数）だけにする。owner: user、導入: A-S2b2、削除条件: UI 3 ファイルと `PodcastViewModelTest` が `nowPlaying` / `session` だけを読むようになった時（A-S2c）。`keepCurrentPodcast` 引数は `Stopped` が代替するため呼出 0 になる（シンボル削除は A-S2c）。
2. `di/AppContainer.kt`: `PodcastViewModel` へ `preferencesStore` を渡す。`onSubjectLeave` ラムダに `_podcastViewModel.stopForSubjectLeave()` を独立 try/catch で追加（順序: 停止 → `cancelDownloadsAndClearCache` → FCM。A-S0 で保留していた再生停止部分。A-S4 が先に merge されている場合は手順列 `CleanupStep` の 1 手順として追加する）。

**変更（test）**: `podcast/PodcastViewModelTest.kt`（T-T20 を baseline に先に追加。変更行に該当するテストだけを反転し、反転理由に行 ID を書く）、`network/AudioCacheManagerTest.kt` は本 slice では触らない（`invalidate` の観測は A-S2b1 で済み）。`stopForSubjectLeave()` は `PodcastViewModelTest` で観測する（位置同期 1 回 → `stop()` → `queue` 空 → `NothingPlaying` → `nowPlaying == null`。SL-01 の再生停止部分。`AppContainer` の配線は unit テスト対象外）。`FakePodcastApi`（A-S1）と `FakePlayerController.setState`（A-S2a）を使う。

**変更しない**: `AudioPlayerSection` / `PodcastScreen` / `QueueSheet` の `currentPodcast` 読み（TP-A1 で動く。読み替えは A-S2c）、`SettingsScreen.kt:1361 PLAYBACK_SPEEDS`（削除は A-S2c）、`PlayerController.onPlaybackCompleted` の宣言と `ExoPlayerController` の発火（TP2。削除は A-S2c）、`core/PlaybackQueue.kt`・`PlaybackConstants.kt`・`preferences/*`・`AudioCacheManager.kt`（A-S2b1 で完了）。

## 契約（CI → T の対応）
| CI | T-T |
|---|---|
| CI-T4（resume・既定速度。PS-08） | T-T4: `FakePlayerController` の `seekTo` / `setSpeed` 引数。既定速度 1.5 でセッション中 2.0 へ変更 → 次エピソードは 1.5、`PreferencesStore` は 1.5 のまま |
| CI-T5（INV-P1・PS-01・PS-02） | T-T5: 完聴 → advance → NETWORK 失敗の系列。`queue.current.id == session.ref.id`、`Errored(FetchFailed)`、`retry()` が再実行 |
| CI-T6（PS-03） | T-T6 |
| CI-T8（PS-06） | T-T8: `FakePodcastApi` の呼出列。同一セッション内で `Ended` を 2 回注入しても `markCompleted` は 1 回 |
| CI-T9（Coordinator 部分） | T-T9: `FakePlayerController.setState(Failed(Source))` → `cacheManager.invalidate` の呼出 → `Errored(Player)` → 次の `resolvePlaybackSource` が NETWORK |
| CI-T19（PS-05・PS-05b） | T-T19: `Errored(FetchFailed)` 後に timer を進めても `updatePlaybackPosition` なし。T-T19b: pause 後に timer を進めても pause 時の 1 回以外なし |
| CI-T20（baseline） | T-T20: 2 回連続 `playNow` 後の `updatePlaybackPosition` の id が最後の episode のみ |
| CI-T1 | T-T1（再確認）: Coordinator 経由（`FakePlayerController.setState` ＋ start / playNow / retry / advance）で A-S2a と同じ 11 遷移を観測 |

## 特性テスト（baseline。着手前に green。8 ファイル）
`podcast/PodcastViewModelTest`（52）、`core/PlaybackQueueConformanceTest`（32＋A-S2b1 の T-T7）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14＋A-S2b1 分）、`podcast/PlaybackMetadataTest`（5）、`podcast/PodcastStatusBadgeTest`（5）、`settings/SettingsViewModelTest`（24）、`preferences/DataStorePreferencesStoreTest`（5＋A-S2b1 の T-T15）。加えて **T-T20 を先に追加**して green にする。

## 手順
1. baseline 8 ファイル＋T-T20 green を記録。
2. T-T4・T-T5・T-T6・T-T8・T-T9・T-T19・T-T19b・T-T1（Coordinator）・PS-04 → RED → `PodcastViewModel` を `PlaybackSession` 経由へ差し替え（TP-A1 の派生 `currentPodcast`・`errorMessage` の維持・`stopPlayback` の `Stopped` 写像を含む）→ GREEN。8 特性テストが green のままであることを各段で確認。
3. `stopForSubjectLeave()`（`PodcastViewModelTest` で SL-01 の再生停止部分を観測）→ RED → 実装 → `AppContainer.onSubjectLeave` へ追加 → GREEN。
4. UV3（エミュレータ `newslisten_e2e`）: resume 位置の適用・既定速度の適用・次エピソード失敗時の停止表示・`onPlayerError` → `Failed`（CI-T2b）の 4 項目を手動観測し記録。
5. 1 slice = 1 PR。temporary path: TP-A1（本書 1）・TP2（`onPlaybackCompleted`。owner user、導入 2026-09-16 Spec、削除条件 = `PodcastViewModel` が `state.Ended` だけを購読する状態＝本 slice で満たす。削除は A-S2c）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。
- T-T1・T-T4〜T-T6・T-T8・T-T9・T-T19・T-T19b・T-T20 が `verifies: CI-T*` をテスト名またはコメントに持ち、PS-01・PS-02・PS-03・PS-04・PS-05・PS-05b・PS-06・PS-08 がテスト名に含まれる。
- 既存 `PlaybackQueueConformanceTest` 32 件（Q-01〜Q-32）が名前・期待値とも不変。
- 反転した既存テストはすべて上記変更行のいずれかを理由に持つ（PR 本文に「テスト名 → 行 ID」の表）。
- `grep -n 'onPlaybackCompleted' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0（登録しない。2026-09-23 時点は `:327` の 1 件）。`grep -n 'keepCurrentPodcast' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` の一致は宣言行のみ（呼出 0。2026-09-23 時点は `:368` 呼出・`:502, :509` 宣言と分岐）。`grep -rn '_currentPodcast.value =' app/src/main` = 1（session 適用関数）。
- `grep -rn 'invalidate(' app/src/main` の一致は `network/AudioCacheManager.kt` の宣言と `podcast/PodcastViewModel.kt` の呼出 1 箇所。
- grep oracle 回帰なし: `error("` = 0。`code == 401` の一致は `network/AuthInterceptor.kt:48` と `network/OkHttpApiClient.kt:459` の 2 箇所から増えない（対象集合 = `app/src/main`）。
- レビュー観点: `PlayerController` interface に Media3 の型が無い。`PodcastViewModel` の再生 use case が `PodcastApi` だけに依存（A-S1 の境界維持）。`core/` が `kotlinx.coroutines` を import しない。
- UV3 の 4 項目の観測結果が記録されている。

## 禁止事項 / scope 外
- 上記「変更行」以外の挙動を変えない（Q-01〜Q-32・`resolvePlaybackSource`・語彙登録・クイズ中継・ダウンロード・`PodcastStatusBadge` の gate・PS-07・`errorMessage` の文言）。
- UI 3 ファイルの `currentPodcast` → `nowPlaying` 読み替え、`_currentPodcast` / `keepCurrentPodcast` / `onPlaybackCompleted` / `stopPlayback` / `SettingsScreen.PLAYBACK_SPEEDS` の削除はしない（A-S2c）。
- `_currentPodcast` を owner にしない（書込 1 箇所・session からの派生のみ）。
- `core/PlaybackQueue.kt`・`PlaybackConstants.kt`・`preferences/*`・`AudioCacheManager.kt` の契約を変えない（A-S2b1 で確定）。
- 主体別キャッシュ・主体離脱の順序変更・logout ヘッダはしない（A-S4）。背景遷移時の位置同期は Android 現行に無く追加しない（Spec CI-T19 の契機は 15 秒・停止直前・完聴時の 3 つ）。
- 棄却済み案を持ち込まない。仕様にない業務条件を足さない。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 上記 grep 5 本の結果と UV3 の記録を PR 本文に貼る。

## 記録
- 反転した特性テストの一覧（テスト名 → 行 ID）を PR 本文と `android/docs/trial-log/` に残す。
- 親 docs への返却事項: 共有仕様 §4.4 の保留（PS-01〜PS-06・PS-08・android A-S2b）を解除できる旨。PS-07 は未追随のまま（学習サイクル）。親 plan の slice ID を A-S2b1 / A-S2b2 に揃える旨。

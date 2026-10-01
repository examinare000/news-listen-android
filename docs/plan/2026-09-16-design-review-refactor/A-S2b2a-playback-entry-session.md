## android リファクタ A-S2b2a: 再生の入口を `PlaybackSession` へ差し替える — 前半（`session`・`nowPlaying`・`startEpisode`・`retry`・既定速度適用・`invalidate` 接続）

> **2026-10-01 order の分割**: 旧 `A-S2b2-playback-entry.md`（≈ 1,020 行。1 ファイルに前半・後半の 2 PR）を、投入の道具（`.takt/enqueue-orders.mjs`。order ファイル単位で投入し、ID は「ファイル名が `<ID>-` で始まる」で解決する）に合わせて 2 ファイルに分けた。**本 order = A-S2b2a = 1 PR（前半: 現在再生中の正本）**。後半（完聴の順序・送信条件・主体離脱の停止）は [A-S2b2b-playback-sync-and-leave.md](A-S2b2b-playback-sync-and-leave.md)。下の「概要」「前提」「変更行」「対象」は 2 PR の全体を説明する（両 order で同じ文）。**本 PR の範囲は「本 order の範囲（A-S2b2a）」節が正**で、完了条件は本 PR だけで判定できる形にしてある。以下の本文の「A-S2b2」は A-S2b2a・A-S2b2b の 2 つを合わせた呼び名。

## 本 order の範囲（A-S2b2a）
- **変更行のうち本 PR が変えるもの**: PS-01・PS-02・PS-03・PS-08・PS-09（SG-C51。観測点は `stopPlayback()`）・PS-10・PS-11（SG-C62）・CI-T4（resume の適用）・CI-T9（Coordinator 部分）。PS-04（INV-P1）を全公開操作の後に assert する。**PS-05・PS-05b・PS-06・SL-01（再生停止部分）は A-S2b2b**。
- **対象**: 下の「対象」の 1 のうち、`session`・`nowPlaying`・`retry()`・`startEpisode`（手動の開始の判定と取得の順序・SG-C62・A-1・A-2・A-17）・開始を確定するときの辺の選び方（A-14）・`Errored` の代入（SG-C52）・`stopPlayback()` の `NothingPlaying` への写像（SG-C51）・既定速度の適用（`PreferencesStore` のコンストラクタ注入。A-3）・CACHED 経路の `invalidate` 接続・主体の固定（`sessionSubject`）・TP-A1（`currentPodcast` を `session` の派生値に、`_currentPodcast` の書込を 1 箇所に）。2 のうち `PodcastViewModel` へ `preferencesStore` を渡すこと。
- **player の事象の受け方（A-15）のうち本 PR で写すもの**: `Playing`・`Paused` の `Starting` の列（`playerStarted`）と、`Failed(reason)` の行。**完聴の検知は旧い経路のまま**: `onPlaybackCompleted`（TP2）が呼ぶ `handlePlaybackEnded` を、`session` を参照して `playerEnded` → advance（`Completed→Starting(advance)`。キューが尽きたら `Stopped`）を行う形へ書き換えるだけで、完聴の送信の順序（`markCompleted` の応答を待ってから advance する現行の形）と位置同期の契機（`syncPosition`・`startPositionSync` の周期・停止直前の 1 回）は**現行の挙動のまま**にする（`_currentPodcast` の代わりに `session` を読むだけ）。`Ended` の購読・周期の timer の張り直し・一時停止の 1 回・送らない 2 つの場合（A-16）・`onPlaybackCompleted` の非登録は A-S2b2b。
- **A-S4 との順序不定**: 下の「A-S4 が先に merge されている場合の追随」のうち (b)(c)(d) は **A-S4a** が先に入っている場合に本 PR が行う。(a)（`CleanupStep("playback_stop")`）は A-S2b2b の範囲。
- **着手条件**: 下の「前提・着手条件」のとおり（A-T2b まで）。
- **規模**: ≈ 600 行（下の「規模」）。

## 概要
`PodcastViewModel` の再生 use case（開始・完聴・位置同期・主体離脱時の停止）を A-S2a で新設した `PlaybackSession` / `ResumeRule` / `PlaybackState` 経由に差し替える。共有仕様 §2・Q-01〜Q-32 は**挙動不変**（特性テストで判定）。**変わる挙動は下記「変更行」に限る**（準拠テストで判定）。Queue の不変条件・速度値域・`invalidate` 操作の追加は A-S2b1 で適用済みであり、本 slice は **Coordinator（`PodcastViewModel`）と `AppContainer` の配線だけ**を変える。正本は Implementation Spec `android/docs/design/2026-09-16-implementation-spec-playback-auth.md`（§3.1・§4 CI-T4〜T6・T8・T9・T19・T20・§5 CP2/CP5/CP6・§6 S2 行・TP2）、親 docs `docs/design/android-design.md` §7.2（「現在再生中の正本」「速度」の既定速度適用・「再生状態と失敗」「完聴の順序」「位置同期の送信条件」「`PodcastViewModel` に残る責務」）・§7.3（A-S2b 行）、共有仕様 §4.4・§6.4・§6.6、ADR-103。旧 `S2-playback.md` を 3 段に分けた第 2 段のうち、2026-09-23 夜の点検で A-S2b1（Coordinator に触らない契約）と分けた **Coordinator 差し替え側**。**検証モード（再設計しない）**。generate_spec の spec.md は CI-T4〜T6・T8・T9・T19・T20 の抜粋で足り、新しい契約 ID を作らない。

着手順: A-S2b1 → A-T2a → A-T2b → **A-S2b2** → A-S2c（Spec §8.1。`Episode` を先に置く理由は Spec §8.2）。

> **2026-10-01 目標アーキテクチャ（ADR-110・Spec §8.3）による補正**（正本: `android/docs/design/2026-09-30-implementation-spec-target-architecture.md` §8.3「A-S2b2」の 15 項目と、導出 A-5・A-6・A-9・A-12・A-14〜A-17・A-20（§10.1）。本文を直接直した。番号は Spec の補正番号）:
> 1. 語を実物（`podcast/PlaybackSession.kt:81-89`）に合わせた: `Errored` の参照は `SessionEpisodeRef.IdOnly(id)`（開始前の失敗）と `SessionEpisodeRef.Loaded(episode)`（player の失敗）。旧「`episodeRef`」「`episode`」を直した。
> 2. 「開始を確定するときの辺の選び方」の表を足した（A-14）。3. 「player の事象の受け方」の表を足した（A-15）。4. 送る値の出どころを書いた（A-16）。5. 送らない 2 つの場合を書いた（A-16）。6. Fake の連動の範囲を書いた。
> 7. PS-09・PS-10・PS-11 をテスト名と完了条件に足した。8. `play(episode)` は `playNow` と同じ手動の開始として残す（A-20）。
> 9. 型を `PodcastResponse` → `Episode` / `Episode.Playable` に置き換えた（A-T2b の後に入るため）。10. 取り直した `Episode` が `Playable` でないときの扱いを足した（A-17）。
> 11. 「PS-07 は対象外」→「PS-07・PS-07b は A-T2b で適用済み」。禁止事項の「`PodcastStatusBadge` の gate」を外した。12. 禁止事項の契機を「周期・一時停止への遷移・停止直前・完聴の 4 つ。背景遷移は適用しない（A-12）」に直した。
> 13. T-T20 の文を直した。14. `NowPlaying` は `podcast/PlaybackSession.kt` に置き、A-T1 の対応表に「リードモデル（移行中は domain のファイルに同居）」として登録されている前提を書いた。15. 行番号は A-T2b の後に数え直す（「着手前の前提点検」）。
> 補正の外で直した点: 依存 slice を A-T2b に、完了条件に「許可リストが増えていない（A-T1）」を足した。

> **2026-09-30 の前提点検による修正**: 読み取り専用のレビュー役の指摘 S2b2-1〜S2b2-8（親 docs `research-reports/2026-09-30-wave3-order-premise-check/android.md`）と、user が確定した決定（親 docs 監査レポート §5。Spec 冒頭の 2026-09-30 追記）を反映した: `stopPlayback()` の終状態は `NothingPlaying`（**SG-C51**）、取得前・開始前の失敗は Coordinator が `Errored` の値を代入する（**SG-C52**）、総時間の優先順（**SG-C54**）、完聴時の送信は順に始めて次の開始は待たない（**SG-C61**）、手動で選んだものが開始前に再生できないと分かる場合は状態を変えない（**SG-C62**）。order を書く側が決定から導いた宣言は **A-1〜A-4**（監査レポート §5.0）。行番号は 2026-09-30 の実測に直した（A-S2a・A-S2b1 の merge でずれるので、着手時に数え直す。判定は件数で行う）。
> **A-S2a の成果に依存する前提**（A-S2a の merge 後、投入の前に実物で確かめる）: `PlaybackSession` の遷移関数の名前と `Errored` の `ref` の形、`Playing` / `Paused` / `Ended` が持つ総時間の型、`FakePlayerController.setState` が `isPlaying` / `positionSeconds` / `durationSeconds` も動かすか。食い違えば本 order を直してから投入する。

## 前提・着手条件
- 依存 slice: A-S2a（`PlaybackSession`・`ResumeRule`・`PlayerController.state`・`FakePlayerController.setState`）、A-S2b1（`PlaybackQueue.init`・`speeds: List<Double>`・`isSelectableSpeed`・`setDefaultPlaybackSpeed` の拒否・`AudioCacheManager.invalidate`）、A-T1（`ArchitectureStructureTest` と許可リスト）、**A-T2a・A-T2b**（`catalog/domain/Episode.kt` の `Episode`（`Playable` / `Generating` / `Failed`）、`network/EpisodeDecoder.kt`・`network/PodcastApiAdapter.kt`、`PodcastApi` の戻り値が `Episode`、`PlaybackSession` の 4 状態と `SessionEpisodeRef.Loaded` の保持値が `Episode.Playable`、`PodcastViewModel` のキューが `PlaybackQueue<Episode>`、`podcasts: StateFlow<List<Episode>>`、`PodcastStatusBadge.kt` の削除、`FakePodcastApi` の戻り値が `Episode`）の android PR が main に merge 済み **かつ** 親リポ `news-listen` の submodule ポインタが進んでいる（親で `git submodule status` を実行し `android` 行に `+` が無い）。
- **入口条件**: 下記「特性テスト（baseline）」9 ファイル＋T-T20 が green。
- **着手前の前提点検**（A-T2b が `PodcastViewModel.kt` とテストを触るので、行番号と件数は投入の直前に `android/` で数え直し、違えば order を直してから投入する。Spec §8.3 補正 15）:
  - `grep -n 'fun play\b\|fun playNow\|fun playNext\|fun addToQueue\|fun handlePlaybackEnded\|fun stopInternal\|fun startPositionSync\|fun syncPosition\|fun stopPlayback\|fun playabilityError' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` → 10 関数の行（2026-10-01 実測: `play` `:274`・`handlePlaybackEnded` `:350`・`playNow` `:382`・`playNext` `:392`・`addToQueue` `:402`・`stopPlayback` `:475`・`stopInternal` `:504`・`startPositionSync` `:524`・`syncPosition` `:538`・`playabilityError` `:551`。A-T2b の後にずれる）。
  - `grep -c '@Test' app/src/test/java/com/rioikeda/newslisten/podcast/PodcastViewModelTest.kt` = 52（A-T2b が fixture の型だけを直し、件数は変えない）。`grep -c 'viewModel\.play(' …/PodcastViewModelTest.kt` = 25。`grep -c 'stopPlayback' …/PodcastViewModelTest.kt` = 33。
  - `grep -n 'onPlaybackCompleted' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 1（`:329`）。`grep -n 'keepCurrentPodcast = ' 同ファイル` = 1（`:370`）。`grep -rn '_currentPodcast\.value = [^=]' app/src/main` = 2（`:327`・`:512`）。
  - `grep -rn 'invalidate(' app/src/main | wc -l` = 1（A-S2b1 の宣言）または 2（A-S4a が先なら `OfflineLibrary` の中継を含む）。`grep -rn 'code == 401' app/src/main | wc -l` = 2。`grep -rn 'error("' app/src/main | wc -l` = 0。
  - `grep -n 'data class Starting\|data class Active\|data class Completed\|data class Stopped\|data class Loaded' app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt` の保持値の型がすべて `Episode.Playable`（A-T2b の成果）。`grep -rn 'PodcastResponse' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0（A-T2b の完了条件。0 でなければ A-T2b が終わっていない）。
  - `grep -n 'fun setState\|fun play\|fun pause\|fun stop' app/src/test/java/com/rioikeda/newslisten/podcast/FakePlayerController.kt` で、`setState` が `state` だけを動かし `play()`・`pause()`・`stop()` が `state` を動かさないことを読む（下記「Fake の連動の範囲」）。
  - A-T1 の許可リスト（`test/…/architecture/Allowlist.kt`）の件数を記録する。
- Selection Gate は確定済み（共有仕様 §6.7、ADR-103）。本 slice で実装する: **SG-X1** 完聴時に `duration` を 1 回明示送信（順序 `markCompleted` → `updatePlaybackPosition(duration)` → `advance`。PS-06）。**SG-X4** 一時停止中は 15 秒周期送信をしない（PS-05b）。**SG-C16**（2026-09-24 user 判断）主体離脱の後始末から位置同期を外す: `stopForSubjectLeave()` は `updatePlaybackPosition` を送らない。共有仕様 §6.4 の送信契機「状態変化（pause / stop / 背景遷移 / 完聴）」に**離脱は含めない**（離脱時点で保存トークンは破棄済みか破棄直前であり、送っても 401 か別主体の位置を壊す。§6.4 への追記は docs 反映で行う）。
- 2026-09-24 に確定した小決定（導出値。再提案しない）: `retry()` は `session` が `Errored` のときだけ `startEpisode(queue.current)` を再実行し、それ以外の状態では **no-op**（`NothingPlaying` / `Starting` / `Active` / `Completed` / `Stopped` で何も起きず状態も変わらない）。`NowPlaying` 型（7 field）の置き場は **`podcast/PlaybackSession.kt` と同ファイル**（新ファイルを作らない。A-S2a と同じく `kotlinx.coroutines`・`android.*`・`network`・`model` を import しない。A-T1 の対応表（`test/…/architecture/LayerMap.kt`）には、このファイルの中の `NowPlaying` が「リードモデル（移行中は domain のファイルに同居）」として登録済みで、本 slice は対応表を変えない。A-T3a で `podcast/app/PlaybackReadModels.kt` へ移す。Spec §8.3 補正 14）。`invalidate` に渡す主体はセッション開始時に固定した値（下記 1）。
- **型（Spec §8.3 補正 9・A-T2b の成果）**: 開始の引数は `Episode`（`play` / `playNow` / `playNext` / `addToQueue`）、セッションの保持値と TP-A1 の派生値は `Episode.Playable`。本 order の「一覧の `Episode`」は `podcasts` の要素（CACHED 経路の再開位置と総時間の元）、「取り直した `Episode`」は `podcastApi.fetchPodcast(id)` の戻り値（NETWORK 経路）。`NowPlaying` の `segments`・`vocabulary`・`quiz` は `Episode` の内容の型（`catalog/domain/Episode.kt` で宣言。DTO の `TranscriptSegment` などではない）。開始前の判定は `PodcastStatusBadge`（A-T2b で削除済み）ではなく **`Episode` の種類**で行う: `Playable` だけが開始でき、`Generating` は「生成中のため再生できません」、`Failed` は失敗の識別子をそのまま出す（文言は現行の `playabilityError` と同じ。A-26）。`PodcastResponse` を本 slice の新しいコードに持ち込まない（禁止事項）。
- **取り直した `Episode` が `Playable` でないとき（Spec §8.3 補正 10・導出 A-17）**: 開始前に再生できないと分かった場合と同じに扱う。手動の開始（`play` / `playNow`、`playNext` / `addToQueue` の即再生）は状態を変えず `errorMessage` だけ、advance と `retry()` は `Errored(IdOnly(id), NotPlayable)` の値を代入する（SG-C52）。現行は取り直した DTO を検査せずに再生へ渡す（`PodcastViewModel.kt:308-309`）ので、この分岐は本 slice で足す。
- 2026-09-30 の導出（再提案しない）: **A-1** 手動の開始は「判定 → （NETWORK なら取得）→ その後でキューと Session を変える」の順にし、取得に失敗したときもキューと Session を変えない（SG-C62 の「error 状態にするのは、キューが既にそのエピソードを現在にしている場合だけ」。web の W-S2a2 と同じ）。**A-2** 「何も再生していない」（`playNext` / `addToQueue` が即再生する条件）は `session` が `NothingPlaying` のとき。**A-3** `Active` が持つ速度は開始時の値のままで、現在の速度の正本は `playerController.playbackSpeed`。**A-4** 完聴の送信は 1 本の直列（`markCompleted` → 位置の書込 → ストリークの更新）を別の coroutine で始め、`advance` と次の開始はその完了を待たない。
- 棄却済み案（再提案しない）: 旧 `_currentPodcast` を owner のまま残す併存移行、`AuthState` 4 状態化、preferences 消去契約の追加（A-S4 で SG-A6 により別途扱う）。`Episode` decode は棄却ではなく A-T2a・A-T2b で入った（ADR-110 決定 8）。本 slice は `Episode` を使うが、判別の規則（TA-R-CT1）を変えない。
- `docs/trial-log/`（android・親）を最初に読む。

## 変更行（挙動が変わるのはこの行だけ。準拠テスト名に行 ID を含める）
| 行 | 変わる内容 | 検証 |
|---|---|---|
| PS-01 | advance 後の NETWORK 取得失敗 → Coordinator が `Errored(FetchFailed)` の値を代入する（SG-C52。遷移関数を通らず、分母 11 に数えない）・`queue.current` は次のまま・自動で進まない・player 停止（現行: `errorMessage` のみで session 概念なし） | T-T5 |
| PS-02 | PS-01 の状態から手動 `retry()` → `startEpisode(queue.current)` を再実行 | T-T5 |
| PS-03 | advance 後にオフライン未キャッシュ → network 取得なし・`Errored(SourceUnavailable)`・文言「オフライン」 | T-T6 |
| PS-05 | `Errored` / `Stopped` / `NothingPlaying` では位置同期を送らない（現行は `_currentPodcast ?: return` が代替） | T-T19 |
| PS-05b（SG-X4） | 一時停止中は周期送信しない。pause への遷移時に 1 回送る。Media3 が自動で行う一時停止（音声フォーカスの喪失・出力機器の切断。`ExoPlayerController.kt` の `handleAudioFocus` / `setHandleAudioBecomingNoisy`）も `state` の `Paused` として現れるので、同じく 1 回送る。`Playing` へ戻ったら 15 秒の周期を張り直す | T-T19b |
| PS-06（SG-X1・SG-C54・SG-C61） | 完聴の送信 = `markCompleted`（1 セッション 1 回）→ `updatePlaybackPosition(総時間)` 1 回を、この順で送り始める。`advance` と次の開始は、その応答を待たない（A-4）。送る総時間は「`PlaybackState.Ended.duration`（`Double?`。0 より大きい）→ `Episode.durationSeconds`（`Int`。`Double` に直して比べ、0 より大きい）」の順で、どちらも不明なら `Ended` を受けたその場で捕捉した `positionSeconds.value`、それも 0 なら位置は送らない（現行: `markCompleted` の応答を待ってから advance し、次の play の `stopInternal` が現在位置を送る）。**送る値の出どころ**は下の表 | T-T8 |
| PS-08 | セッション速度は既定速度で開始し、セッション中の変更は既定速度を書き換えない（現行: 既定速度を player に適用していない） | T-T4 |
| PS-09（SG-C24・SG-C51） | 再生セッションの停止 = Coordinator が `NothingPlaying` を代入し、player の読み込みを外す（遷移関数を通らない。分母 11 の外）。`NothingPlaying` での停止は何もしない。本 slice の観測点は `stopPlayback()`（A-S2c の後は `stopForSubjectLeave()`） | T-PS09（テスト名に `PS-09`） |
| PS-10（SG-C52） | 取得前・開始前の失敗（オフラインで未キャッシュ・取得失敗・再生不可）を、`IdOnly(id)` と理由を渡して Coordinator が `Errored` の値を代入する（キューが既にそのエピソードを現在にしている経路だけ） | T-T5・T-T6 のテスト名に `PS-10` |
| PS-11（SG-C62・SG-C68） | = 下の SG-C62 の行。手動の開始が開始前に落ちる・取得に失敗するとき、キューもセッションも変わらず a の再生が続く | T-T6m（テスト名に `PS-11`） |
| CI-T4（resume 適用） | 開始位置に `resolveResumePosition` の値を適用（`resume > 0` のとき `seekTo`）。CACHED は一覧の `Episode`、NETWORK は取り直した `Episode` の `resumeHintSeconds`・`durationSeconds` を使う | T-T4 |
| CI-T9（Coordinator 部分） | CACHED 経路で `Failed(Source\|Decode)` → `invalidate(id)`（A-S2b1 で追加済み）→ `Errored(Loaded(episode), Player(reason))`、次回は NETWORK | T-T9 |
| SG-C62（行 ID なし → PS-11） | **手動の開始**（`play` / `playNow`、`playNext` / `addToQueue` の即再生）が、開始前に再生できないと分かる（`Episode` が `Playable` でない・オフライン未キャッシュ）か、取得に失敗した・取り直した `Episode` が `Playable` でない（A-17）とき、キューも Session も変えず `errorMessage` だけを出す。再生中のものは続く（現行: gate のときキューだけ先に jump して現在再生中と食い違う。オフライン未キャッシュ・取得失敗のとき、再生中のものを止めて `currentPodcast` を null にする） | T-T6m |
| SG-C51（行 ID なし → PS-09） | `stopPlayback()` の終状態は `NothingPlaying`（挙動は現行と同じ。`currentPodcast` は null になる） | 既存 `stopPlayback` のテスト（期待値不変） |
| SL-01（再生停止部分。SG-C16） | `stopForSubjectLeave()` = `stop()` → queue 空 → `NothingPlaying` → `nowPlaying == null`。**位置同期を送らない**（現行の `stopInternal` は停止直前に 1 回送る） | T-SL01p |

PS-04（INV-P1）は A-S2a の述語を Coordinator の全公開操作後に assert する形で本 slice でも検証する。**PS-07・PS-07b は A-T2b で適用済み**（`Episode` の判別。本 slice は判別を変えず、`Playable` 以外を開始しないことを T-T6m で観測する）。CI-T7（Queue）・CI-T15（速度値域）は A-S2b1 で green 済みであり、本 slice では再検証しない（回帰は baseline で拾う）。

**開始を確定するときの辺の選び方**（Spec §8.3 補正 2・導出 A-14。「確定」は、開始前の判定と取得が通った後（A-1）。`start` は `NothingPlaying`・`Stopped`・`Errored` からだけ、`playNow` は `Active` からだけ受ける（`podcast/PlaybackSession.kt:21-24,39-42`）。書かないと、連続した開始（T-T20。Fake は `play()` で `state` を動かさないので 1 回目の後は `Starting` のまま）で `IllegalStateException` が出る）

| 確定の時点のセッション | 手順 | 使う辺 |
|---|---|---|
| `NothingPlaying`・`Stopped`・`Errored` | `start` | NothingPlaying→Starting／Stopped→Starting／Errored→Starting(start) |
| `Active` | 停止直前の位置を 1 回送る → `playerController.stop()` → `playNow` | Active→Starting(playNow) |
| `Starting` | `playerController.stop()` → `NothingPlaying` を代入 → `start`（位置は送らない） | 停止のリセット（表の外。PS-09）＋ NothingPlaying→Starting |
| `Completed`（手動の開始が割り込んだ） | `playerController.stop()` → `NothingPlaying` を代入 → `start`（位置は送らない） | 同上 |
| `Completed`（そのエピソードの完聴に続く自動の開始） | `advance` | Completed→Starting(advance) |
| `Errored` での `retry()` | `retry` | Errored→Starting(retry) |

自動の開始が直列化（`playMutex`）の順番を待つ間に手動の開始が先に確定した場合、自動の側はその時点のセッションの値でこの表を引く（後から来たものが効く。SG-C73 の Android の保留のとおり現行の挙動を保つ）。表に無い遷移は足さない。

**player の事象の受け方**（Spec §8.3 補正 3・導出 A-15。`podcast/PlaybackSession.kt:44-58`。表に無い組合せは無視し、購読の中で例外を出さない。書かないと 2 回目の `Ended`（T-T8）で `playerEnded` が例外を出す）

| player の事象 | `Starting` | `Active` | それ以外 |
|---|---|---|---|
| `Idle`・`Loading` | 何もしない | 何もしない | 何もしない |
| `Playing` | `playerStarted`。周期の timer を張る | 周期の timer を張り直す | 無視 |
| `Paused` | `playerStarted`（位置は送らない） | 直前の事象が `Playing` なら `Paused.position` を 1 回送り、timer を止める | 無視 |
| `Ended` | `playerStarted` → `playerEnded` の順に呼び、完聴の処理へ | `playerEnded` → 完聴の処理へ | 無視（2 回目の `Ended` を含む） |
| `Failed(reason)` | CACHED 経路で `Source`・`Decode` なら `invalidate` → `fail(Loaded(episode), Player(reason))` | 同左 | 無視 |

**送る値の出どころ**（Spec §8.3 補正 4・5・導出 A-16）

| 値 | 出どころ |
|---|---|
| 完聴時の総時間 | `PlaybackState.Ended.duration`（`Double?`）→ `Episode.durationSeconds`（`Int` → `Double`）→ `Ended` を受けたその場で捕捉した `positionSeconds.value`（完聴の送信は別の coroutine で、読む頃には次の `prepare` と `stop` が `durationSeconds` を null に、位置を 0 に戻している。`podcast/ExoPlayerController.kt` の `STATE_IDLE` の分岐と `prepare`） |
| 一時停止の位置 | `PlaybackState.Paused.position` |
| 停止直前の位置（切り替え・`stopPlayback`） | `positionSeconds.value` |
| 送らない 2 つの場合 | (a) `Starting` の間の切り替えでは「停止直前の 1 回」を送らない（player がまだ位置を報告しておらず、古い 0 を送り得る）。(b) `Completed` のエピソードには停止直前の 1 回を送らない（完聴の送信が最後の書込。共有仕様 §6.4） |

**Fake の連動の範囲**（Spec §8.3 補正 6。`test/…/podcast/FakePlayerController.kt:77-103,128-130`・`PlaybackStateTest` の「setState は既存の isPlaying を派生させない」）: `setState` は `state` だけを動かす。`play()`・`pause()`・`stop()` は `state` を動かさない。周期の送信・一時停止の 1 回・完聴を確かめるテストは `setState(Playing(…))`・`setState(Paused(位置, 総時間))`・`setState(Ended(総時間))` を注入する。既存の位置同期のテストにも `play` の後に `setState(Playing(…))` を足す（期待値は変えない）。

## 対象（android サブモジュールのみ。ファイル単位）
**変更（main）**
1. `podcast/PodcastViewModel.kt`（PlaybackCoordinator）: 追加 `session: StateFlow<PlaybackSession>`・`nowPlaying: StateFlow<NowPlaying?>`（`episodeId` / `displayTitle` / `japaneseIntroText` / `segments` / `vocabulary` / `quiz` / `difficulty` の 7 field。`segments`・`vocabulary`・`quiz` は `Episode` の内容の型。`NothingPlaying` と `Errored`（`ref` が `IdOnly` でも `Loaded` でも）で `null`）・`retry()`・`stopForSubjectLeave()`。`PreferencesStore` をコンストラクタ注入（既定速度の読み手）。`play` / `playNow` / `playNext` / `addToQueue` / `handlePlaybackEnded` / `stopInternal` / `startPositionSync` / `syncPosition` を `startEpisode` / `onEnded` / 送信条件に置き換える。完聴の検知は `playerController.state` の `Ended` を購読（`onPlaybackCompleted` は登録しない）。完聴の送信は別の coroutine で「`markCompleted` → 位置の書込 → `listeningStreakStore?.refresh()`」を順に行い、`advance` と次の開始はその完了を待たない（A-4）。`state` の購読の中で `invalidate` が `AudioCacheException` を投げても購読を止めない（捕捉して `Errored(Player)` へ進める）。`Errored` の `ref` は prepare 前の失敗（`NotPlayable` / `SourceUnavailable` / `FetchFailed`）では `SessionEpisodeRef.IdOnly(id)`、`Player` では `SessionEpisodeRef.Loaded(episode)`（`podcast/PlaybackSession.kt:81-89`。Spec §8.3 補正 1）。開始の確定は上の「辺の選び方」の表、player の事象は「受け方」の表のとおりに写す（表に無い組合せは無視）。**prepare 前の失敗を `Errored` にするのは、キューが既にそのエピソードを現在にしている経路（完聴後の advance・`retry()`）だけ**で、Coordinator が `Errored(ref, reason)` の値を代入する（SG-C52）。**手動の開始**（`play` / `playNow` と、`playNext` / `addToQueue` の即再生）は、`playMutex` の中で「`Episode` の種類の判定（`Playable` か） → `resolvePlaybackSource` → NETWORK なら `fetchPodcast` → 取り直した `Episode` の種類の判定」を先に行い、再生できないと分かったとき・取得に失敗したとき・取り直した結果が `Playable` でないときは**キューも Session も変えず** `errorMessage` に現行と同じ文言を書いて戻る（SG-C62・A-1・A-17。前の再生は止めない）。**`play(episode)`**（`:274`。main の外部の呼出 0、`PodcastViewModelTest` に 25 箇所）は本 slice では `playNow` と同じ手動の開始として残す（キューに入れてから開始する。PS-04 が全公開操作の後に INV-P1 を求めるため。Spec §8.3 補正 8・導出 A-20）。公開の削除とテストの `playNow` への置換は A-S2c。通ったときだけ、その後で「キューの jump / 挿入 → 前の再生の位置同期 1 回と `stop()` → 開始」を続けて行う。`playNext` / `addToQueue` の「キューに足す」は利用者の操作なので、即再生が通らなくても残る。即再生の条件は `session` が `NothingPlaying`（A-2）。`Active` が持つ速度は開始時の値のままで、セッション中の `setSpeed` では書き換えない（A-3。現在の速度は `playerController.playbackSpeed`）。**`errorMessage: StateFlow<String?>` は維持**し、`Errored` への遷移時に現行と同じ文言（取得失敗は `e.message`、オフラインは「オフラインのため再生できません」、gate は `playabilityError`）を書く（`PodcastScreen.kt:60, 210, 214` の dialog は不変）。**`stopPlayback()`**（`:475`。main の呼出 0・`PodcastViewModelTest` の呼出 33 箇所）は §7.2 の残責務 15 操作に無いが本 slice では残し、「位置同期 1 回 → `stop()` → **`NothingPlaying` を代入**」（キューは保持。SG-C51。`Stopped` は `Completed` からキューが尽きた場合だけで、遷移表の 11 遷移に `Active → Stopped` は無い）へ写す。既存テストの `assertNull(viewModel.currentPodcast.value)`（`PodcastViewModelTest.kt:334` 付近）は期待値不変で green。削除は A-S2c。**TP-A1（暫定互換）**: `currentPodcast: StateFlow<Episode.Playable?>`（A-T2b で型が `Episode.Playable?` になっている）は `session` からの派生値（`nowPlaying` と同じ非 null 条件で `Episode.Playable` を返す。逆変換はしない。Spec §8.2）として残し、`_currentPodcast` への書込は 1 箇所（session 適用関数）だけにする。owner: user、導入: A-S2b2、削除条件: UI 3 ファイル（`AudioPlayerSection` / `PodcastScreen` / `QueueSheet`）＋`PodcastRowView`（引数名 `isPlaying` → `isNowPlaying`）と `PodcastViewModelTest` が `nowPlaying` / `session` だけを読むようになった時（A-S2c の対象 5〜9 と同じ集合）。`keepCurrentPodcast` 引数は `Stopped`（キューが尽きた完聴）が代替するため、名前付き引数での呼出が 0 になる（シンボル削除は A-S2c）。**`stopForSubjectLeave()`** は「`stop()` → queue 空 → `NothingPlaying`」だけを行い、**位置同期を送らない**（SG-C16。`stopPlayback()` の「位置同期 1 回」とは異なる）。**主体の固定**: `startEpisode` の開始時に `currentSubject()`（`() -> String?`。A-S4a が `AppContainer` から注入する。A-S4a 未 merge なら本 slice が `{ null }` 既定の引数として宣言だけ足す）を 1 回捕捉して `sessionSubject` に保持し、CACHED 判定・`cachedFileUri`・`invalidate` にはこの値を渡す。`NothingPlaying` へ戻るときに `null` にする。A-S4a 未 merge の間は `invalidate(id)` の 1 引数のまま（`sessionSubject` は保持だけ）。
2. `di/AppContainer.kt`: `PodcastViewModel` へ `preferencesStore` を渡す。`onSubjectLeave` ラムダに `_podcastViewModel.stopForSubjectLeave()` を独立 try/catch で追加（順序: 停止 → `cancelDownloadsAndClearCache`（A-S4a の後は `offlineLibrary` の 2 手順）→ FCM。A-S0 で保留していた再生停止部分。A-S2b2b）。

**A-S4 が先に merge されている場合の追随**（A-S4a・A-S4b 側の同名節と対称。(a) は A-S4b が先のとき A-S2b2b が行い、(b)(c)(d) は A-S4a が先のとき A-S2b2a が行う）: (a) 後始末は `List<CleanupStep>` で組まれているので、`CleanupStep(name = "playback_stop") { _podcastViewModel.stopForSubjectLeave() }` を**先頭**に足す（手順列の順序 = `playback_stop` → `download_cancel` → `audio_cache` → `preferences` → `fcm_local_reset`）。(b) `AudioCacheManager` を直接触るのは `podcast/OfflineLibrary.kt` だけになっているので、CACHED 判定・`cachedFileUri`・`invalidate` は `offlineLibrary.isCached(sessionSubject, id)` / `cachedFileUri(sessionSubject, id)` / `invalidate(sessionSubject, id)` の 2 引数で呼ぶ（`sessionSubject` は上記 1）。(c) `currentSubject: () -> String?` は A-S4a が既に `PodcastViewModel` へ注入しているので、本 slice は捕捉地点を `play` の開始から `startEpisode` の開始へ移すだけ。(d) 完了条件の `invalidate(` grep は A-S4a 側の値（3 箇所）で判定する。

**変更（test）**: `podcast/PodcastViewModelTest.kt`（T-T20 を baseline に先に追加。変更行に該当するテストだけを反転し、反転理由に行 ID を書く）、`podcast/PodcastViewModelPortTest.kt`（`PodcastViewModel(` の呼出は `PodcastViewModelTest.kt:69`・`PodcastViewModelPortTest.kt:41`・`di/AppContainer.kt:346` の 3 箇所。`preferencesStore` の引数を足すので、Port テストの生成も直す。期待値は不変）、`network/AudioCacheManagerTest.kt` は本 slice では触らない（`invalidate` の観測は A-S2b1 で済み）。`stopForSubjectLeave()` は `PodcastViewModelTest` で観測する（T-SL01p: `Active` から呼ぶ → `stop()` 1 回 → `queue` 空 → `NothingPlaying` → `nowPlaying == null`、かつ `FakePodcastApi` の `updatePlaybackPosition` 呼出が**増えない**。SL-01 の再生停止部分・SG-C16。`AppContainer` の配線は unit テスト対象外）。`FakePodcastApi`（A-S1）と `FakePlayerController.setState`（A-S2a）を使う。

**変更しない**: `AudioPlayerSection` / `PodcastScreen` / `QueueSheet` の `currentPodcast` 読み（TP-A1 で動く。読み替えは A-S2c）、`SettingsScreen.kt:1361 PLAYBACK_SPEEDS`（削除は A-S2c）、`PlayerController.onPlaybackCompleted` の宣言と `ExoPlayerController` の発火（TP2。削除は A-S2c）、`core/PlaybackQueue.kt`・`PlaybackConstants.kt`・`preferences/*`・`AudioCacheManager.kt`（A-S2b1 で完了）。

## 契約（CI → T の対応。本 PR = A-S2b2a の分だけ。CI-T8・CI-T19・SL-01 の T-T8・T-T19・T-T19b・T-T19c・T-SL01p は A-S2b2b）
| CI | T-T |
|---|---|
| CI-T4（resume・既定速度。PS-08） | T-T4: `FakePlayerController` の `seekTo` / `setSpeed` 引数。既定速度 1.5 でセッション中 2.0 へ変更 → 次エピソードは 1.5、`PreferencesStore` は 1.5 のまま |
| CI-T5（INV-P1・PS-01・PS-02） | T-T5: 完聴 → advance → NETWORK 失敗の系列。`queue.current.id == session.ref.id`、`Errored(FetchFailed)`、`retry()` が再実行。本 PR では完聴の契機を旧い経路（`FakePlayerController` の完聴の通知 → `handlePlaybackEnded`）で与える |
| CI-T6（PS-03） | T-T6 |
| CI-T6（SG-C62・PS-11） | T-T6m: a を再生中に、手動で b を開始する。b が (1) `Episode` の判別で `Playable` でない（`Generating` と `Failed` の各 1 例） (2) オフラインで未キャッシュ (3) NETWORK の取得に失敗 (4) 取り直した `Episode` が `Playable` でない（A-17）、のそれぞれで、`queue`・`session`・player の状態が呼出前と同じで、`errorMessage` に現行の文言が入り、`updatePlaybackPosition` の呼出が増えない。(2) では `fetchPodcast` が呼ばれない。`NothingPlaying` で `addToQueue(b)` を呼び b が再生できない場合は、キューに b が入り、`session` は `NothingPlaying` のまま |
| CI-T9（Coordinator 部分） | T-T9: `FakePlayerController.setState(Failed(Source))` → `cacheManager.invalidate` の呼出 → `Errored(Loaded(episode), Player(Source))` → 次の `resolvePlaybackSource` が NETWORK。`invalidate` が `AudioCacheException` を投げる場合も `Errored(Player)` になり、その後の `state` の変化を受け取れる |
| CI-T20（baseline） | T-T20: 2 回連続 `playNow` の**切り替えの後に timer を進めて出る** `updatePlaybackPosition` の id が最後のエピソードだけ。切り替えのときの停止直前の 1 回は、前のエピソードの id で出る（前が `Active` のとき。現行の `stopInternal` も切り替えで前の id を送るので baseline はこの文で通る。Spec §8.3 補正 13） |
| PS-09（停止のリセット） | T-PS09: `Active` から `stopPlayback()` → `session` が `NothingPlaying`・player の `stop()` 1 回。`NothingPlaying` から呼んでも何も起きない（`FakePodcastApi`・`FakePlayerController` の呼出が増えない）。テスト名に `PS-09` |
| PS-10・PS-11 | T-T5・T-T6 のテスト名に `PS-10`、T-T6m のテスト名に `PS-11` を含める |
| CI-T1 | T-T1（再確認）: Coordinator 経由（`FakePlayerController.setState` ＋ start / playNow / retry / advance）で A-S2a と同じ 11 遷移を観測。`retry()` を `Active` / `NothingPlaying` で呼んでも状態・`FakePodcastApi` の呼出列が変わらない（no-op） |

既存の位置同期と完聴のテスト（`PodcastViewModelTest` の位置同期 `:270-313`・再生完了 `:433-492` の節）は、本 PR では**期待値を変えずに green**（挙動を変えないため）。反転は A-S2b2b。

## 特性テスト（baseline。着手前に green。9 ファイル）
`podcast/PodcastViewModelTest`（52）、`podcast/PodcastViewModelPortTest`（1）、`core/PlaybackQueueConformanceTest`（32＋A-S2b1 の T-T7）、`core/PlaybackSourceResolverTest`（4）、`network/AudioCacheManagerTest`（14＋A-S2b1 分）、`podcast/PlaybackMetadataTest`（5。A-T2b で `Episode` 入力に直したもの）、`catalog/EpisodeTest`（A-T2a の判別の表。旧 `PodcastStatusBadgeTest` 5 件は A-T2b でここへ統合済み）、`settings/SettingsViewModelTest`（24）、`preferences/DataStorePreferencesStoreTest`（5＋A-S2b1 の T-T15）。加えて **T-T20 を先に追加**して green にする。件数は A-T2b の後に数え直す（「着手前の前提点検」）。

## 手順
1. baseline 9 ファイル＋T-T20 green を記録。
2. T-T4・T-T5・T-T6・T-T6m・T-T9・T-T1（Coordinator）・T-PS09・PS-04 → RED → `PodcastViewModel` を `PlaybackSession` 経由へ差し替え（`session`・`nowPlaying`・`startEpisode`・`retry`・TP-A1 の派生 `currentPodcast`・`errorMessage` の維持・`stopPlayback` の `NothingPlaying` への写像。完聴は旧い `handlePlaybackEnded` を `session` 参照へ書き換えるだけ）→ GREEN。9 特性テストと既存の位置同期・完聴のテストが green のままであることを各段で確認。
3. `AppContainer` で `PodcastViewModel` へ `preferencesStore` を渡す（A-S4a が先なら下の「追随」の (b)(c)）。
4. 1 slice = 1 PR。temporary path: TP-A1（本書 1。削除は A-S2c）・TP2（`onPlaybackCompleted`。本 PR では完聴の検知に使い続ける。登録をやめるのは A-S2b2b、宣言の削除は A-S2c）。

## 完了条件
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` 全 green。
- T-T1・T-T4・T-T5・T-T6・T-T6m・T-T9・T-T20 が `verifies: CI-T*` をテスト名またはコメントに持ち、PS-01・PS-02・PS-03・PS-04・PS-08・**PS-09・PS-10・PS-11** がテスト名に含まれる（量化する集合 = `podcast/PodcastViewModelTest.kt` のテスト名）。
- 本 slice の新しいコードに DTO が無い: `grep -n 'PodcastResponse' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt app/src/main/java/com/rioikeda/newslisten/podcast/PlaybackSession.kt` = 0（A-T2b の到達点を保つ）。
- 許可リストが増えていない（A-T1）: `test/…/architecture/Allowlist.kt` の件数が着手前の記録と同じか少ない。`ArchitectureStructureTest` が green。
- 既存 `PlaybackQueueConformanceTest` 32 件（Q-01〜Q-32）が名前・期待値とも不変。
- 反転した既存テストはすべて本 PR の変更行（PS-01・02・03・08・09・10・11、CI-T4、CI-T9、SG-C62）のいずれかを理由に持つ（PR 本文に「テスト名 → 行 ID」の表。行 ID の無い行は `SG-C62` と書く）。既存の位置同期・完聴のテストは期待値を変えていない。
- TP-A1 の書込が 1 箇所: `grep -rn '_currentPodcast\.value = [^=]' app/src/main` = 1（session 適用関数。2026-09-30 実測は `:327`・`:512` の 2 件。`[^=]` を付けないと比較 `_currentPodcast.value == null` の 2 行にも一致して 4 件になる）。`grep -n 'keepCurrentPodcast = ' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0（名前付き引数での呼出が無い。2026-09-30 実測は `:370` の 1 件。宣言 `keepCurrentPodcast: Boolean` と分岐 `if (!keepCurrentPodcast)` はこのパターンに一致しない）。
- `grep -rn 'invalidate(' app/src/main` の一致は `network/AudioCacheManager.kt` の宣言と `podcast/PodcastViewModel.kt` の呼出 1 箇所（= 2 箇所）。**後続 A-S4a が更新する（許可）**: A-S4a merge 後は `podcast/OfflineLibrary.kt` の中継 1 箇所を加えた 3 箇所が到達点（A-S4a が先に merge されていれば本 slice の判定値も 3 箇所）。
- 本 PR が変えていないこと（A-S2b2b の範囲）: `grep -n 'onPlaybackCompleted' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 1（登録が残る。2026-09-30 実測 `:329`）。`grep -n 'fun stopForSubjectLeave' app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt` = 0。
- grep oracle 回帰なし: `error("` = 0。`grep -rn 'code == 401' app/src/main | wc -l` = **2**（件数判定。2026-09-24 実測: `network/AuthInterceptor.kt:48`・`network/OkHttpApiClient.kt:459`。対象集合 = `app/src/main`）。
- レビュー観点: `PlayerController` interface に Media3 の型が無い。`PodcastViewModel` の再生 use case が `PodcastApi` だけに依存（A-S1 の境界維持）。`core/` が `kotlinx.coroutines` を import しない。
- **公開面**（NFR-10・AQ-6。2026-10-01 追加: 新しい domain・リードモデルの型を作る slice は、その型を公開面の検査の対象に足す。Spec §7 TA-V5・TA-V6）: `NowPlaying`（`podcast/PlaybackSession.kt` に置く。リードモデル）を TA-V6 の対象に足す: `NowPlaying` の `segments`・`vocabulary`・`quiz` の list を `MutableList` へ cast して書き換えても、次の `nowPlaying.value` と `session` が変わらない（`podcast/NowPlayingEncapsulationTest.kt`。テスト名に `TA-V6`）。TA-V5（静的）は `ArchitectureStructureTest` が `NowPlaying` の property が `val` で可変の collection 型でないことを確かめる（A-T1 の対応表に登録済み）。

## 禁止事項 / scope 外
- 上記「変更行」以外の挙動を変えない（Q-01〜Q-33・`resolvePlaybackSource`・語彙登録・クイズ中継・ダウンロード・`Episode` の判別（TA-R-CT1。PS-07・PS-07b は A-T2b で適用済みで、本 slice は判別を変えない）・`errorMessage` の文言）。
- `PodcastResponse`・`model.*` の型を、本 slice が書く新しいコード（`PodcastViewModel`・`PlaybackSession.kt` の `NowPlaying`・新しいテスト）に持ち込まない（Spec §8.2 の原則）。
- `stopPlayback()` を `Stopped` にしない（SG-C51）。手動の開始の失敗で `Errored` にしない・前の再生を止めない（SG-C62）。完聴の送信の順序（SG-C61・PS-06）は本 PR では変えない（現行の「`markCompleted` の応答を待ってから advance」のまま残し、A-S2b2b が改める）。「次へ送り」の入口は足さない（SG-C63。Android は現時点で入口を持たない）。
- **学習の中継 3 操作（`loadVocabularyRegistrations` / `saveVocabulary` / `submitQuizAnswers`）は `PodcastViewModel` に残す**（保留。根拠: 親 docs §7.2「`PodcastViewModel` に残る責務」が「語彙登録・クイズ中継の 3 操作は `ApiClient` 経由のまま残す（RF8 保留: 解除条件 = 学習機能のサイクル）」と定めており、本 slice の変更行に学習の契約 ID が無い。ダウンロード 3 操作の `OfflineLibrary` 移設（SG-C17）は A-S4 で行い、本 slice は触らない）。
- `stopForSubjectLeave()` から位置同期を送らない（SG-C16。「最後の位置を保存したい」を理由に足さない）。
- UI 3 ファイルの `currentPodcast` → `nowPlaying` 読み替え、`_currentPodcast` / `keepCurrentPodcast` / `onPlaybackCompleted` / `stopPlayback` / `SettingsScreen.PLAYBACK_SPEEDS` の削除はしない（A-S2c）。
- `_currentPodcast` を owner にしない（書込 1 箇所・session からの派生のみ）。
- `core/PlaybackQueue.kt`・`PlaybackConstants.kt`・`preferences/*`・`AudioCacheManager.kt` の契約を変えない（A-S2b1 で確定）。
- 主体別キャッシュ・主体離脱の順序変更・logout ヘッダはしない（A-S4）。位置同期の契機は**周期・一時停止への遷移・停止直前・完聴の 4 つ**で、背景遷移は Android に適用しない（導出 A-12: 再生の use case は Activity の lifecycle に結び付かず、背景でも周期と一時停止の 1 回が続く。main の `ON_STOP` は `feed/FeedScreen.kt:111` だけ）。背景遷移の契機を足さない（Spec §8.3 補正 12）。
- `PlaybackCoordinator` を class として取り出さない・リードモデル `QueueView`・`EpisodeRow` を足さない・command を id 受けにしない（A-T3a）。`errorMessage` を `PlaybackNotice` にしない（A-T3b）。
- A-S2b2b の範囲（PS-05・PS-05b・PS-06、`state` の `Ended` の購読と `onPlaybackCompleted` の登録をやめること、周期の timer の張り直しと一時停止の 1 回、送らない 2 つの場合、`stopForSubjectLeave()` と `AppContainer.onSubjectLeave` への追加）を本 PR で行わない。
- 棄却済み案を持ち込まない。仕様にない業務条件を足さない。

## 規模（見込み行数。根拠 = 2026-09-24 実測）
- A-S2b2 の全体は production ≈ 270 行・test ≈ 750 行・合計 ≈ 1,020 行で 1,000 行の目安を超えるため、2 つの order（2 PR）に分けた（全体の内訳: `PodcastViewModel.kt` 562 行（2026-09-30 実測）のうち書き換える 8 関数の範囲 = `play` 系 `:257-333`（`beginPlayback` 含む）＋`handlePlaybackEnded` `:337-372`＋`playNow` / `playNext` / `addToQueue` `:374-406`＋`stopInternal` / `startPositionSync` / `syncPosition` `:493-544` = 約 200 行。`PodcastViewModelTest.kt` 1,219 行・52 件のうち変更行に触れる既存節 ≈ 560 行）。
- **本 PR（A-S2b2a）≈ 600 行**: production ≈ 170（`play` 系・`playNow` / `playNext` / `addToQueue` の `startEpisode` への置換・`session` 適用関数・`nowPlaying`・`retry`・`stopPlayback` の写像・`handlePlaybackEnded` の `session` 参照・`AppContainer.kt` ≈ 5・`PlaybackSession.kt` へ `NowPlaying` ≈ 15）、test ≈ 430（play ゲート／オフライン `:105-269`・`stopPlayback` `:314-340`・suspend 競合 `:552-590`・キュー `:591-766`・advance×gate `:767-855` の反転と、新規 T-T4 / T-T5 / T-T6 / T-T6m / T-T9 / T-T1 / T-T20 / T-PS09）。
- 分けた理由: 前半は「現在再生中の正本」の境界、後半（A-S2b2b）は「完聴の順序・送信条件」の境界で、失敗時の巻き戻しを独立にできる。前半の完了条件は前半の変更行と grep だけで判定する（`onPlaybackCompleted` = 0 は後半の条件）。

## 検証
- `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest --console=plain` → exit 0。
- 完了条件の grep（`PodcastResponse`・`_currentPodcast\.value = [^=]`・`keepCurrentPodcast = `・`invalidate(`・`onPlaybackCompleted`（= 1 のまま）・`fun stopForSubjectLeave`（= 0）・`error("`・`code == 401`）の結果を PR 本文に貼る。

## 記録
- 反転した特性テストの一覧（テスト名 → 行 ID）を PR 本文と `android/docs/trial-log/` に残す。
- 親 docs への返却事項: 共有仕様 §4.4 の保留のうち PS-01〜PS-04・PS-08〜PS-11 を、A-S2b2b の完了とあわせて解除できる旨（解除の返却は A-S2b2b でまとめて行う）。導出 A-14・A-15（`Starting` と `Failed` の列）・A-17・A-20 が実装と一致した旨。`NowPlaying` の置き場（`podcast/PlaybackSession.kt`）と `retry()` の no-op 条件を Spec §3.1 に反映する旨。
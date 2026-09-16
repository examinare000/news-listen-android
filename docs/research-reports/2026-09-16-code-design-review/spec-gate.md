# Spec gate — android Implementation Spec（architecture ロール・read-only・事前実装ゲート）

対象: `/private/tmp/claude-501/-Users-rio-git-news-listen-android/e97c09e3-0f12-436c-b90e-d63ddba9ef51/scratchpad/review/2026-09-16-implementation-spec-playback-auth.md`（308 行）
入力: `android/docs/research-reports/2026-09-16-code-design-review.md` §4/§8（424 行）、`docs/design/shared-playback-spec.md`（327 行）、実コード（下記 §H の範囲検査表）
日付: 2026-09-16 ／ mutation: 行っていない（ソース・設定・git 無変更）

```yaml
gate_verdict: revise
subject_verdict: 骨格は妥当・安全性で要修正
decision: {artifact_readiness: draft, engineering_status: blocked_on_fixes, release_status: not_applicable}
reviewed_by: unresolved   # 本 gate は AI 単独評価。SG-R13〜R16 の確定と Spec 承認は user
```

結論を先に言う。**context 分割・依存方向・port 1 つ・状態 union・失敗方針という骨格は妥当で、着手順も §8 に概ね従っている。ただし `revise`。** 最大の問題は 3 件で、いずれも「Spec のとおり実装すると現行より悪くなる」種類である: (1) 全 401 を `Unauthorized` に写し `AuthInterceptor` から `onUnauthorized` を発火させる設計に、**ログイン失敗の 401 を除外する事前条件が無い**（パスワード誤入力で音声キャッシュ全削除・再生停止が走り得る）、(2) `_currentPodcast` 削除に伴い `stopInternal` の早期 return ガードが失われ、**`Errored(FetchFailed)` から position 0 が server の resume 位置を上書きし得る**、(3) `PodcastViewModel ↛ ApiClient` という禁止事項が、RF8 保留（語彙・クイズを分離しない）と**両立しない**。次いで、pending な SG-R13〜R15 を確定済みとして S0/S2 に組み込んでいること、§8 で「記録のみ」とした RF16 を S0 で作業化していること。

## A. 観点別判定

| # | 観点 | 判定 | 根拠（path:line） |
|---|---|---|---|
| 1 | Design gate（判断順・pattern を成果にしない） | **pass** | Spec:7 の判断順を §1→§2→§3→§4→§5→§6 で実際に守っている。factory / Strategy / Clock port / 汎用 Storage を `rejected_overdesign`（Spec:234-240）で明示 reject。追加 port は `PodcastApi` 1 つで品質根拠（QL2）付き（Spec:95-101）。`abstraction_decisions` は 3 件すべて rationale 付き |
| 2 | code-design（capsule と隠す技術の一致） | **revise** | CP1〜CP9 の owns/hides は目的中心で妥当。ただし CP5 の `PodcastViewModel ↛ ApiClient`（Spec:91）が実コードと不整合（`PodcastViewModel.kt:143,157,488` が `fetchVocabulary`/`saveVocabulary`/`submitQuizAnswers` を呼ぶ＝`PodcastApi` 5 メソッドに含まれない）。`nowPlaying` の field 集合が §3.1（Spec:129）と §6（Spec:263）で不一致、かつ UI の実読み出しと一致しない |
| 3 | §8 決定との整合 | **revise** | 順 1〜4・保留・記録のみの骨格は一致。逸脱 3 件: RF16 の作業化（§8:417「記録のみ」）、`onSubjectLeave` rename（§8 に無い）、pending SG を前提化。AuthState 4 状態化の不採用・完聴位置=duration・一括切替は SG-R13/R14/R15 として登録済み（Spec:293-295）＝手続きは正しい |
| 4 | 共有仕様互換 | **revise** | Queue `init` の require は §2.4 clamp / §2.5 dedupe / Q-18 と衝突しない（conformance 32 行の直接構築はすべて有効状態。`PlaybackQueueConformanceTest.kt:21-336` を確認）。ただし `setQueue` は dedupe しない（`PlaybackQueue.kt:39-44`）ため CI-T7 の「公開操作は throw しない」は 1 点で偽。ResumeRule の 2 秒窓は §6.2 と矛盾しない（§6.2 は同期方向のみ規定、完聴境界に沈黙）。一方 §6.2/§6.3 への「追記」は実は主題変更 |
| 5 | 過剰・不足 | **revise** | 保留（RF6 全面 / RF8 / RF9 / RF10）は順 1〜3 をブロックしない（Unauthorized 単独追加は `ApiException.kt:8-20` に閉じる）。**例外が RF8**: 上記 #2 のとおり S1 の目標を阻む。不足 1 件: `playMutex`／`syncJob` 直列化の契約と特性テストが無い。疑われた 3 点は非該当（下記 §C） |
| 6 | testability | **revise** | T-T1〜T-T18 の oracle は概ね公開 API 経由。ただし T-T1 は「11 遷移」の分母のみで駆動入力が無く、そのままでは RED を書けない。CI-T2 は Fake 側の自作写像を見るだけで契約（Media3 errorCode → reason）を検証しない。`PlaybackException.errorCode` の分類表が Spec に無く実装者判断に委ねられている。JVM 不可（UV4: ExoPlayer 配線・Keystore）は Spec:290 で明示済み＝pass |
| 7 | 移行現実性 | **revise** | S2 の特性テスト 8 ファイルの件数はすべて実測と一致（§G）。消費者の取りこぼし 1 件（play 連打の直列化）、回復手段は revert のみで Spec:266-307 に明記済み＝許容。TP1 は根拠が誤同定（`AuthViewModel.kt:152` は login の文言分岐）。TP2 の削除条件は判定式（`state.Ended` のみ購読）＝pass |

## B. 修正リスト

### 必須（実装着手前）

1. **`onUnauthorized` に「トークン付与済み応答のみ」の事前条件を入れる。**
   - Spec のどこを: §2 prohibited_structures（Spec:92）・CI-T11（Spec:188）・T-T11。
   - 何に: CI-T11 の statement を「**`Authorization` ヘッダを付与したリクエスト**の 401 応答でのみ `onUnauthorized` を 1 回呼ぶ。トークン未付与（`tokenProvider()==null`）の 401、および認証エンドポイント（login / passkey）の 401 では呼ばない」に書き換え、T-T11 に反例 2 件（未付与 401・login 401）を追加する。
   - 根拠: `AuthInterceptor.kt:26-39` は三点一致だけで判定し、ヘッダ付与の有無を返り値に残さない。`AuthViewModel.kt:137` のコメントどおり login は資格情報誤りで 401 を返す。現状の Spec のまま実装すると、セッション保持中の再ログイン画面でのパスワード誤入力が「主体離脱」と判定され、`AppContainer.kt:266-281` の cleanup（音声キャッシュ全削除・進行中 DL cancel・FCM 解除）＋新規の再生停止が走る。可用性ではなくデータ喪失の問題。

2. **位置同期の事前条件を「player が prepare 済みの session」に限定する。**
   - Spec のどこを: §3.1 Coordinator（Spec:141-145）と CI-T4/CI-T5、新規 CI-T19 として追加。
   - 何に: 「最終同期・15 秒同期は `Starting`(prepare 済み) / `Active` / `Completed` の session でのみ行う。`Errored` / `Stopped`(既同期済み) / `NothingPlaying` では `updatePlaybackPosition` を呼ばない」と明記し、T-T5 の系列に「NETWORK 取得失敗 → `stopForSubjectLeave` / 次の `play` で `updatePlaybackPosition` が呼ばれない」を追加。
   - 根拠: 現行は `stopInternal` が `_currentPodcast.value?.id ?: return`（`PodcastViewModel.kt:503`）で守っている。`_currentPodcast` を削除し「session が `NothingPlaying` でない」に置き換えると、`Errored(FetchFailed)`（player 未 prepare、`positionSeconds` は前回停止時の 0）でも最終同期が走り、`PodcastViewModel.kt:538` の `updatePlaybackPosition(id, 0.0)` で server の resume 位置を破壊する。Spec:142 は「queue は進めたまま INV-P1 は `episodeRef` で維持」と書いており、この経路が実在する。

3. **`PodcastViewModel ↛ ApiClient` の禁止事項を実態に合わせる。**
   - Spec のどこを: §2 prohibited_structures（Spec:91）、CI-T16（Spec:193）、S1（Spec:258）。
   - 何に: 「`PodcastViewModel` は再生 UC については `PodcastApi` の 5 操作だけを使う。語彙（`fetchVocabulary` / `saveVocabulary`）とクイズ（`submitQuizAnswers`）は RF8 保留のため当面 `ApiClient` 依存のまま残す（S4 で `VocabularyApi` / 既存 `VocabularyTestApi` へ移す）」と書き分ける。CI-T16 の「再生 RED テストの Fake が 5 メソッドで書ける」は「再生系テストは `FakePodcastApi` 5 メソッド＋`BaseFakeApiClient` の error default で足りる」に修正。
   - 根拠: `PodcastViewModel.kt:131,143,157,207,208,306,354,488,538` の 8 操作のうち 3 操作が `PodcastApi` の外。現 Spec のままでは S1 の完了条件が満たせず、`PodcastViewModelTest`（52 件）の Fake も 5 メソッドに縮まらない。

4. **`AuthViewModel.kt:152` の同定を訂正し、login 401 の文言保持を契約にする。**
   - Spec のどこを: §3.2 ApiException（Spec:161）と TP1（Spec:257）。
   - 何に: 「失効の Evidence は `AuthViewModel.kt:111-114`（`catch (e: ApiException)` で無条件 `clear`）。`:151-152` は **login の資格情報誤り文言**であり、401→`Unauthorized` 化で `catch (e: ApiException.HttpError)` を外れるため、`Unauthorized` catch を足して同じ文言（"ユーザーIDまたはパスワードが正しくありません"）を維持する（観測挙動の保存）」に書き換え、T-T14 か新 T-T20 で pin する。
   - 根拠: `AuthViewModel.kt:151-152` は login 内。`AuthViewModelTest.kt`（24 件）に login 401 文言のテストが含まれるため、修正漏れは S0 で RED になるが、Spec が「互換層」と誤記しているままでは意図が伝わらない。

5. **pending SG を前提として S0/S2 に組み込むのをやめる。**
   - Spec のどこを: §0 `decision_maturity`（Spec:21）、S0/S2 の内容（Spec:257,259）、§7 の status（Spec:270-280）。
   - 何に: §0 の `evidence_status: confirmed` / `approval_evidence: ["review §8..."]` を `pending: [SG-R13, SG-R14, SG-R15]` に修正（§8 の `decision.approval_evidence: []`（Spec:301）と矛盾している）。S0 の `lastFailure` を `blocked_on: SG-R13`、S2 の完聴位置=duration を `blocked_on: SG-R14`、切替方式を `blocked_on: SG-R15` と明示。§7 の R3/R6 の `covered` を `covered（SG-R14 確定後）` に。
   - 根拠: SG-R13 は S0 の CI-T10（`lastFailure` を要求）に、SG-R14 は CI-T8 の呼出列に、SG-R15 は S2 全体に効く。pending の既定値を本文の確定事項として書くのは web 側 gate でも同じ指摘を受けている（`web/.../spec-gate.md` の「前提先走り」）。

6. **§8 で「記録のみ」とした RF16 の作業化を、逸脱として明示するか S0 から外す。**
   - Spec のどこを: S0（Spec:257）、CI-T14（Spec:191）、§3.2 SessionStore（Spec:163）。
   - 何に: 「§8.3 は RF16 を記録のみと決めたが、`SessionStore.save` の失敗返却と `login` の遷移抑止は S0 で同時に閉じる」と逸脱として書き、追加コスト（`SessionStore` interface の戻り値変更＝`InMemorySessionStore`・`KeystoreSessionStore`・`AuthViewModelTest`・`completePasskeyLogin`（`AuthViewModel.kt:194-195`）へ波及）を示して user の再確認対象（新 SG-R16）にする。外す場合は CI-T14 / T-T14 を S4 へ移す。
   - 根拠: review §8:417「記録のみ | RF16・RF17・RF18」。RF17 は Spec も現状維持で一致、RF18 は web 側。RF16 だけが作業化されている。

7. **`nowPlaying` の field 集合を 1 箇所に確定し、`Errored` 時の値を決める。**
   - Spec のどこを: INV-P1（Spec:129）と §6 の scope 注意（Spec:263）の 2 箇所を 1 つの表に統合。
   - 何に: 実読み出しに合わせて `{episodeId, title, japaneseIntroText, segments, vocabulary, quiz}` を確定する。`durationSeconds` は **CP1（player）を正本**とし `nowPlaying` に入れない（入れると同一概念の二重表現になり R3 に反する）。`difficulty` は読み手が無いので落とす。加えて「`Errored(episodeRef)` のとき `nowPlaying` は null（＝プレイヤー非表示を維持）」を明記する。
   - 根拠: 実際の読み手は `AudioPlayerSection.kt:75,101,115,125,126,142`（id / japaneseIntroText / segments / vocabulary / quiz）、`PodcastScreen.kt:160,204`（id 比較と null 判定）、`QueueSheet.kt:58,83`（null 判定のみ。表示は `queue.current`）。duration は `AudioPlayerSection.kt:68` が `viewModel.durationSeconds`（player 由来）を読んでおり DTO 値は未使用。`PodcastScreen.kt:204` は `currentPodcast != null` でプレイヤー表示を切っているため、`episodeRef` だけの状態を non-null にすると空のプレイヤーが出る（観測挙動の変更）。

8. **`play` の直列化（`playMutex` / `syncJob`）を契約と特性テストに載せる。**
   - Spec のどこを: §3.1 Coordinator（Spec:141-145）に契約を追加、§6 S2 の特性テスト列に 1 本追加。
   - 何に: CI として「`play` の二重呼び出しでも `stopInternal` 相当は 1 回だけ走り、旧 `syncJob` は孤児化しない。`queue` と `session` は同一 lock 下で更新される（INV-P1 が中間状態で破れない）」を追加し、S2 の baseline に `PodcastViewModelTest` への「同一 dispatcher 上で play を連続呼び出し」1 本を先行追加する。
   - 根拠: `PodcastViewModel.kt:272-290` と `:428-432` のコメントは過去レビュー修正の産物（同期タイマー孤児化・queue の read-modify-write 消失）。`PodcastViewModelTest.kt` に `並行|同時|連打|Mutex` の一致は 0 件で pin が存在しない。S2 は一括切替なので、Spec に契約が無ければこの不変条件は再実装時に失われる。

9. **CI-T7 の「公開操作は throw しない」を `setQueue` について正す。**
   - Spec のどこを: §3.1 Queue（Spec:133）と CI-T7（Spec:184）。
   - 何に: (a) `setQueue` に dedupe を足して共有仕様 §2.4 へ dedupe 行の追記を要求する、または (b) 「`setQueue` は §2.4 のとおり clamp のみで dedupe しないため、重複 id を含む `items` では `init` が throw する。production では未使用（`PodcastViewModel` の `_queue.value` 代入 6 箇所に `setQueue` は無い）」と限定を書く。どちらかを選び CI-T7 の statement に反映。
   - 根拠: `PlaybackQueue.kt:39-44`（clamp のみ）、`PlaybackQueue.kt:47-48,55-66,101-114`（add / playNext / remove は正規化済み）。`PodcastViewModel.kt:362,382,392,402,419,432` の代入はすべて公開操作経由。

10. **共有仕様 §6.2/§6.3 への変更を「追記」と書かない。**
   - Spec のどこを: §0 の `public_contract_change_allowed` コメント（Spec:20）、§3.1 ResumeRule（Spec:135）、§6 S2（Spec:259）、§7 R9（Spec:280）。
   - 何に: §6.2 は「オフライン中の再生位置同期」（server-wins）、§6.3 は「logout 時のキャッシュ削除（共有端末対応）」が主題であり、完聴境界 2 秒窓・位置同期の送信条件・再生状態の停止はいずれも主題外。**新節（例: §6.4「resume 位置と完聴境界」・§6.5「主体離脱時の事後条件」）として提案し、web/iOS へ波及する範囲を独立の合意 gate に切り出す**（3 platform 合意は android セッションでは閉じない）。§6.3 に Android 行だけ足す案を採るなら、行の内容は「音声キャッシュ全削除＋FCM 解除」に留め、再生状態の停止は android ローカル決定として Spec に置く。
   - 根拠: shared-playback-spec.md の §6.2 は 6 行（server-wins 戦略と理由のみ）、§6.3 は Web/iOS 2 行の表と「削除失敗でもログアウト UI 状態は即座に未認証へ遷移」。あわせて、現行 `AuthViewModel.kt:167-182` は `onLogoutCleanup()` の**完了を待って**から `_authState` を落としており（`AppContainer.kt:270-281` の cleanup は `cancelAndJoin` を含む）、§6.3 の「即座に」と既に食い違う。`CleanupIncomplete` を入れる S0 で、authState 遷移と cleanup の順序をどちらにするかを決めて明記すること。

### 推奨（着手はブロックしない）

11. **T-T1 の 11 遷移に駆動入力の列を足す。** Spec:131 は分母 11 を固定しているが、各遷移を起こす入力（player state 注入 / `FakePodcastApi` の応答 / queue の事前状態）が無く、そのままでは RED を書けない。`Completed→Stopped` は queue 末尾、`Errored→Starting(retry)` は `retry()` 呼出、`Active→Starting(playNow)` は Mutex 経路を通るため、入力を書かないと oracle が実装者判断になる。`FakePlayerController`（`app/src/test/java/com/rioikeda/newslisten/podcast/FakePlayerController.kt`）に `state` を注入できれば player 起因の 6 遷移は駆動可能で、残り 5 は api/queue 側の入力が必要。
12. **CI-T2 を 2 つに割り、coverage を再集計する。** CI-T2a（union の消費側・JVM 検証可）と CI-T2b（`PlaybackException.errorCode` → `Source|Decode|Network|Unknown` の写像・JVM 不可）。加えて **errorCode の分類表が Spec に無い**（`ExoPlayerController.kt:71-115` に `onPlayerError` の購読自体が無いので、どの errorCode をどの reason に写すかは新規決定）。Spec:197 の coverage を「分母 19 / test 仕様あり 19 / 実行 0 / JVM 検証不能 2（CI-T2b, CI-T18）」の形に直す。
13. **速度の値域を Double 1 本にする。** `PlaybackConstants`（`PlaybackConstants.kt:11-19`）は `List<Float>`、`SettingsScreen.kt:1361` は `List<Double>`（5 段）、preferences は Double。Spec:137 の「`PlaybackConstants` 側に Double 版を 1 つ置く」は 8 値を 2 箇所に持つことになり R1 に反する。正本を `List<Double>` にし、`toFloat()` は CP1 の内側で行う。なお `AudioPlayerSection.kt:283` は既に `PlaybackConstants.speeds` を使うので UI 側の変更は Settings だけ（Spec の記述は正しい）。
14. **`setDefaultPlaybackSpeed` の正規化方針を書く。** Spec:137 の「既定 1.0 へ正規化」は `AuthViewModel.kt:121-133` の server 同期経路も通るため、8 段外の server 値が来ると端末値が 1.0 になり server へは書き戻されない（恒久的な不一致）。最近傍の段へ丸めるか、拒否してローカル値を保持し観測可能にするかを CI-T15 の statement に書く。実運用では旧 5 段が 8 段の部分集合なので発火しない見込み、という根拠も添えると判断が残る。
15. **CACHED 経路の resume が stale であることを §6.2 相当の追記に含める。** Spec:135 は「CACHED 経路は一覧 DTO の `playbackPositionSeconds`（一覧取得時点の値）」と書いており oracle も CI-T4 にある。server-wins の例外（オフライン中は一覧取得時点の server 値）として共有仕様側に書かないと、3 platform で解釈が割れる。
16. **Spec:197 の「met へ変わる見込み 22 件」に分母を書く。** 既存 CI 総数が無いため比率が検証できない。

## C. 疑われたが非該当（確認済み・修正不要）

- **MediaSession 通知の再生状態**: `playbackservice/PlaybackService.kt:38-39` が `appContainer.getPlayerController().player`（`ExoPlayerController.kt:202` の具象プロパティ）を直接 MediaSession に渡す構成。`PlaybackState` union の追加は通知経路と直交で、leakage guard（Spec:93,249）と実態が一致している。
- **`AudioPlayerSection` の速度選択 UI**: `AudioPlayerSection.kt:283` は既に `PlaybackConstants.speeds`（8 段）を使用。8 段統一で変更が必要なのは `SettingsScreen.kt:1360-1361` のみで、Spec:137 の記述どおり。
- **`PlaybackService` の `onPlaybackCompleted` 依存**: 依存は無い（`onPlaybackCompleted` の参照は `ExoPlayerController.kt:67,90`・`PodcastViewModel.kt:327`・`PlayerController.kt:42` の 4 箇所のみ）。TP2 の削除条件（`state.Ended` のみ購読）は判定可能。
- **Queue `init` throw と conformance 32 行**: `PlaybackQueueConformanceTest.kt` の直接構築 27 箇所はすべて不変条件 1〜3 を満たす有効状態で、`require` 追加で落ちる行は無い（重複 id を直接構築する行は存在せず、Q-11 は `playNext` 経由）。
- **S2 特性テストの件数**: すべて実測一致（§G）。

## D. user へ返すべき Selection Gate

```yaml
selection_gates_for_user:
  - {id: SG-R13, subject: "AuthState を 4 状態化せず Unknown + lastFailure で表す", status: pending, default: adopt,
     note: "S0 の CI-T10 が lastFailure を要求するため、S0 着手前に確定が必要（Spec は確定済みとして本文に書いている）"}
  - {id: SG-R14, subject: "完聴時の位置同期の値を duration にする", status: pending, default: adopt,
     note: "CI-T8 の呼出列 oracle に直結。修正 2（Errored では同期しない）と併せて判断"}
  - {id: SG-R15, subject: "S2 の切替方式（一括 / 段階）", status: pending, default: 一括,
     note: "回復手段が revert のみ。修正 8（play 連打の特性テスト）を baseline に加えた上で判断"}
  - {id: SG-R16, subject: "§8 で『記録のみ』とした RF16（SessionStore.save の失敗返却）を S0 で同時に閉じるか", status: pending, default: "S0 に含める",
     new: true, note: "修正 6。含める場合 SessionStore interface の戻り値変更が 2 実装 + 2 テスト + completePasskeyLogin に波及"}
  - {id: SG-R17, subject: "共有仕様 §6.2/§6.3 の扱い（新節として 3 platform 合意を取るか、android ローカル決定に留めるか）", status: pending, default: "新節 + 独立合意 gate",
     new: true, note: "修正 10。S2 の前提（Spec:307 の residual_risk）でもあるため、S2 着手前に決める必要がある"}
  - {id: SG-R18, subject: "`onLogoutCleanup` → `onSubjectLeave` の rename を行うか", status: pending, default: "行う（S0 の独立コミット）",
     new: true, note: "§8 に無い自律決定。AuthViewModel.kt:58 / AppContainer.kt:266 / AuthViewModelTest に波及する純粋 rename"}
```

## E. 棄却した検討案

- 目的: `Errored` 時の position 上書きを型で防ぐ／前提: `PlaybackSession` の各状態が player の prepare 済みかを型で持てば事前条件をコンパイル時に強制できる（未検証: Kotlin の sealed 階層を 2 軸（session 意味 × player 有無）に割ると 11 遷移の分母が増える）／やったこと: `Starting` を `StartingPrepared` / `StartingUnprepared` に割る案を検討／結果: 遷移表の分母が 11→14 に増え SG-R15 の一括切替範囲を広げるため棄却。修正 2 の「同期の事前条件を契約で書く」を採用／残課題: 事前条件違反は実行時にしか検出できない（T-T5 の系列テストで代替）。
- 目的: `onUnauthorized` の誤発火を経路分離で防ぐ／前提: iOS は `buildRequest` の外側で downloadAudio を組むという構造分離を採っている（`AuthInterceptor.kt:10-14` のコメント）／やったこと: 認証エンドポイント専用の OkHttpClient を別に持ち、interceptor を挟まない案を検討／結果: `OkHttpApiClient` の単一 client 前提（`ApiClient.kt` 全 44 操作が同一 client）を崩し S0 の範囲を超えるため棄却。修正 1 の「ヘッダ付与済み応答のみ」条件で足りる／残課題: passkey の 401 も同じ条件で除外されるかは実装時に確認が必要。
- 目的: `nowPlaying` を作らず UI に `session` を直接渡す案／前提: session は既に episode DTO を持つ（Spec:123-127）／やったこと: `PodcastScreen` / `AudioPlayerSection` が `session` を when 分岐する構成を検討／結果: UI に状態 union の分岐が増え「caller 側分岐」を招くため棄却。派生 view model（`nowPlaying`）の方が UI の変更理由を 1 つに保てる／残課題: `Errored` の文言表示経路は `errorMessage` のままか、`nowPlaying` と別 flow にするかが未決（修正 7 に含めた）。
- 目的: S2 を段階切替にする案／前提: web SG8 は一括／やったこと: `nowPlaying` を `_currentPodcast` から先に導出して UI 3 ファイルだけ切り替え、後で session に差し替える 2 段案を検討／結果: 二重 owner が一時的に生まれ SG-R1（正本一意）に反する期間ができるため棄却（Spec:239 の判断に同意）／残課題: 回復手段が revert のみという residual risk は残る（修正 8 の特性テストで緩和）。

## F. unknown

```yaml
unknowns:
  - {id: UK1, subject: "Media3 の `PlaybackException.errorCode` から Source/Decode/Network/Unknown への写像表", how_to_resolve: "Media3 の errorCode 定数群を実装時に列挙し、分類表を Spec に追記してから CI-T2b を書く", impact_if_unresolved: "reason の分類が実装者判断になり、CI-T9（CACHED 破損時の invalidate）の発火条件が再現不能"}
  - {id: UK2, subject: "passkey ログインの 401 応答が `AuthInterceptor` の三点一致 host を通るか", how_to_resolve: "`OkHttpApiClient` の passkey 経路の URL を実装時に確認（本 gate では未確認）", impact_if_unresolved: "修正 1 の除外条件が passkey を取りこぼす"}
  - {id: UK3, subject: "`AuthViewModelTest.kt:95` 反転後に他の 23 件が前提にしている挙動があるか", how_to_resolve: "S0 の baseline 実行で確認（本 gate は `:95` 付近の 1 件のみ読んだ）", impact_if_unresolved: "S0 の RED が想定より広がる"}
  - {id: UK4, subject: "`fetchPodcasts` 再取得時に queue を作り直す経路の有無", how_to_resolve: "`PodcastViewModel.kt:131` 周辺の一覧更新が queue に触るかを実装時に確認", impact_if_unresolved: "Queue `init` require が server 由来の重複 id で production crash になる経路が残る"}
  - {id: UK5, subject: "backend が `updatePlaybackPosition(duration)` を完聴として扱うか（SG-R14 の前提）", how_to_resolve: "review §8.4 の U1/U2 と同様、backend 側の確認待ち（Spec:304 は clamp のみと記録）", impact_if_unresolved: "完聴の二重記録（markCompleted と position=duration）の意味が backend で重複"}
```

## G. 実測との突合（Spec の主張の検算）

| Spec の主張 | 実測 | 判定 |
|---|---|---|
| `PodcastViewModelTest` 52 | `grep -c '@Test'` = 52 | 一致 |
| `PlaybackQueueConformanceTest` 32 | 32 | 一致 |
| `AuthViewModelTest` 24 | 24 | 一致 |
| `SettingsViewModelTest` 24 | 24 | 一致 |
| `DataStorePreferencesStoreTest` 5 | 5 | 一致 |
| `AudioCacheManagerTest` 14 | 14 | 一致 |
| `AuthInterceptorTest` 6 | 6 | 一致 |
| `ApiClient` に throwing default がある（N3） | `ApiClient.kt:66-68` の `markCompleted` が `error(...)` | 一致 |
| `PLAYBACK_SPEEDS` は 5 段 Double | `SettingsScreen.kt:1361` = `listOf(0.75, 1.0, 1.25, 1.5, 2.0)` | 一致 |
| `AuthViewModel.kt:152` が 401 比較 | 401 比較だが **login** の文言分岐（失効経路ではない） | 不一致 → 修正 4 |
| `PodcastViewModel` は `PodcastApi` 5 操作で足りる | 実際は 8 操作（語彙 2・クイズ 1 が外） | 不一致 → 修正 3 |

## H. 自分の引用の範囲検査（`wc -l`）

| file | 行数 | 本書の最大引用行 | 判定 |
|---|---|---|---|
| `podcast/PodcastViewModel.kt` | 560 | 538 | 範囲内 |
| `podcast/PlayerController.kt` | 78 | 42 | 範囲内 |
| `podcast/ExoPlayerController.kt` | 237 | 202 | 範囲内 |
| `core/PlaybackQueue.kt` | 137 | 114 | 範囲内 |
| `auth/AuthViewModel.kt` | 218 | 195 | 範囲内 |
| `network/AuthInterceptor.kt` | 40 | 39 | 範囲内 |
| `network/ApiClient.kt` | 224 | 68 | 範囲内 |
| `network/ApiException.kt` | 20 | 20 | 範囲内 |
| `di/AppContainer.kt` | 537 | 281 | 範囲内 |
| `podcast/PodcastScreen.kt` | 240 | 204 | 範囲内 |
| `podcast/QueueSheet.kt` | 302 | 83 | 範囲内 |
| `podcast/AudioPlayerSection.kt` | 536 | 283 | 範囲内 |
| `settings/SettingsScreen.kt` | 1362 | 1361 | 範囲内 |
| `podcast/PlaybackConstants.kt` | 19 | 19 | 範囲内（行単位の断定を避け 11-19 で引用） |
| `playbackservice/PlaybackService.kt` | 65 | 39 | 範囲内 |
| `test/auth/AuthViewModelTest.kt` | 386 | 95 | 範囲内 |
| `test/core/PlaybackQueueConformanceTest.kt` | 347 | 336 | 範囲内 |
| `docs/design/shared-playback-spec.md` | 327 | 316 | 範囲内 |
| `docs/research-reports/2026-09-16-code-design-review.md` | 424 | 417 | 範囲内 |
| 監査対象 Spec | 308 | 307 | 範囲内 |

いずれも連結出力ではなく単一ファイルの `sed -n` / `cat -n` / `grep -n` から取得した（trial-log `mino-design-review-delegation.md` の「連結出力の行番号を引用した」失敗の再発防止）。

## I. 既存 trial-log との整合

`docs/trial-log/` 3 件を確認した。`mino-design-review-delegation.md` に記録済みの棄却・失敗（連結 `cat` の行番号引用、JDK 26 での `./gradlew test` 失敗と `clean` 必須、ワーカーの turn 上限）はいずれも本 gate の指摘と衝突せず、再導入もしていない。検証コマンドが `JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest` である点（Spec:253,288）は同 trial-log の結論と一致しており、`clean` が入っているのも正しい。`featured-categories.md` / `launcher-icon-rebrand.md` は本設計と無関係。

# Architecture Strategy Package — news-listen/android（module 内部の data authority と品質 portfolio）

routing_context: origin=integrated / mode=review / requested_by=router / return_to=router / mutation_authorized=false / requested_artifact=architecture_strategy_package / re_routing=forbidden。他 Function・peer の起動は行っていない。source・設定・git は一切変更していない（成果物は scratchpad のみ）。

scope note: 本 package は **android module 内部の data authority と品質 portfolio** に限定する。backend / iOS / web の実装、および system-wide architecture 選定は out_of_scope。共有仕様 `docs/design/shared-playback-spec.md` は「正本 document」として参照するだけで、仕様自体の改訂案は SG / OB として人間へ返す。

> **Evidence 規律に関する注意（読む前に）**: 共通ブリーフ §4 の path:line 引用のうち 3 件は対象ファイルの行数を超えており、そのまま転記できなかった。詳細は §11 `evidence_integrity` に記す。本 package の引用はすべて 1 ファイル 1 コマンドで再取得し、§12 で `wc -l` 機械照合した結果のみを載せている。

---

## 1. decision_frame

```yaml
decision_frame:
  question: >-
    news-listen android client 内部の data authority（現在再生中 Podcast・再生速度・再生位置・
    再生状態・認証状態とトークン・ユーザー固有キャッシュ）は、R1–R9 を満たす一意な writer 構造に
    なっているか。なっていない fact について、どの選択肢を人間が決める必要があり、どの負債を
    どの順で返す候補があるか。
  owner: user
  approvers: [user]
  horizon: "現行 phase（フェーズ17 まで実装済み）の直後 1〜2 phase。長期 roadmap は未提示のため未評価。"
  target_platform: "Android のみ（single platform）。platform 別分岐は §9 参照。"
  decision_maturity:
    status: proposed
    owner: user
    scope:
      - "android module 内部の 6 fact の data authority"
      - "QL1–QL4 品質 portfolio と scenario"
      - "技術的負債の priority 候補"
    evidence_status: confirmed        # 実コードを本 review で直接再取得して確認
    approval_evidence: []             # user 承認なし。AI は確定しない
    baseline_version: ""              # ADR 化された target architecture baseline は本 review 時点で未設定
    change_control: >-
      未設定。SG1–SG6 が pending のため、target authority は「選択済み」として表現しない。
      user が SG を閉じるまで conditional design に留める。
  actors:
    - id: A1
      purpose: "学習者として、聴き途中の podcast を端末をまたいで正しい位置・速度から再生し、オフラインでも聴ける。"
      evidence:
        - status: confirmed
          source: "app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt:293（resolvePlaybackSource による cached 優先）、:536-542（位置の定期送信）"
          supports: "オフライン再生と位置記録が実装済み capability として存在する。"
        - status: contradicted
          source: "app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt:324-331（beginPlayback が prepare→play のみ）"
          supports: >-
            「正しい位置から再生」は未実装。位置は書かれるが読まれないため、A1 の purpose の
            半分が現状の android 実装では成立しない（F1）。
    - id: A2
      purpose: "共有端末の利用者として、ログアウト・セッション失効の後に自分の認証済みデータが次の利用者へ残らない。"
      evidence:
        - status: confirmed
          source: "app/src/main/java/com/rioikeda/newslisten/di/AppContainer.kt:266-281（logout 時に音声 cache + FCM token を消す合成ラムダ）、docs/design/shared-playback-spec.md:305-316（§6.3）"
          supports: "残留防止が明示された設計意図として存在し、logout 経路では部分的に実装されている。"
        - status: contradicted
          source: "app/src/main/java/com/rioikeda/newslisten/auth/AuthViewModel.kt:111-114（失効経路は sessionStore.clear のみで onLogoutCleanup を呼ばない）"
          supports: "失効経路では cleanup が走らないため、A2 の purpose は logout 導線のみで成立する（F4）。"
    - id: A3
      purpose: "この repository の保守者（user）として、共有仕様（Q-*/RT-*/§6）や業務ルールの変更を 1 箇所の修正で安全に反映できる。"
      evidence:
        - status: confirmed
          source: "android/CLAUDE.md（TDD 必須）、core/PlaybackQueue.kt:23-29（仕様正本を参照する純粋モデル）"
          supports: "仕様正本に追随する保守が明示的な作業前提であり、core は純粋に保たれている。"
        - status: confirmed
          source: "app/src/main/java/com/rioikeda/newslisten/podcast/PodcastViewModel.kt:341-346（queue と currentPodcast の不整合を実装コメントが自認）"
          supports: "二重 authority の保守コストが既に実装者に認識されている（F2）。"
  product_values:
    - id: V1
      statement: "聴取状態（どれを・どこから・どの速度で）が、ユーザーの期待どおり一意に復元される。"
      actor_ids: [A1]
      success_signals:
        - "resume 位置が server/local のどちらか一方の規則で説明できる"
        - "設定した既定速度が次の再生開始時に適用される"
        - "『再生中』表示が一時停止・エラーと区別できる"
      owner: user
      evidence:
        - status: confirmed
          source: "docs/design/shared-playback-spec.md:298-303（§6.2 server-wins が共有方針として宣言されている）"
          supports: "復元規則が product 意図として文書化されている。"
        - status: contradicted
          source: "app/src/main/java/com/rioikeda/newslisten/model/PodcastResponse.kt:31 が唯一の出現（本 review の全 module grep で reader 0 件）"
          supports: "V1 の『どこから』は android で成立していない。"
    - id: V2
      statement: "共有端末・セッション失効後に、他利用者の認証済みデータが端末に見えない。"
      actor_ids: [A2]
      success_signals:
        - "失効後に音声 cache・FCM token・preferences・再生 queue が残らない"
        - "一時的な通信障害でログイン状態を失わない"
      owner: user
      evidence:
        - status: confirmed
          source: "docs/design/shared-playback-spec.md:305-316（§6.3 が全 platform 共通方針として削除を要求）"
          supports: "削除範囲が仕様として存在する。ただし『preferences・再生状態』は §6.3 の表に列挙されていない（SG4 の論点）。"
    - id: V3
      statement: "業務ルール（HTTP status の意味・再生可否・quota・文言）の変更が 1 箇所で完結する。"
      actor_ids: [A3]
      success_signals:
        - "status code の数値比較が network 境界の内側だけに現れる"
        - "Screen 層に業務判断が無い"
      owner: user
      evidence:
        - status: confirmed
          source: "app/src/main/java/com/rioikeda/newslisten/network/ApiException.kt:8-20（失敗型が 4 種のみで、意味が transport 値のまま残る）"
          supports: "V3 を阻害する構造原因が型レベルで特定できる（F5）。"
```

### 1.1 capability classification

`core / supporting / generic` は business capability / subdomain にのみ適用する（technical capability には付けない）。

| ID | capability | kind | classification | 根拠 Evidence |
|---|---|---|---|---|
| C1 | 聴取セッション管理（何を・どこから・どの速度・どの状態で再生するか） | subdomain | core | `core/PlaybackQueue.kt:23-29` と `docs/design/shared-playback-spec.md:39-56` が platform 共通の不変条件を定義しており、この意味論が product 固有価値（V1）を担う |
| C2 | オフライン聴取（cache 優先解決・download 管理） | subdomain | supporting | `docs/design/shared-playback-spec.md:284-296` が解決規則を定義。C1 の価値を可用性面で支えるが、独自意味論は cache/online の 2 値判定のみ |
| C3 | セッション・認証状態管理 | subdomain | supporting | `auth/AuthState.kt:13-22` は汎用的な三状態。ただし「失効時の cleanup 範囲」は product 固有（V2）で、そこだけは core に近い |
| C4 | ユーザー設定の所有と server 同期 | subdomain | supporting | `preferences/DataStorePreferencesStore.kt:41-115` が値の所有を担う。意味論は key-value 同期で独自性は低い |
| C5 | HTTP 通信・DTO 変換 | technical_capability | not_applicable（kind が technical のため classification を付けない） | `network/ApiClient.kt:36` は transport 抽象。business 意味を持たない |
| C6 | Media3 による音声出力・MediaSession 提供 | technical_capability | not_applicable（同上） | `podcast/ExoPlayerController.kt:34-53`、`playbackservice/PlaybackService.kt:30-54` は platform SDK の adapter |
| C7 | DI / 依存グラフ組立 | technical_capability | not_applicable（同上） | `di/AppContainer.kt:63-66` は組立のみ |

> C1 だけを core とした理由: 「同一の queue 意味論を web / iOS / android の 3 実装で一致させる」ことが本 product の差別化点であり、仕様正本（`docs/design/shared-playback-spec.md:39-56`）が platform 非依存の不変条件として明文化されている唯一の領域である。C2–C4 に架空の unique value は作らない。

---

## 2. quality portfolio

共通ブリーフ §2 で router 確定・user 選択済みの ID をそのまま使う（再定義しない）。

```yaml
quality_portfolio:
  primary:
    - id: QL1
      reference_model: "ISO/IEC 25010:2023"
      characteristic: maintainability
      subcharacteristic: modifiability
      display_name_ja: 変更容易性
      why_primary: "V3（業務ルール変更が 1 箇所で完結）が直接この品質。A3 が単独保守者であり、変更コストが product 継続性の制約になる。"
    - id: QL2
      reference_model: "ISO/IEC 25010:2023"
      characteristic: maintainability
      subcharacteristic: testability
      display_name_ja: テスト容易性
      why_primary: "android/CLAUDE.md が TDD 必須を定め、`agent-rules/11-testing-strategy.md` が R7 を要求する。仕様準拠（R9）の検証手段そのもの。"
  secondary:
    - id: QL3
      reference_model: "ISO/IEC 25010:2023"
      characteristic: reliability
      subcharacteristic: fault tolerance
      display_name_ja: 障害許容性
      why_secondary: "V1 を壊す主因が『失敗時に状態が不正になる』ことであるため必要だが、primary の 2 つより投資順位は後ろ（user 選択）。"
  constraint:
    - id: QL4
      reference_model: "ISO/IEC 25010:2023"
      characteristic: security
      subcharacteristic: confidentiality
      display_name_ja: 機密性
      why_constraint: "V2 は「達成度を上げる」対象ではなく「下回ってはいけない床」。共有端末の残留は許容しない。"
  intentionally_not_optimized:
    - id: performance
      statement: "起動時間・音声 decode 効率・DataStore 読み取り遅延は本 review の投資対象にしない。"
      value_preservation: "0.5 秒ポーリング（podcast/ExoPlayerController.kt:210-226）と 15 秒 PATCH（podcast/PodcastViewModel.kt:522-533）が現行の実測問題として報告されていないため、現状維持で V1 を損なわない。"
      risk_statement: "位置同期が一時停止中も継続する（podcast/PodcastViewModel.kt:514-520 が自認）ため、電池・通信量の観測値が無い状態で『問題なし』と断定はできない。観測方法は OB-A5。"
      evidence:
        - status: unknown
          source: "計測結果が repository 内に存在しない（本 review では benchmark も実行していない）"
          verification_method: "実機での battery historian / network profiler 測定"
          impact_if_unresolved: "performance を non-goal としたまま V1 の可用性を損なう可能性が残る（低）"
```

### 2.1 quality scenario（観測可能な形）

| ID | QL | stimulus | environment | response | measure | 現状 |
|---|---|---|---|---|---|---|
| QS1 | QL1 | backend が 404 の意味を「機能未提供」から「リソース削除」へ変えた | 通常運用 | 修正箇所が network 境界 1 箇所で完結する | 変更ファイル数 = 1 | **未達**: `settings/SettingsViewModel.kt:158-160`、`network/OkHttpApiClient.kt:303`、`podcast/QuizSheet.kt:195` 等に 404 判断が分散（F5） |
| QS2 | QL1 | 速度選択肢を 8 段から 6 段へ変える | 通常運用 | 1 箇所の定義変更で player UI と settings UI が一致する | 変更箇所 = 1 | **未達**: `podcast/PlaybackConstants.kt:12`（8 段）と `settings/SettingsScreen.kt:1361`（5 段）が独立定義（F3） |
| QS3 | QL2 | `ApiClient` に method を 1 つ追加する | 開発時 | test double の修正が 1 箇所で済む | 修正 Fake ファイル数 | **部分達成**: `network/ApiClient.kt` は 9 個の default 実装を持ち Fake の override を削れるが、Fake は依然 9 ファイル 1,393 行（F6） |
| QS4 | QL2 | 「resume 位置は server 優先」という規則を回帰テストで固定する | 開発時 | production 経路を通る unit test で pin できる | 該当 test の有無 | **未達**: 規則自体が実装されておらず、pin 対象が無い（F1） |
| QS5 | QL3 | 再生中にメディア decode が失敗する | 端末上 | UI が「エラー」と判別できる状態になり、queue は不整合にならない | error 状態の観測可否 | **未達**: `podcast/ExoPlayerController.kt:71-115` に `onPlayerError` が無く、`isPlaying=false` として pause と区別不能（F7） |
| QS6 | QL3 | 完聴直後の次エピソード取得が通信失敗する | オフライン化 | `queue.current` と `currentPodcast` が一致し続ける | 乖離の有無 | **未達**: `podcast/PodcastViewModel.kt:305-310` の失敗時に currentPodcast=null / queue.current=next となる（F2） |
| QS7 | QL4 | 保存トークンが server 側で失効され `/auth/me` が 401 を返す | 共有端末 | 認証 UI とトークンに加え、音声 cache・FCM token・preferences も消える | 残留データ件数 = 0 | **未達**: `auth/AuthViewModel.kt:111-114` は cleanup を呼ばない（F4） |
| QS8 | QL4 | 地下鉄など一時的な通信断でアプリを起動する | オフライン | ログイン状態を維持し、トークンを破棄しない | トークン保持 | **未達**: 同 `:111-114` が `NetworkError` も未認証扱いにする（F4） |

trade-off: QS7 と QS8 は同じ 1 箇所（`auth/AuthViewModel.kt:111-114`）が原因でありながら要求が逆方向（「もっと消せ」と「消すな」）である。両立には「失効」と「一時障害」の型分離が必要で、これが SG3 の論点を生む。

---

## 3. data authority 表（本 package の中核）

判定基準: **writer が 1 つ**かつ**その writer 以外が同じ fact を保持しない**なら `unique`。同一 fact を 2 箇所が独立に保持・更新するなら `split`。

### (a) 現在再生中の Podcast

| 役割 | 所在 | 内容 |
|---|---|---|
| writer 1 | `podcast/PodcastViewModel.kt:325`（`beginPlayback` 内 `_currentPodcast.value = podcast`） | 再生開始時に設定 |
| writer 1' | `podcast/PodcastViewModel.kt:509-511`（`stopInternal` 内 `_currentPodcast.value = null`） | 停止時に解除（`keepCurrentPodcast=true` なら保持） |
| writer 2 | `podcast/PodcastViewModel.kt:362`（`_queue.value = advanced`） | `advance()` が `currentIndex` を進める |
| writer 2' | `podcast/PodcastViewModel.kt:382`、`:392`、`:402`、`:419`、`:432` | `playNow` / `playNext` / `addToQueue` / `removeFromQueue` / `moveUpNext` が queue を更新 |
| reader | `podcast/PodcastScreen.kt:61`、`:160`、`:204` / `podcast/AudioPlayerSection.kt:65` / `podcast/QueueSheet.kt:58` | UI は `currentPodcast` を読む |
| reader | `podcast/PodcastScreen.kt:62` / `podcast/QueueSheet.kt:57`、`:83` | UI は `queue`（`current`）も読む |

**authority: split（R3 違反）**。`_currentPodcast`（`:83-86`）と `_queue.currentIndex`（`:115-121`）が同じ「現在再生中」を独立に保持する。実装自身が `podcast/PodcastViewModel.kt:341-346` でこの不整合を「review 指摘」として自認し、手続き（事前 gate 判定 + `keepCurrentPodcast` flag）で補正している。補正は 2 経路で漏れる:

1. `podcast/PodcastViewModel.kt:305-310` — `play(next)` の NETWORK 失敗。`:291` の `stopInternal()` が既に `currentPodcast=null` にした後で `fetchPodcast` が throw するため、`queue.current=next` / `currentPodcast=null`。
2. `podcast/PodcastViewModel.kt:368` — `stopInternal(keepCurrentPodcast = next == null)`。queue 末尾で `next==null` のとき `currentPodcast` は完聴済みエピソードのまま残り、`queue.currentIndex` も末尾のまま。UI（`podcast/PodcastScreen.kt:160`）はこれを「再生中」と描画する。

`podcast/QueueSheet.kt:83` が `currentPodcast != null && queue.current != null` の両者一致を表示条件にしているのは、split を UI 側で吸収している証拠である。

### (b) 再生速度（既定 / セッション）

| 概念 | writer | reader | authority |
|---|---|---|---|
| 既定速度（永続・server 同期） | `preferences/DataStorePreferencesStore.kt:85-87`（`setDefaultPlaybackSpeed`）。呼出元は `auth/AuthViewModel.kt:125`（server → local）と `settings/SettingsViewModel.kt:190`（local → server 成功後） | `settings/SettingsScreen.kt:130`（Screen が store を直接購読）、`auth/AuthViewModel.kt:77`（委譲公開、本 review の grep では他 reader 0） | **unique**（store が正本。`auth/AuthViewModel.kt:32-35` と `settings/SettingsViewModel.kt:28-32` が「独自コピーを持たない」と明記） |
| セッション速度（揮発・実再生） | `podcast/ExoPlayerController.kt:169-174`（`setSpeed`）。呼出元は `podcast/PodcastViewModel.kt:465-467` ← `podcast/AudioPlayerSection.kt:285` のみ | `podcast/PodcastViewModel.kt:72`（委譲）→ player UI | **unique**（ExoPlayer + `_playbackSpeed` が正本） |

**authority: 各概念は unique だが、2 概念の関係が未定義（R3 の部分未達）**。`podcast/ExoPlayerController.kt:64` が `_playbackSpeed` を `1.0f` 固定で初期化し、`podcast/PodcastViewModel.kt:324-331`（`beginPlayback`）は `setSpeed` を呼ばない。全 module grep で `setSpeed` の呼出元は `podcast/AudioPlayerSection.kt:285` の 1 件のみであり、**既定速度が実再生へ適用される経路が存在しない**。したがって「既定速度」は設定画面と server にのみ存在し、聴取体験に影響しない死んだ設定である。

逆向きの漏れもある: セッション速度は `podcast/ExoPlayerController.kt:176-185` の `stop()` で reset されないため、エピソードを切り替えても前回の速度が残る。これが意図（セッション継続）なのか不具合なのかは実装・仕様のどこにも書かれていない（SG2）。

### (c) 再生位置（local / server）

| 役割 | 所在 | 内容 |
|---|---|---|
| local writer | `podcast/ExoPlayerController.kt:210-226`（0.5 秒ポーリング）、`:161-167`（`seekTo` 即時反映） | `_positionSeconds` |
| server writer | `podcast/PodcastViewModel.kt:536-542`（`syncPosition` → `apiClient.updatePlaybackPosition`）。起動元は `:522-533` の 15 秒タイマーと `:505` の停止直前同期 | server 側 `playback_position_seconds` |
| server reader | **なし** | `model/PodcastResponse.kt:31` が `playbackPositionSeconds` を decode するが、本 review の android module 全体 grep（`--include='*.kt'`）で他の出現は 0 件 |
| local reader | `podcast/PodcastViewModel.kt:446`、`:453`（skip 計算）、`:538`（server 送信）／ player UI | 表示・skip |

**authority: write-only（R3・R9 違反）**。仕様 `docs/design/shared-playback-spec.md:298-303`（§6.2）は `resolveResumePosition()` による server-wins を「現在の実装」と記述するが、android には resolve 関数も seek 呼出も存在しない（`podcast/PodcastViewModel.kt:324-331` は `prepare → play` のみ）。結果として position の authority は「server が持つが誰も読まない」状態で、fact としては authority 不在に等しい。

副次: `podcast/PodcastViewModel.kt:460-462`（`seekTo`）は clamp しないのに `:445-457`（skip）は clamp する。同一 fact への 2 つの書き込み経路が異なる値域契約を持つ。

### (d) 再生状態（playing / paused / ended / error）

| 役割 | 所在 | 内容 |
|---|---|---|
| writer | `podcast/ExoPlayerController.kt:104-112`（`onIsPlayingChanged`）、`:87-91`（`STATE_ENDED` で false）、`:93-98`（`STATE_IDLE` で false） | `_isPlaying: Boolean` |
| writer（別 fact に分離） | `podcast/PodcastViewModel.kt:135`、`:212`、`:214`、`:251`、`:309`、`:313`、`:367` | `_errorMessage: String?` |
| reader | `podcast/PodcastViewModel.kt:63`（委譲）→ player UI、`:437`（`togglePlayPause` の分岐） | |
| reader（UI 独自解釈） | `podcast/PodcastScreen.kt:160` `isPlaying = currentPodcast?.id == podcast.id` | **`isPlaying` を読まず「選択中」を「再生中」と表示** |

**authority: unique だが表現力不足（R2 違反）**。`podcast/PlayerController.kt:26` は `isPlaying: StateFlow<Boolean>` のみを公開契約とし、4 状態を 1 bit に射影している。帰結:

1. **error が観測不能**: `podcast/ExoPlayerController.kt:73-114` の `Player.Listener` は `onPlaybackStateChanged` と `onIsPlayingChanged` のみを override し、`onPlayerError` を持たない。decode 失敗・URL 失効は `isPlaying=false` になるだけで、pause と区別できない。
2. **ended が観測不能**: `:87-91` は `_isPlaying=false` にして callback を撃つが、状態としては残らない。
3. **UI が別の意味に読み替える**: `podcast/PodcastScreen.kt:160` は行 UI の「再生中」を id 一致で判定するため、一時停止中も「再生中」と表示する。error/pause/ended のいずれでも同じ描画になる。

つまり「不正状態を公開経路から構築できない」（R2）ではなく、**正常状態と異常状態を区別する語彙自体が公開契約に無い**。

### (e) 認証状態とトークン

| fact | writer | reader | authority |
|---|---|---|---|
| 認証状態 | `auth/AuthViewModel.kt:102`、`:107`、`:113`、`:149`、`:181`、`:197`、`:211` の `_authState.value` 代入（7 箇所すべて AuthViewModel 内） | `di/AppContainer.kt:409`（admin 判定）、`account/AccountViewModel` 経由（`di/AppContainer.kt:435`）、AppScaffold | **unique**（型は `auth/AuthState.kt:13-22` の sealed class で `Authenticated ⟹ user 必須` を型保証） |
| トークン | `di/AppContainer.kt:69` が構築する `KeystoreSessionStore`。`save` は `auth/AuthViewModel.kt:147`、`:195`。`clear` は `:112`、`:180` | `di/AppContainer.kt:72` の `tokenProvider` → `AuthInterceptor` | **unique**（store が正本） |

**authority: unique。ただし状態遷移規則が R5 に違反**。

- `auth/AuthViewModel.kt:111-114` は `catch (e: ApiException)` の catch-all で `sessionStore.clear()` + `Unauthenticated`。`network/ApiException.kt:8-20` の 4 種すべて（`NetworkError` を含む）が同じ扱いになる。コメント `:96-98` は「iOS 準拠の catch-all」と意図を明示しているが、QS8 を満たさない。
- セッション中の 401 を認証状態へ反映する経路が無い。`network/AuthInterceptor.kt:26` の `intercept` は response を検査せずヘッダ付与のみで（本 review の grep で同ファイルに `401` の出現 0 件）、`network/ApiException.kt:13` は `HttpError(code)` の数値としてのみ 401 を表現する。各 ViewModel が個別に `e.code == 401` を見るしかない。

### (f) ユーザー固有キャッシュ（音声・FCM・preferences）

| fact | 所有 | 消去 writer | logout 経路 | 失効経路 | spec §6.3 要求 |
|---|---|---|---|---|---|
| 音声 cache | `di/AppContainer.kt:107-110`（`AudioCacheManager`、`cacheDir`） | `podcast/PodcastViewModel.kt:232-238`（`cancelDownloadsAndClearCache`） | **消える**（`di/AppContainer.kt:268`） | **残る** | 削除対象（`docs/design/shared-playback-spec.md:305-316`） |
| FCM token | `di/AppContainer.kt:149-158`（`FcmTokenRegistrar`） | `fcmTokenRegistrar.onLogout()` | **消える**（`di/AppContainer.kt:275`） | **残る** | 表に明記なし（「ユーザー固有キャッシュ」の解釈に依存） |
| preferences（8 key） | `preferences/DataStorePreferencesStore.kt:117-128`（`filesDir` の `user_preferences.preferences_pb`、`di/AppContainer.kt:211-217`） | **消去 writer が存在しない**（`clear`/`removeAll` 相当の method が `PreferencesStore` に無い） | **残る** | 表に明記なし |
| 再生 queue / currentPodcast / player | `podcast/PodcastViewModel.kt:83-86`、`:115-121`、`di/AppContainer.kt:321-325`（player は Application スコープ singleton） | 消去経路なし（`cancelDownloadsAndClearCache` は download 状態のみ） | **残る**（次ユーザーに前ユーザーの queue が見える） | 表に明記なし |

**authority: 消去責務が split かつ不完全（R6 違反・QL4 制約への抵触）**。`onLogoutCleanup` は `auth/AuthViewModel.kt:58` の単一 hook で、`di/AppContainer.kt:266-281` が 2 操作を束ねる合成ラムダになっている。この設計自体（auth → podcast の逆依存を避ける関数注入）は健全だが、**hook の呼出点が `logout()` のみ**（`auth/AuthViewModel.kt:174`）であり、`refreshAuth()` の失効経路（`:112`）には無い。同じ「主体が離れる遷移」が 2 つの異なる事後条件を持つ。

### 3.1 authority 判定まとめ

| fact | authority | 違反 R | finding |
|---|---|---|---|
| (a) 現在再生中の Podcast | **split** | R3, R2 | F2 |
| (b) 再生速度 | 各概念 unique / 関係未定義・既定が死んでいる | R3 | F3（+ 選択肢二重定義） |
| (c) 再生位置 | write-only（実質 authority 不在） | R3, R9 | F1 |
| (d) 再生状態 | unique だが語彙不足 | R2 | F7 |
| (e) 認証状態とトークン | unique / 遷移規則が違反 | R5 | F4 |
| (f) ユーザー固有キャッシュ | 消去責務 split・不完全 | R6 | F4（同一原因）、F8 |

---

## 4. current architecture finding（F*）

severity は提案（`proposal`）であり、確定は user が行う。各 finding は 症状 → 損なう品質 → owner → 構造原因 の順で書く。

### F1 — 再生位置が write-only で、resume 規則が存在しない

- **症状**: 15 秒ごと（`podcast/PodcastViewModel.kt:522-533`）と停止直前（`:505`）に server へ位置を PATCH するが、再生開始（`:324-331`）で位置を読み戻さない。`model/PodcastResponse.kt:31` の `playbackPositionSeconds` は android module 全体で reader 0 件。
- **損なう品質**: QL3（V1 の中核が成立しない）。R3・R9 違反（`docs/design/shared-playback-spec.md:298-303`）。
- **構造原因**: `beginPlayback` が「再生元 URL の決定」と「再生セッションの初期条件（位置・速度）の決定」を分離しておらず、後者の責務を誰も持っていない。`resolvePlaybackSource`（`podcast/PodcastViewModel.kt:293`）に相当する純粋関数が位置側に存在しない。
- **severity proposal**: high。ユーザーに見える機能欠損であり、既に通信コストを払って書いたデータを捨てている。
- **owner**: user（規則の採用可否は SG3 で決める）。

### F2 — 「現在再生中」の authority が split

- **症状**: §3(a) のとおり `_currentPodcast`（`podcast/PodcastViewModel.kt:83-86`）と `_queue.currentIndex`（`:115-121`）が独立に同じ fact を持ち、2 経路で乖離する。
- **損なう品質**: QL1（`podcast/PodcastViewModel.kt:341-346` が示すとおり、新しい遷移を追加するたびに整合手続きを追記する必要がある）、QL3（QS6 未達）。R3・R2 違反。
- **構造原因**: `PlaybackQueue`（`core/PlaybackQueue.kt:23-29`）は仕様上 `current` を持つ純粋モデルとして完成しているのに、ViewModel が「UI に見せる現在」を別 StateFlow で二重化した。`core/PlaybackQueue.kt:84-91` の `advance()` は「末尾で停止」を `currentIndex` 維持 + `null` 返却で表すため、ViewModel 側が `keepCurrentPodcast` という補正 flag を持たざるを得なくなっている（`podcast/PodcastViewModel.kt:502-512`）。
- **severity proposal**: high。UI の「再生中」表示の正しさに直結し、補正漏れが既に 2 経路ある。
- **owner**: user（どちらを正本にするかは SG1）。

### F3 — 再生速度の 2 概念が接続されておらず、選択肢が二重定義

- **症状 1（接続欠落）**: `preferences/DataStorePreferencesStore.kt:46-49` の既定速度が実再生に適用されない。`setSpeed` の production 呼出元は `podcast/AudioPlayerSection.kt:285` の 1 件のみ（module 全体 grep）。
- **症状 2（二重定義）**: `podcast/PlaybackConstants.kt:12` が 8 段（0.5〜2.5、`List<Float>`）、`settings/SettingsScreen.kt:1361` が 5 段（0.75〜2.0、`List<Double>`、private）。型も段数も異なる。`podcast/PlaybackConstants.kt:3-9` の doc は「片方だけ変更して食い違う回帰を防ぐ」ことを定数化の目的として明記しており、その目的が settings 側で破られている。
- **症状 3（値域検証なし）**: `preferences/DataStorePreferencesStore.kt:85-87` は任意の `Double` を保存する。server 由来値（`auth/AuthViewModel.kt:125`）も検証されない。`settings/SettingsViewModel.kt:193-197` は `weeklyGoalEpisodes` だけ検証するが、速度は検証しない。
- **損なう品質**: QL1（QS2 未達）、QL3（`0.0` や負値が保存されると `settings/SettingsScreen.kt:182-183` が index -1 → fallback に落ちる）。R3 違反。
- **構造原因**: 「速度」という語が「既定値（永続設定）」と「セッション値（再生器の状態）」の 2 概念を指すのに、型でも命名でも区別されていない。値域が domain 型ではなく UI 定数として表現されている。
- **severity proposal**: medium。既定速度が効かないのは機能欠損だが、手動選択で回避可能。
- **owner**: user（適用方針は SG2）。

### F4 — セッション失効と一時障害が同一経路で、cleanup 事後条件が logout にしかない

- **症状**: `auth/AuthViewModel.kt:111-114` が `ApiException` 全種で `sessionStore.clear()` + `Unauthenticated`。`onLogoutCleanup`（`:58`）の呼出は `:174`（`logout()` 内）のみ。セッション中 401 の反映経路は存在しない（`network/AuthInterceptor.kt` に `401` の出現 0 件、`intercept` は `:26` でヘッダ付与のみ）。
- **損なう品質**: QL4（QS7 未達: 失効後に音声 cache・FCM token・preferences・queue が残る）かつ QL3（QS8 未達: 通信断でログアウトされる）。R5・R6 違反。
- **構造原因**: 「失効（トークンが無効）」と「到達不能（判定不能）」が `network/ApiException.kt:8-20` で型分離されておらず、`refreshAuth` が判定を持てない。加えて「主体が離れる」という domain event が存在せず、`logout()` という 1 操作に cleanup が bind されている。
- **severity proposal**: high（QL4 は constraint = 下回ってはいけない床）。
- **owner**: user（cleanup 範囲は SG4、失効判定は SG3 と独立に SG5）。

### F5 — 失敗の意味が transport 値のまま consumer へ漏れている

- **症状**: `network/ApiException.kt:8-20` は `RateLimited | HttpError(code, bodyMessage) | DecodingError | NetworkError` の 4 種で、`unauthorized` / `not_found` / `conflict` / `server` の意味型が無い。結果として status 数値比較が層をまたいで分散する（共通ブリーフ §4/§5 の観測を本 review で再取得・確認した箇所: `auth/AuthViewModel.kt:152`（401）、`settings/SettingsViewModel.kt:160`（404）、`network/ApiClient.kt:66-67` は別論点）。さらに `_errorMessage.value = e.message` により transport 文言（`network/ApiException.kt:13` の `"HTTP Error $code"`、`:19` の `"Network error: ..."`）がそのまま UI に出る経路が `podcast/PodcastViewModel.kt:135`、`:212`、`:214`、`:309` にある。
- **損なう品質**: QL1（QS1 未達）、QL4（内部詳細の UI 露出。`agent-rules/12-security-guidelines.md` の「errors free of internal detail」に抵触する可能性）。R4 違反。
- **件数に関する明示**: `podcast/PodcastViewModel.kt` の `_errorMessage.value` 代入は本 review の再確認で **12 箇所**（`:129`、`:135`、`:160`、`:212`、`:214`、`:251`、`:280`、`:309`、`:313`、`:326`、`:367`、`:477`）。うち `:129`・`:326`・`:477` は `null` 代入（クリア）、`:160`・`:280`・`:313`・`:367` は定数文言、`e.message` 直代入は `:135`・`:212`・`:214`・`:251`・`:309` の **5 箇所**（`:251` は `AudioCacheException`）。
- **severity proposal**: medium。
- **owner**: user。ただし failure 型の設計は contract Function の所掌のため OB-A2 で引き渡す。

### F6 — test double が production 契約の複製になっている

- **症状**: `network/ApiClient.kt:36` の interface（`suspend fun` 44 件）に対し、`FakeApiClient` 系が **9 ファイル 1,393 行**（`app/src/test/.../settings/FakeApiClient.kt` 158 / `passkey/FakeApiClient.kt` 158 / `auth/FakeApiClient.kt` 146 / `notification/FakeNotificationApiClient.kt` 146 / `observability/FakeApiClient.kt` 142 / `feed/FakeFeedApiClient.kt` 156 / `podcast/FakePodcastApiClient.kt` 194 / `account/FakeApiClient.kt` 151 / `onboarding/FakeApiClient.kt` 142。`wc -l` 実測）。
- **緩和策とその副作用**: `network/ApiClient.kt` は interface 内に `error("...")` を投げる default 実装を **9 箇所**持つ（`grep -c 'error("'` = 9。例: `:66-67` の `markCompleted`）。これは Fake の override を減らすための意図的な設計だが、**production interface に「未実装なら実行時に落ちる」契約を埋め込む**ため、production 実装（`OkHttpApiClient`）が override を忘れても compile error にならない。testability のために QL3 を削っている trade-off であり、現状は WHY が `ApiClient.kt` に記録されていない。
- **損なう品質**: QL2（QS3 部分未達）、QL3（default が本番経路で発火し得る）。R7 違反。
- **severity proposal**: medium。
- **owner**: user（解消方針は SG6）。

### F7 — 再生状態の公開語彙が 1 bit で、error が観測不能

- **症状**: §3(d)。`podcast/PlayerController.kt:26` は `isPlaying: StateFlow<Boolean>` のみ。`podcast/ExoPlayerController.kt:73-114` の `Player.Listener` は `onPlaybackStateChanged` / `onIsPlayingChanged` のみを override し、`onPlayerError` が無い。
- **損なう品質**: QL3（QS5 未達）。R2 違反。
- **構造原因**: `PlayerController`（`podcast/PlayerController.kt:24-78`）は「操作」を正しく抽象化したが「状態」を Media3 の `Player.State` 相当の語彙で公開せず、ExoPlayer が持つ error 情報を境界で捨てている。境界が薄すぎるのではなく、**境界の出力側の型が貧弱**という形の漏れ。
- **severity proposal**: high。ユーザーは「無音で止まった」を pause と区別できず、queue も進まない。
- **owner**: user。状態機械の設計は model/contract Function の所掌（OB-A2）。

### F8 — `podcast` ⇄ `playbackservice` の相互参照と ExoPlayer の生 Player 公開

- **症状**: `podcast/ExoPlayerController.kt:143`（`Intent(context, PlaybackService::class.java)`）が `playbackservice` を参照し、`playbackservice/PlaybackService.kt:37-39` が `NewsListenApplication` → `AppContainer` → `ExoPlayerController` を参照する。さらに `podcast/ExoPlayerController.kt:202-203` が `val player: Player` を公開し、`playbackservice/PlaybackService.kt:51-53` の `MediaSession.Builder(this, player)` へ渡す。
- **損なう品質**: QL1（`playbackservice` が `PlayerController` interface ではなく具象 `ExoPlayerController` と service locator（`NewsListenApplication.getAppContainer()`）の両方に依存するため、player 実装の差し替えが service を巻き込む）、QL2（`PlaybackService` を unit test で構築する seam が無い）。
- **緩和の存在**: `podcast/ExoPlayerController.kt:194-201` と `playbackservice/PlaybackService.kt:17-28` の doc が所有権（ExoPlayer は AppContainer 所有、MediaSession は service 所有）を明記しており、規律としては文書化されている。`di/AppContainer.kt:325` が戻り型を `ExoPlayerController`（具象）にしているのも意図的。
- **反証**: `core/` は import 純粋（`android.*` / `network` への依存 0）であり、循環は `podcast`/`playbackservice` の 2 package に閉じている。domain 層への逆流は無い。
- **severity proposal**: low。Media3 の shared-player 方式では実質的に避けにくく、現状の文書化で risk は管理されている。R1–R9 のいずれにも直接違反しない。
- **owner**: user。**この finding は「直す」提案ではなく「記録する」提案**（§8 の do-minimum に含める）。

### F9 — CI が単一ゲートで、品質観測が build 成否に縮退

- **症状**: `.github/workflows/ci.yml` の `android-test` job は `./gradlew build --stacktrace` の 1 ステップ（+ 別 job の gitleaks）。detekt / ktlint / JaCoCo の独立ゲートは無く、lint は build 内の `lintVital` のみ。`androidTest` ディレクトリ自体が存在しない。
- **損なう品質**: QL2。R8 違反（test・lint・build が独立ゲートになっていない）。
- **severity proposal**: low〜medium。`build` は test を含むため回帰検出自体は機能しており、欠けているのは「どのゲートが落ちたか」の識別性と coverage 可視化。
- **owner**: user。

### 4.1 dependency direction（現状）

```
[UI / Compose]  PodcastScreen, AudioPlayerSection, QueueSheet, SettingsScreen
      |                                    |
      |  (viewModel)                       |  (!) SettingsScreen.kt:130 は
      v                                    v      preferencesStore を直接購読
[ViewModel]  PodcastViewModel, AuthViewModel, SettingsViewModel, ...
      |            |              |
      v            v              v
[port]  PlayerController   ApiClient   PreferencesStore   SessionStore   AudioCacheManager
      |                       |              |
      v                       v              v
[infra] ExoPlayerController  OkHttpApiClient  DataStorePreferencesStore  KeystoreSessionStore
      |
      +--> playbackservice/PlaybackService  (ExoPlayerController.kt:143)
                    |
                    +--> NewsListenApplication -> AppContainer -> ExoPlayerController
                         (PlaybackService.kt:37-39)   ^^^ 逆流・service locator

[core]  PlaybackQueue, resolvePlaybackSource, Difficulty   <-- import 純粋（依存を持たない）
```

健全な点（維持すべき既存資産）:
- `core/` は外部依存ゼロの純粋モデルで、仕様正本を pin する conformance test を持つ（`PlaybackQueueConformanceTest` に Q-01〜Q-32、`RelativeTimeConformanceTest` に RT-01〜15＋RT-A01/A02）。
- ViewModel は `androidx.lifecycle.ViewModel` を継承せず Dispatcher をコンストラクタ注入する（`auth/AuthViewModel.kt:21-26`、`podcast/PodcastViewModel.kt:34-38` が WHY を明記）。これが QL2 の土台。
- 層をまたぐ依存を関数注入で切る規律が一貫している（`auth/AuthViewModel.kt:38-57` の `onLogoutCleanup`、`settings/SettingsViewModel.kt:35-44` の `isAdminProvider`）。

逸脱:
- `SettingsScreen.kt:130` が ViewModel を飛ばして `PreferencesStore` を直接読む（Screen → infra port）。
- `playbackservice` → `AppContainer` の service locator 逆流（F8）。

---

## 5. 技術的負債の 5 因子比較

score は付けない（根拠のない数値順位は Hard Gate 違反）。各因子は Evidence に基づく記述で比較し、最終順位は user が決める。

### D1 — 再生位置 resume の不在（F1）

| 因子 | 内容 |
|---|---|
| 発生源 | iOS 忠実写像を優先した結果、`beginPlayback`（`podcast/PodcastViewModel.kt:324-331`）に初期条件決定の責務を置かなかった。spec §6.2（`docs/design/shared-playback-spec.md:298-303`）は先に書かれていた |
| 影響範囲 | 再生開始の全経路（`play` / `playNow` / `handlePlaybackEnded` からの自動遷移）。`model/PodcastResponse.kt:31` の decode 済みデータが全て未使用 |
| 利子 | 15 秒ごとの PATCH（`:529`）が恒久的に無価値な通信を発生させ続ける。ユーザーは毎回手動シークする |
| 返済コスト | 小〜中。`resolveResumePosition(local, server)` 相当の純粋関数を `core/` に追加し、`beginPlayback` から `playerController.seekTo` を 1 回呼ぶ。unit test は既存 Fake（`podcast/FakePlayerController.kt`）で完結 |
| 放置 risk | 機能欠損が固定化。将来 local 位置保存を足すと merge 規則が未定義のまま 2 writer になる（F2 の再演） |

### D2 — 「現在再生中」の split authority（F2）

| 因子 | 内容 |
|---|---|
| 発生源 | queue 導入（issue #81 相当）時に既存の `_currentPodcast` を残したまま `_queue` を追加した。`podcast/PodcastViewModel.kt:341-346` が当時の review 指摘を手続き補正で閉じた記録 |
| 影響範囲 | 再生遷移の全経路 + UI 5 箇所（`podcast/PodcastScreen.kt:61`、`:160`、`:204`、`podcast/AudioPlayerSection.kt:65`、`podcast/QueueSheet.kt:58`、`:83`） |
| 利子 | 遷移を 1 つ追加するたびに「両方を整合させる」手続きの追記が必要。`keepCurrentPodcast` のような補正 parameter が増える |
| 返済コスト | 中。UI 5 箇所の read を `queue.current` へ寄せるか、逆に `currentPodcast` を derived にする。どちらも `podcast/PodcastViewModel.kt` の遷移 6 箇所の書き換えを伴う |
| 放置 risk | 「再生中なのに UI が空」「停止したのに再生中表示」が特定条件で再発。テストで pin されていない組合せが残る |

### D3 — 失効と一時障害の未分離 + cleanup 範囲の不足（F4）

| 因子 | 内容 |
|---|---|
| 発生源 | `network/ApiException.kt:8-20` が transport 分類（status / IO / decode）で設計され、domain 分類（失効 / 判定不能）を持たなかった。`auth/AuthViewModel.kt:96-98` が iOS の catch-all をそのまま写像した |
| 影響範囲 | 起動時認証（`:99-115`）、および失効時に消えるべき 4 種のデータ（音声 cache・FCM token・preferences・queue） |
| 利子 | QL4 が constraint（床）であるため、利子ではなく **常時の規約違反**として計上すべき。共有端末では毎回 risk が顕在化 |
| 返済コスト | 中。failure 型の追加（OB-A2）＋ `refreshAuth` の分岐＋ cleanup hook の呼出点追加。`PreferencesStore` への消去 method 追加は interface 変更を伴い 9 Fake（F6）へ波及する可能性 |
| 放置 risk | 共有端末での情報残留。逆方向として、地下鉄で起動した既存ユーザーが再ログインを強いられる |

### D4 — 再生状態語彙の不足（F7）

| 因子 | 内容 |
|---|---|
| 発生源 | `PlayerController` を iOS `AVPlayer` の操作集合から抽出した（`podcast/PlayerController.kt:13-14` が明記）ため、操作は移植されたが Media3 固有の error 通知が対応物を持たなかった |
| 影響範囲 | player UI 全体 + 自動次再生の継続性。`podcast/PodcastScreen.kt:160` の行 UI 表示 |
| 利子 | error を扱う機能（再試行ボタン・自動 fallback）を作るたびに、まず状態語彙の拡張から始める必要がある |
| 返済コスト | 中。`PlayerController` の公開契約変更 → `ExoPlayerController` と `FakePlayerController` の両方＋既存 player test の書き換え |
| 放置 risk | 無音停止がユーザーに説明不能なまま残る。`onPlayerError` が無いため、crash report にも痕跡が出ない（`observability/CrashReporter` は例外経路のみ） |

### D5 — Fake 重複と interface 内 throwing default（F6）

| 因子 | 内容 |
|---|---|
| 発生源 | `ApiClient` を 44 method の単一 interface に集約し、consumer 別の狭い port に分割しなかった。分割の代わりに interface 内 default（`error("...")` 9 箇所）で override 負担を下げた |
| 影響範囲 | test 側 9 ファイル 1,393 行。production 側は `OkHttpApiClient` の override 漏れが compile で検出されなくなる範囲 |
| 利子 | method 追加時に Fake の修正が必要か否かが default の有無に依存し、判断が都度発生する。production HTTP 経路を通る test は 53 / 534 件に留まる |
| 返済コスト | 中〜大。consumer 別 port への分割は 10 前後の ViewModel の constructor 変更を伴う。do-minimum（default の WHY を記録し、production 側に override 網羅の test を足す）なら小 |
| 放置 risk | throwing default が本番で発火する経路が将来生まれる。`network/ApiClient.kt:67` の `error("markCompleted is not stubbed for id=$id")` は message に id を含むため、露出時に内部情報が漏れる（QL4） |

**比較の観点（user へ）**: business criticality は D1・D3 が最上位（前者はユーザーに見える機能欠損、後者は QL4 constraint 違反）。expected change は D2 が最上位（再生遷移は今後も増える）。failure risk は D3・D4 が最上位。remediation cost が最小なのは D1。

priority owner record:

```yaml
priority_owner_record:
  - id: D1
    owner: user
    status: unresolved
    value: null
    resolution_or_reason: "AI が priority を確定しない（Hard Gate）。§5 の 5 因子記述を根拠に user が順位を決める。"
    evidence: "podcast/PodcastViewModel.kt:324-331 / docs/design/shared-playback-spec.md:298-303"
  - id: D2
    owner: user
    status: unresolved
    value: null
    resolution_or_reason: 同上
    evidence: "podcast/PodcastViewModel.kt:83-86, :115-121, :341-346, :305-310, :368"
  - id: D3
    owner: user
    status: unresolved
    value: null
    resolution_or_reason: 同上
    evidence: "auth/AuthViewModel.kt:111-114, :174 / di/AppContainer.kt:266-281"
  - id: D4
    owner: user
    status: unresolved
    value: null
    resolution_or_reason: 同上
    evidence: "podcast/PlayerController.kt:26 / podcast/ExoPlayerController.kt:73-114"
  - id: D5
    owner: user
    status: unresolved
    value: null
    resolution_or_reason: 同上
    evidence: "network/ApiClient.kt:36, :66-67 / 9 Fake ファイル計 1,393 行（wc -l 実測）"
```

---

## 6. Selection Gate（SG*）と option 比較

すべて `pending`。**web module の決定は default 候補として併記するが、選択済みとして扱わない**（platform ごとに UI 導線と SDK 制約が異なり、web の決定がそのまま android の最適解である保証を本 review は持たない）。

### SG1 — 「現在再生中の Podcast」の正本をどちらにするか

```yaml
selection_gate:
  id: SG1
  question: "(a) の authority を _queue.current 側に一本化するか、_currentPodcast 側に一本化するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D2, F2, QS6]
  default_candidate_note: >-
    web は「Queue.current を正本」と決定済み（共通ブリーフ §1 の参照情報）。android でも O1-A が同方向だが、
    android 固有の制約（下記 O1-A の cost 欄）があるため選択済みにしない。
  evidence_needed:
    - "『署名 URL 再取得後の最新 Podcast をどこに置くか』の user 意図。現在 _currentPodcast は fetchPodcast の結果（podcast/PodcastViewModel.kt:307 の fresh）を保持し、queue の要素は一覧取得時の古い DTO のまま。この差分を UI がどちらで見るべきかが未確定。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O1-A**（web と同方向） | `queue.current` を正本にし、`currentPodcast` を `queue` からの derived（`StateFlow` の `map`）にする | QL1 +（writer が queue 操作に集約）、QL3 +（乖離が構造的に不可能）、QL2 +（`core/PlaybackQueue.kt` の既存 conformance test が authority を pin） | 高（derived を再び独立 StateFlow に戻せる） | 中。`advance()` が `null` を返しても `currentIndex` を末尾に残す（`core/PlaybackQueue.kt:84-91`）ため「停止」を表す状態が queue に無い。停止表現の追加（`currentIndex=null` への遷移 or 別 flag）が必要で、これは **仕様 §2.1（`docs/design/shared-playback-spec.md:39-56`）の不変条件に触る**＝3 platform 合意が必要 |
| **O1-B** | `_currentPodcast` を正本にし、queue は「順序の入れ物」に格下げ（`currentIndex` を廃止し `currentId` を持たない） | QL1 −（仕様正本 `docs/design/shared-playback-spec.md:39-56` から乖離し、Q-01〜Q-32 の conformance test が無効化される） | 低（仕様側の改訂を伴うため戻しにくい） | 大 |
| **O1-C**（do-minimum） | 二重化を維持し、乖離する 2 経路（`podcast/PodcastViewModel.kt:305-310` の NETWORK 失敗、`:368` の末尾停止）だけを test で pin して補正する | QL3 +（既知 2 経路は塞がる）、QL1 ±（構造原因は残り、新経路で再発） | 高 | 小 |

### SG2 — 既定再生速度の適用方針

```yaml
selection_gate:
  id: SG2
  question: "preferences の既定速度を、いつ・どの粒度で実再生へ適用するか。またセッション速度はエピソード切替で維持するか reset するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D_none_direct, F3]
  default_candidate_note: "web は「速度は 2 概念（既定 / セッション）」と決定済み。android も概念分離自体は同方向だが、適用 timing（下表）は web の決定に含まれないため候補のまま。"
  evidence_needed:
    - "ユーザーが『このエピソードだけ 1.5 倍』を期待するか『以後ずっと 1.5 倍』を期待するか。iOS 実装の実挙動（正本とされる AVPlayer 側）が本 review では未確認（OB-A3）。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O2-A** | `beginPlayback`（`podcast/PodcastViewModel.kt:324-331`）で毎回 `setSpeed(defaultPlaybackSpeed)` を適用。セッション速度はエピソード切替で reset | QL1 +（適用点 1 箇所）、QL3 +（起点が常に既定値で予測可能） | 高 | 小。ただし `PodcastViewModel` が `PreferencesStore` への依存を新たに得る（現状は持たない。`di/AppContainer.kt:339-348` の constructor 変更） |
| **O2-B** | 初回再生時のみ既定を適用し、以後はセッション速度を維持（現状の暗黙挙動に「初期化」だけ足す） | QL3 ±（「なぜ今この速度か」がユーザーに説明不能な場合が残る） | 高 | 小 |
| **O2-C** | UI での速度変更を既定速度への書き込みにもする（2 概念を 1 概念に統合） | QL1 +（概念が 1 つ）、QL4 ±（server 同期が増える）、V1 −（「このエピソードだけ速く」ができない） | 中（分離し直すには型追加） | 中。`settings/SettingsViewModel.kt:187-191` の同期経路を player 側からも呼ぶ配線が必要 |

選択肢に依らず必要な前提整備（SG2 とは独立に user 判断が要る小項目）: `podcast/PlaybackConstants.kt:12`（8 段 `Float`）と `settings/SettingsScreen.kt:1361`（5 段 `Double`）のどちらを正とするか。段数が異なるため、統合すると settings の選択肢が増減してユーザーに見える変更になる。

### SG3 — resume 規則（§6.2 server-wins）を android で採用するか

```yaml
selection_gate:
  id: SG3
  question: "docs/design/shared-playback-spec.md:298-303 の server-wins resume を android に実装するか、しないと決めて spec 側へ android 非対応を明記するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D1, F1, QS4]
  default_candidate_note: "web は server-wins を実装済みとされる。spec も §6.2 で server-wins を『現在の実装』と書いている。ただし android 未実装という事実により、spec の記述と実装の乖離をどちらに合わせるかが未決。"
  evidence_needed:
    - "『最後まで聴いた（completed）』エピソードを再度開いたとき、末尾から再開すべきか先頭からか。server 値が duration 相当のとき seek すると即 STATE_ENDED になり handlePlaybackEnded が発火して次へ飛ぶ（podcast/ExoPlayerController.kt:87-91 → podcast/PodcastViewModel.kt:348-370）。この境界規則が spec に無い。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O3-A** | `core/` に純粋関数 `resolveResumePosition(serverSeconds, duration)` を追加し、`beginPlayback` で `seekTo` を 1 回呼ぶ。完聴境界は「duration の 95% 超なら 0 に戻す」等の閾値規則を spec へ追記 | QL3 +、QL1 +（`resolvePlaybackSource` と同じ pattern で対称になる）、QL2 +（純粋関数なので conformance test 化できる） | 高 | 小。閾値規則の spec 追記は 3 platform 合意が必要（OB-A4） |
| **O3-B** | server 値をそのまま無条件に `seekTo` する（閾値なし） | QL3 −（完聴済みエピソードが即スキップされる回帰を作る） | 高 | 最小 |
| **O3-C**（do-minimum） | 実装せず、position の PATCH（`podcast/PodcastViewModel.kt:536-542`）を削除して「android は位置同期しない」と spec に明記 | QL1 +（死んだ code path が消える）、V1 −（A1 の purpose を明示的に諦める）、performance +（無価値な通信が消える） | 中（再実装は O3-A 相当） | 小 |

### SG4 — セッション失効時の cleanup 範囲

```yaml
selection_gate:
  id: SG4
  question: "『主体が離れる』遷移（logout と失効の両方）の事後条件として、どこまでのユーザー固有データを消すか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D3, F4, QS7]
  default_candidate_note: >-
    web は「失効時キャッシュ消去」を決定済み。spec §6.3（docs/design/shared-playback-spec.md:305-316）の表は
    web/iOS の削除対象しか列挙しておらず、preferences と再生状態は対象に含まれていない。android の範囲は未決。
  evidence_needed:
    - "preferences（preferences/DataStorePreferencesStore.kt:117-128 の 8 key）は『ユーザー固有データ』か『端末設定』か。sfx_enabled / haptics_enabled / time_format / article_open_mode は端末寄り、default_difficulty / default_playback_speed / weekly_goal_episodes / seen_achievement_ids は user 寄りで、8 key を一律に扱えない。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O4-A** | 現状の cleanup 対象（音声 cache + FCM token）を失効経路にも適用する。範囲は増やさない | QL4 +（logout と失効の事後条件が揃う）、QL1 +（hook 呼出点が 2 つになるだけ） | 高 | 小。`auth/AuthViewModel.kt:112` 付近で `onLogoutCleanup()` を呼ぶ |
| **O4-B** | O4-A に加え、user 寄り preferences 4 key と再生状態（queue / currentPodcast / `playerController.stop()`）も消す | QL4 ++、QL1 −（`PreferencesStore` に key 単位の消去契約を追加 → 9 Fake（F6）へ波及）、QL3 ±（消去中の失敗時の部分消去状態が新たな考慮点になる） | 中 | 中〜大 |
| **O4-C**（do-minimum） | 範囲は変えず、「失効時に cleanup しない」ことを ADR に明記して既知 risk として受容する | QL4 −（constraint を意図的に下回る宣言。QL4 が constraint である以上、この option は user の明示承認なしに採れない） | 高 | 最小 |

### SG5 — 失効（unauthorized）と到達不能（network）の分離方針

```yaml
selection_gate:
  id: SG5
  question: "refreshAuth が『トークン失効』と『一時的に判定不能』を区別するために、失敗型をどう変えるか。セッション中の 401 をどこで認証状態へ反映するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D3, F4, F5, QS8]
  default_candidate_note: >-
    web は「失敗時停止＋手動再試行」を決定済み。android の AuthState には『判定保留』を表す状態が
    既に存在する（auth/AuthState.kt:15 の Unknown）ため、web と異なる選択肢 O5-B が成立し得る。
  evidence_needed:
    - "起動時に通信不可だったとき、保護 UI を出すか、ログイン画面を出すか、Unknown のまま待たせるか。Unknown のままにすると UI が無限ローディングになり得る（現状 Unknown は起動直後のみの一過性状態）。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O5-A** | `ApiException` に `Unauthorized` を追加し、`refreshAuth` は `Unauthorized` のみ `clear()` + `Unauthenticated`、それ以外は状態を変えず（トークン保持）エラー表示 → 手動再試行 | QL3 +、QL4 +（失効のみで消す）、QL1 +（R4 に前進） | 高 | 中。`network/ApiException.kt` と `OkHttpApiClient` の分類、および `e.code == 401` を見ている既存箇所（`auth/AuthViewModel.kt:152` 等）の整理 |
| **O5-B** | O5-A に加え `AuthState` へ「オフライン猶予」状態を追加し、保存トークンで楽観的に Authenticated 相当の UI を出す | QL3 ++（offline first）、QL4 −（失効済みトークンで保護 UI が一時的に描画される）、QL1 −（状態が 4 つになり全 consumer の分岐が増える） | 中 | 大 |
| **O5-C**（do-minimum） | `refreshAuth` の catch を `NetworkError` だけ別扱いにする（型追加なし、既存 sealed subclass での分岐） | QL3 +、QL1 ±（意味が catch の並び順に埋まり R4 は未解決） | 高 | 最小 |

`network/AuthInterceptor` での 401 検出（interceptor が response を見て cleanup を起動する）は、どの option でも追加可能な独立論点。ただし interceptor から auth 状態を書くと依存方向が network → auth へ逆流するため、`di/AppContainer.kt:72` の `tokenProvider` と同じ「関数注入」pattern（`onUnauthorized: () -> Unit`）での実現を推奨候補として記録する。

### SG6 — 再生状態語彙（error の観測）をどう入れるか

```yaml
selection_gate:
  id: SG6
  question: "PlayerController の公開状態を isPlaying: Boolean から拡張するか、error を別 channel で出すか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D4, F7, QS5]
  default_candidate_note: "web 側に対応する決定は本 review の参照範囲に無い（unknown）。確認方法は OB-A3。"
  evidence_needed:
    - "error 発生時に自動で次へ進むか、停止して再試行を促すかの product 方針。web の『失敗時停止＋手動再試行』が player error にも適用されるかは未確認。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O6-A** | `PlayerController` に `playbackState: StateFlow<PlaybackState>`（`Idle / Buffering / Playing / Paused / Ended / Failed(reason)`）を追加し、`isPlaying` を derived として残す | QL3 +、QL1 +、QL2 +（`FakePlayerController` で全状態を注入できる） | 高（`isPlaying` を残すので consumer は段階移行可能） | 中。`ExoPlayerController` に `onPlayerError` override 追加＋`FakePlayerController` 拡張＋既存 player test |
| **O6-B** | 状態は変えず、`onPlaybackFailed: ((reason) -> Unit)?` callback を `onPlaybackCompleted`（`podcast/PlayerController.kt:42`）と同型で追加 | QL3 +（error が届く）、QL1 ±（状態として観測できないため UI は自前で保持）、QL2 +（既存 pattern の踏襲でコスト最小） | 高 | 小 |
| **O6-C**（do-minimum） | 何もしない。error 未観測を既知 risk として ADR 記録 | QL3 −（QS5 が恒久未達） | 高 | 最小 |

### SG7 — Fake 重複と interface 内 throwing default の解消方針

```yaml
selection_gate:
  id: SG7
  question: "44 method の単一 ApiClient interface と 9 ファイル 1,393 行の Fake、および 9 箇所の throwing default を、どう整理するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [D5, F6, QS3]
  default_candidate_note: >-
    web の対応する決定は本 review の参照範囲に無い（unknown）。
    なお共通ブリーフ §1 は web review の Boundary RO2「汎用 Storage port」を棄却済みと記録しており、
    『汎用の広い port を作る』方向は再提案しない。下記 O7-A は consumer 別の狭い port であり別物。
  evidence_needed:
    - "各 ViewModel が実際に使う method 集合。本 review では列挙していない（確認方法: ViewModel ごとに apiClient. の呼出を grep）。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O7-A** | consumer 別の狭い port（`PodcastApi` / `AuthApi` / `SettingsApi` …）に分割し、`OkHttpApiClient` が全てを実装。Fake は port ごとに 1 つ | QL2 ++（Fake が数行になる）、QL1 +（consumer が知る面が縮む）、QL3 +（throwing default を全廃できる） | 中（戻すには再統合） | 大。10 前後の ViewModel の constructor と `di/AppContainer.kt` の配線、9 Fake の書き換え |
| **O7-B** | interface は維持し、共通の `FakeApiClient` 基底（全 method を `error("not stubbed")`）を **test 側に 1 つ**作り、各 package の Fake はそれを継承して必要分だけ override。production interface の default は全削除 | QL2 +（重複が基底 1 つに集約）、QL3 +（production interface から throwing default が消え、override 漏れが compile error になる） | 高 | 中。9 Fake の書き換えのみで production 側は default 削除だけ |
| **O7-C**（do-minimum） | 現状維持。`network/ApiClient.kt` の throwing default に WHY コメントを追加し、`:67` の message から id（`"...for id=$id"`）を除去して内部情報露出を止める | QL4 +（露出を止める）、QL1 ±、QL2 ±（重複は残る） | 高 | 最小 |

### SG8 — CI ゲートの分割

```yaml
selection_gate:
  id: SG8
  question: "R8（test・lint・build を独立ゲート）を満たすため、CI をどこまで分割・追加するか。"
  status: pending
  owner: user
  approvers: [user]
  blocks: [F9]
  evidence_needed:
    - "CI 実行時間の許容上限。現状 ./gradlew build 1 本の所要時間が本 review では未計測（OB-A5）。"
```

| option | 内容 | QL への影響 | 可逆性 | 移行コスト |
|---|---|---|---|---|
| **O8-A** | `testDebugUnitTest` / `lintVitalRelease` / `assembleDebug` を独立 step に分け、失敗箇所を識別可能にする | QL2 +（識別性） | 高 | 小（`.github/workflows/ci.yml` のみ） |
| **O8-B** | O8-A に detekt / ktlint / JaCoCo を追加 | QL2 ++、QL1 +（規約の機械化） | 高 | 中（新規 tool 導入と既存 code の初回違反対応） |
| **O8-C**（do-minimum） | 現状維持（`./gradlew build` が test を含むため回帰は検出される）を ADR に明記 | QL2 ± | 高 | 最小 |

---

## 7. conditional target design

SG1–SG8 がすべて `pending` であるため、**target architecture は選択済みとして表現しない**。以下は「どの SG がどう閉じても成立する構造制約」だけを列挙した conditional design である。

```yaml
conditional_target:
  status: conditional
  gated_by: [SG1, SG2, SG3, SG4, SG5, SG6, SG7, SG8]
  invariants_independent_of_gates:
    - id: TI1
      statement: "core/ は android.* / network / podcast に依存しない純粋層のままとする。"
      current_status: satisfied
      evidence: "core/PlaybackQueue.kt:1-13（import が無い）"
    - id: TI2
      statement: "再生の初期条件（位置・速度）を決める論理は、SG2/SG3 の結論に関わらず純粋関数として core/ に置き、beginPlayback は決定結果を適用するだけにする。"
      current_status: violated
      evidence: "podcast/PodcastViewModel.kt:324-331 が初期条件を決めていない（誰も決めていない）"
      why: "resolvePlaybackSource（:293）が既にこの pattern であり、対称性が QL1/QL2 の両方に効く。"
    - id: TI3
      statement: "『主体が離れる』事後条件は 1 箇所に定義し、logout と失効の両経路がそれを呼ぶ。範囲（SG4）が何であれ呼出点は 2 つとも通す。"
      current_status: violated
      evidence: "auth/AuthViewModel.kt:174 のみ（:112 には無い）"
    - id: TI4
      statement: "ViewModel は Dispatcher と全 collaborator をコンストラクタ注入で受け取り、androidx.lifecycle.ViewModel を継承しない。"
      current_status: satisfied
      evidence: "auth/AuthViewModel.kt:28-69、podcast/PodcastViewModel.kt:41-48、settings/SettingsViewModel.kt:26-49"
    - id: TI5
      statement: "Screen 層は業務判断と infra port の直接購読を行わない。"
      current_status: violated
      evidence: "settings/SettingsScreen.kt:130（PreferencesStore 直接購読）。および共通ブリーフ §4 が挙げる podcast/QuizSheet.kt:195 の 404 判断（本 review では QuizSheet を直接読んでいないため status は inherited=unverified）"
  authority_assignment: not_applicable
  authority_assignment_reason: >-
    (a)(c) の authority は SG1/SG3 の結論に依存し、(f) の消去責務は SG4 に依存する。
    選択済み target に一意な authority を割り当てることは、未決の human decision を AI が確定することになるため行わない。
```

---

## 8. transition（未選択のため phase は候補のみ）

ADR は未作成。**ADR 化そのものが最初の transition phase** である。

| phase | 内容 | deploy order | exit criteria | irreversible point | abort / rollback | 旧 path 削除 |
|---|---|---|---|---|---|---|
| P0（ADR） | SG1–SG8 を user が閉じ、決定を ADR として記録する。`docs/adr/066-android-internal-architecture.md`（`playbackservice/PlaybackService.kt:28` が参照）への追記か新規 ADR かも user 判断 | 最初 | 8 SG すべてが `pending` 以外になる | なし（文書のみ） | 文書 revert | 該当なし |
| P1（do-minimum 群） | F8 の相互参照を ADR に既知 risk として記録、`network/ApiClient.kt:67` の message から id を除去、SG8=O8-A の CI step 分割 | P0 後。相互に独立で並列可 | CI が 3 step に分かれ、`error()` message に id が含まれない | なし | 個別 revert 可 | 該当なし |
| P2（低コスト・高効果） | SG3=O3-A なら resume 実装、SG5=O5-A/C なら失効分離、TI3 に沿った cleanup 呼出点追加 | P0 後。P1 と並列可 | QS4/QS7/QS8 に対応する unit test が green | `PreferencesStore` に消去契約を追加した時点（9 Fake へ波及） | 追加前なら revail 可。追加後は Fake 側も戻す必要 | position PATCH を残すか削るかは SG3 の結論次第 |
| P3（authority 統合） | SG1 の結論に沿って (a) を一本化 | P2 後（P2 で状態遷移が増えるため） | QS6 の test が green、`keepCurrentPodcast` 補正 flag が不要になる | `docs/design/shared-playback-spec.md:39-56` の不変条件を改訂した時点（3 platform 合意事項） | spec 改訂前に abort 可 | `_currentPodcast`（O1-A の場合） |
| P4（境界整備） | SG6 の player 状態語彙、SG7 の Fake 整理 | P3 後 | QS5/QS3 が達成 | `PlayerController` の公開契約変更（O6-A の場合。`FakePlayerController` と全 player test に波及） | interface 変更を revert | `isPlaying` を derived に降格（O6-A の場合） |

```yaml
temporary_paths: []
temporary_paths_reason: >-
  本 review 時点の実装に、期限付き temporary path / feature flag / 二重 writer 移行機構は存在しない
  （grep 範囲: 対象 12 ファイル。旧新併存の分岐も観測されていない）。
  ただし P3 で (a) の 2 writer を段階移行する場合は、その期間が temporary path となり
  owner / introduced_at / metric / removal condition / removal phase の記録が必須になる（OB-A6）。
migration_of_multiple_writers:
  applies_to: "P3（(a) の authority 統合）"
  status: not_designed
  reason: "SG1 が pending のため、どちらを残すか決まっていない。deadline / reconciliation / conflict rule を AI が確定しない。"
  obligation: OB-A6
recovery:
  rollback: "P1/P2 は revert で戻る（データ移行なし）。P3/P4 は interface 変更を含むため revert と同時に test 側も戻す。"
  forward_recovery: "未設計。DataStore の schema 変更を伴う option（O4-B）を採る場合に必要になる。"
  status: unknown
  verification_method: "O4-B を選ぶ場合、DataStore key 削除後の起動を実機で確認する"
  impact_if_unresolved: "preferences 消去を実装したとき、旧 key 残留や読み取り失敗時の挙動が未定義になる"
```

---

## 9. platform validation

```yaml
platform_validation:
  required_platforms: [Android]
  rationale: "本 module は Android 専用（app/build.gradle.kts の単一 application module）。Windows / Linux / macOS の実行差は対象外。"
  windows_linux_macos: not_applicable
  not_applicable_reason: "対象は Android アプリ本体であり、OS 別の deployment / filesystem / process 差が target・transition・recovery を分岐させない。開発ホストの差（macOS 上の JBR で test 実行）は成果物の品質判断を分岐させない。"
  android_specific_scenarios:
    - id: PS1
      scenario: "Application プロセス death 後の復帰（PlaybackService が生きて Activity が死んだ場合）"
      status: unknown
      verification_method: "実機で『開発者オプション → バックグラウンドプロセス制限』下での再生継続を確認、または adb でプロセス kill"
      impact_if_unresolved: >-
        PlaybackService.kt:37-39 が NewsListenApplication.getAppContainer() を呼ぶため、
        プロセス再生成時に AppContainer が未初期化なら NPE / 例外の可能性がある。本 review は静的読解のみで
        NewsListenApplication の初期化順序を検証していない。
    - id: PS2
      scenario: "画面回転（configuration change）をまたいだ再生状態の保持"
      status: confirmed
      evidence: "di/AppContainer.kt:257-284, :321-325, :339-350 が by lazy の Application スコープ singleton として ViewModel と player を保持し、各 doc（:242-243, :315-316, :335-337）がこの意図を明記"
    - id: PS3
      scenario: "API 33 未満での通知権限（POST_NOTIFICATIONS 不在）"
      status: confirmed
      evidence: "di/AppContainer.kt:152-156 が SDK_INT < TIRAMISU を常時許可扱いにする permissionChecker を注入"
  parity_claim: not_applicable
  parity_claim_reason: "single platform のため cross-platform parity の主張対象が無い。web / iOS との仕様 parity は R9（Q-*/RT-* conformance test）が担うが、それは本 package の品質 scenario ではなく contract 側の所掌（OB-A2）。"
```

---

## 10. architecture trace

value → quality scenario → current finding → option → target → transition → validation。`covered / partial / missing / contradictory` を隠さない。

| value | QS | finding | SG / option | target | transition | validation | trace status |
|---|---|---|---|---|---|---|---|
| V1 | QS4 | F1 | SG3 / O3-A・O3-B・O3-C | 未選択（TI2 が制約） | P2 | 未実行（pin 対象が未実装） | **partial**（value と finding は接続、target 未決） |
| V1 | QS6 | F2 | SG1 / O1-A・O1-B・O1-C | 未選択 | P3 | 未実行 | **partial** |
| V1 | QS2 | F3 | SG2 / O2-A・O2-B・O2-C | 未選択 | P2 | 未実行 | **partial** |
| V1 | QS5 | F7 | SG6 / O6-A・O6-B・O6-C | 未選択 | P4 | 未実行 | **partial** |
| V2 | QS7 | F4 | SG4 / O4-A・O4-B・O4-C | 未選択 | P2 | 未実行 | **partial** |
| V2 | QS8 | F4 | SG5 / O5-A・O5-B・O5-C | 未選択 | P2 | 未実行 | **partial** |
| V3 | QS1 | F5 | （option 未作成） | 未選択 | 未割当 | 未実行 | **missing**（失敗型の再設計は contract Function の所掌。本 package では option を作らず OB-A2 で引き渡す） |
| V3 | QS3 | F6 | SG7 / O7-A・O7-B・O7-C | 未選択 | P4 | 未実行 | **partial** |
| V3 | （QS 未定義） | F9 | SG8 / O8-A・O8-B・O8-C | 未選択 | P1 | 未実行 | **partial**（F9 に対応する観測可能 scenario を本 package では定義していない） |
| （value 未接続） | — | F8 | 記録のみ（P1） | 現状維持 | P1 | 未実行 | **partial**（F8 は severity low で、どの value も直接損なっていないと判断した。この判断自体は user 確認対象） |

```yaml
validation_plan:
  status: ready            # design artifact としての readiness。engineering status は planned（未実装・未実行）
  executed: false
  passed: null
  items:
    - id: VP1
      verifies: QS4
      oracle: "beginPlayback 後に playerController.seekTo(server 位置) が 1 回呼ばれ、完聴済み（閾値超）では 0 が渡る"
      owner: "実装担当（tdd-strict-coder 推奨）"
      precondition: "SG3 が O3-A で閉じ、閾値規則が spec へ追記されていること"
      test_double: "FakePlayerController（app/src/test/java/com/rioikeda/newslisten/podcast/FakePlayerController.kt。既存）"
    - id: VP2
      verifies: QS6
      oracle: "play(next) の NETWORK 失敗後に queue.current と currentPodcast が一致する（両方 null か両方 next）"
      owner: 実装担当
      precondition: "SG1 が閉じていること"
      test_double: "FakePodcastApiClient（fetchPodcast で ApiException.NetworkError を投げる）"
    - id: VP3
      verifies: QS7, QS8
      oracle: "NetworkError では sessionStore.clear が呼ばれず authState が変わらない。Unauthorized（または 401）では clear と cleanup hook の両方が呼ばれる"
      owner: 実装担当
      precondition: "SG4・SG5 が閉じていること"
      test_double: "auth/FakeApiClient（既存）＋ cleanup 呼出を記録する spy ラムダ"
    - id: VP4
      verifies: QS5
      oracle: "ExoPlayer の onPlayerError 相当を注入したとき playbackState が Failed になり isPlaying=false と区別できる"
      owner: 実装担当
      precondition: "SG6 が O6-A/O6-B で閉じていること"
      test_double: "FakePlayerController の拡張"
    - id: VP5
      verifies: QS2
      oracle: "速度選択肢の定義が 1 箇所であり、settings UI と player UI が同じ list を参照する"
      owner: 実装担当
      precondition: "SG2 の付帯項目（8 段 / 5 段のどちらを正とするか）が決まっていること"
      test_double: 不要（静的参照の test）
  note: >-
    本 review は mutation_authorized=false のため、上記いずれも実行していない。
    ./gradlew testDebugUnitTest の実行結果は router が verification-run.md で管理する範囲であり、
    本 package は参照しない（未確認）。
```

---

## 11. verdict と decision（分離して記載）

### 11.1 subject_verdict（監査対象である android module の良否）

```yaml
subject_verdict: incomplete
subject_verdict_rationale: >-
  基盤（core の純粋性・コンストラクタ注入・関数注入による層分離・仕様 conformance test）は
  一貫した設計意図をもって整っており、responsibility の置き方は概ね健全である。
  一方で、6 fact のうち 4 fact（(a) 現在再生中・(b) 速度・(c) 位置・(f) キャッシュ消去）の
  authority が split・不在・不完全であり、R2/R3/R5/R6/R9 に対する違反が実コードで確認できる。
  これは「誤った architecture を選んだ」のではなく「fact の所有者を決める設計判断が
  未実施のまま機能が積まれた」状態であり、coherent とは言えないが indeterminate（判断材料不足）でもない。
counterevidence:
  - "実装コメント（podcast/PodcastViewModel.kt:341-346、:514-520、auth/AuthViewModel.kt:53-56）が
     不整合・仕様乖離を自ら記録しており、設計意図の欠落ではなく既知未処理として扱われている。
     この自認の存在は、対象の設計規律が機能している証拠として counterevidence に数える。"
  - "F8（podcast ⇄ playbackservice 循環）は Media3 の shared-player 方式では標準的な構成であり、
     所有権が文書化されている（playbackservice/PlaybackService.kt:17-28）。これを finding として
     挙げたが severity low とし、architecture の欠陥とは判定していない。"
residual_risk:
  - "本 review は静的読解のみで、実機・emulator の挙動は一切確認していない。PS1（プロセス death 後の
     PlaybackService 復帰）は静的に NPE の可能性が見えるが、未検証。"
  - "共通ブリーフ §4/§5 の観測のうち、本 review が直接再取得していない箇所（podcast/QuizSheet.kt、
     feed/FeedViewModel.kt、account/AccountViewModel.kt、engagement/ListeningStreakStore.kt、
     passkey/、onboarding/、learning/）については inherited 扱いで、本 package の path:line Evidence には含めていない。"
reevaluation_triggers:
  - "docs/design/shared-playback-spec.md §2.1 の不変条件が改訂されたとき（SG1 の option 評価が変わる）"
  - "PlayerController の公開契約が変更されたとき（(d) の authority 判定が変わる）"
  - "PreferencesStore に消去契約が追加されたとき（(f) の判定と F6 の波及範囲が変わる）"
```

### 11.2 package decision（canonical schema — 本成果物自体の readiness）

```yaml
decision:
  status: proposed
  artifact_readiness: ready
  engineering_status: planned
  release_status: not_released
  decision_maturity: proposed
  next_phase: "user が SG1–SG8 を裁定し P0（ADR 化）へ進む。裁定前の実装着手は推奨しない。"
  unknowns:
    - id: U1
      item: "iOS の実速度適用挙動（既定速度を再生開始時に適用するか）"
      verification_method: "ios/NewsListenApp/NewsListenApp/Podcast/PodcastViewModel.swift の AVPlayer.rate 設定箇所を読む"
      impact_if_unresolved: "SG2 の『正本との整合』が判定できず、O2-A/O2-B の選択根拠が弱い"
    - id: U2
      item: "web の player error 方針・Fake 整理方針（SG6/SG7 の default 候補）"
      verification_method: "web/docs/research-reports/2026-09-16-code-design-review.md の該当 SG を読む"
      impact_if_unresolved: "SG6/SG7 に default 候補を併記できず、user の比較材料が 1 つ少ない"
    - id: U3
      item: "PS1（プロセス death 後の PlaybackService 復帰）の実挙動"
      verification_method: "emulator で adb shell am kill 後に通知から再生操作"
      impact_if_unresolved: "F8 の severity low という判定が覆る可能性がある"
    - id: U4
      item: "CI の実行時間と、SG8 で追加できる gate の予算"
      verification_method: "GitHub Actions の直近 run の所要時間を確認"
      impact_if_unresolved: "SG8 の option 比較に cost 軸が入らない"
    - id: U5
      item: "preferences 8 key のうちどれが『ユーザー固有』か（SG4 の前提）"
      verification_method: "user への確認（product 判断であり code からは決まらない）"
      impact_if_unresolved: "SG4=O4-B の範囲が確定できない"
  unexecuted_validation:
    - "VP1〜VP5 のすべて（mutation_authorized=false のため未実装・未実行）"
    - "./gradlew testDebugUnitTest / ./gradlew build（本 review では実行していない）"
    - "実機・emulator での PS1 検証"
  platform_validation:
    required: [Android]
    verified: []
    unexecuted: [PS1]
    not_applicable: [Windows, Linux, macOS]
    not_applicable_reason: "§9 参照（Android 専用 module のため）"
```

---

## 12. obligation（他 Function・人間へ渡す事項）

| ID | 宛先 | 内容 | 未解決時の影響 |
|---|---|---|---|
| OB-A1 | user（裁定） | SG1–SG8 の裁定。とくに SG4（QL4 constraint に関わるため O4-C を採る場合は明示承認が必要）と SG1（spec §2.1 改訂を伴う可能性があるため 3 platform 合意が必要） | target authority が決まらず、P1 以降のどの実装も着手根拠を持たない |
| OB-A2 | contract / domain-model Function | (d) 再生状態の状態機械（`Idle/Buffering/Playing/Paused/Ended/Failed`）の定義、および失敗の意味型（`unauthorized / not_found / conflict / rate_limited(+retryAfter) / network / server`）の設計。本 package は F5・F7 の構造原因を特定したが、型設計と CI*/T* の作成は行っていない | R2・R4 に対応する契約と RED テストが作られず、V3 の trace が `missing` のまま残る |
| OB-A3 | router / 探索 | U1（iOS の速度適用挙動）と U2（web の SG6/SG7 決定）の確認。本 package は再 routing 禁止のため自力で取得していない | SG2・SG6・SG7 の default 候補が欠落し、user の比較材料が不足する |
| OB-A4 | user + docs 管理 | SG3=O3-A を採る場合、`docs/design/shared-playback-spec.md` §6.2 へ「完聴境界の閾値規則」を追記する必要がある（現状 §6.2 に規則が無く、server 値 = duration のとき即スキップになる）。§6.3 の表に android 行が無いことも同時に是正対象 | spec と実装の乖離が拡大し、R9 の conformance が「表に無い項目は検証されない」状態で固定化する |
| OB-A5 | user / CI 担当 | U4（CI 所要時間）の計測と、performance non-goal の裏付け観測（一時停止中の 15 秒 PATCH による通信量・電池影響） | SG8 の cost 比較ができず、performance を non-goal とした判断の Evidence が `unknown` のまま残る |
| OB-A6 | 実装担当 + user | P3 で (a) の 2 writer を段階移行する場合、temporary path として owner / introduced_at / purpose / metric・log / removal condition / removal phase を必ず記録すること。本 package は SG1 未決のため移行機構を設計していない | 二重 writer が期限・reconciliation・削除条件なしに残り、F2 と同型の負債を再生産する |
| OB-A7 | router → trial-log 転記 | §13 の棄却案を `android/docs/trial-log/` へ転記すること（`agent-rules/94-self-improvement-protocol.md` (t2)）。本 package は mutation_authorized=false のため自分で書かない | 検討した前提と棄却理由が失われ、同じ案が再提案される |
| OB-A8 | router | §14 の Evidence 整合性問題（共通ブリーフ §4 の path:line 3 件が対象ファイル行数を超過）を、他ロールの成果物にも同じ誤引用が混入していないか照合すること | 誤った行番号が複数成果物に伝播し、user が実コードを確認できなくなる |

---

## 13. 検討して採らなかった設計案（trial-log 転記用。OB-A7）

各案について 目的 / 前提 / やったこと / 結果 / 残課題 を各 1 文で書く。

**案 R1: `PlaybackQueue` を `currentId: String?` ベースに変更して (a) の split を解消する**
- 目的: `currentIndex` の位置ずれ再計算（`core/PlaybackQueue.kt:60-65` が既に補正している）を無くし、`_currentPodcast` との突き合わせを id 一致で自明にする。
- 前提: 未検証: `docs/design/shared-playback-spec.md:39-56` は `currentIndex` を 2 フィールド構成の一方として明示しており、id 化は 3 platform の型表現を変える。
- やったこと: `core/PlaybackQueue.kt` の全操作（`playNext` / `jump` / `advance` / `remove` / `moveUpNext`）が index 演算で書かれていることを確認し、id 化の影響範囲を見積もった。
- 結果: 棄却。仕様正本の状態モデルを変える案であり、共通ブリーフ §1 が棄却済みと記録する web spec-gate の「`QueueState` の branded type 化＝iOS/Android と共有する型表現の乖離」と同型の問題（platform 間の型表現乖離）を招くため、SG の option にも挙げなかった。
- 残課題: `currentIndex` 方式のまま `playNext` の index 再計算が正しいことは Q-* conformance test が担保しているはずだが、本 review では該当 Q-ID を特定していない。

**案 R2: `PlayerController` に `resumePositionProvider: suspend (String) -> Double?` を注入して resume を player 層で解決する**
- 目的: F1 の resume を `beginPlayback` の外に出し、`PodcastViewModel` を太らせずに済ませる。
- 前提: `PlayerController`（`podcast/PlayerController.kt:24-78`）は「音声再生を担う抽象境界」であり、`podcast/PlayerController.kt:5-6` の doc どおり操作の抽象に限定されている。
- やったこと: 注入した場合に `ExoPlayerController` と `FakePlayerController` の双方が server 通信の関心を持つことになる依存方向を確認した。
- 結果: 棄却。player 境界が「どこから再生すべきか」という業務判断を抱えることになり、TI2（初期条件は純粋関数で core に置く）と矛盾するため、SG3 の option からも外した。
- 残課題: なし（O3-A が同じ目的をより浅い依存で達成する）。

**案 R3: `AuthInterceptor` に 401 検出を持たせて失効を即時反映する（単独案として）**
- 目的: セッション中の 401 を 1 箇所で捕まえ、各 ViewModel の `e.code == 401` 分岐を不要にする。
- 前提: 未検証: `network/AuthInterceptor.kt` は本 review で `intercept` の存在（`:26`）と 401 の不在のみを確認し、全文は読んでいない。
- やったこと: interceptor から認証状態を書く場合の依存方向（network → auth の逆流）を確認し、`di/AppContainer.kt:72` の `tokenProvider` が同じ問題を関数注入で回避している先例を特定した。
- 結果: 単独案としては棄却し、SG5 の「どの option でも追加可能な独立論点」として格下げ記録した（関数注入 `onUnauthorized: () -> Unit` なら依存方向を保てるため、SG5 の結論と独立に採用可能）。
- 残課題: interceptor での cleanup 起動は OkHttp の thread 上で走るため、`AuthViewModel` の dispatcher 規律（`auth/AuthViewModel.kt:99` の `withContext(dispatcher)`）との整合が未検討。

**案 R4: `PreferencesStore` に汎用 `clear()` を追加して (f) の preferences 消去を実現する**
- 目的: SG4=O4-B の実装手段として最短。
- 前提: `preferences/DataStorePreferencesStore.kt:117-128` の 8 key は user 固有 4 件と端末寄り 4 件が混在する（§6 SG4 の evidence_needed 参照）。
- やったこと: 8 key の意味を個別に分類し、一律消去が端末設定（`sfx_enabled` 等）まで巻き込むことを確認した。
- 結果: 棄却。汎用 `clear()` は「何がユーザー固有か」という product 判断を消去 API から消してしまうため、SG4 の option には「key 単位の消去契約」として記述し、汎用 clear は挙げなかった。なお共通ブリーフ §1 が web の「汎用 Storage port」を棄却済みと記録しており、汎用化方向を再提案しない方針にも合致する。
- 残課題: key 単位消去にすると `PreferencesStore` の method が増え、9 Fake（F6）への波及量が SG7 の結論に依存する。

**案 R5: `PodcastViewModel`（13 StateFlow・6 責務）を機能別 ViewModel に分割する**
- 目的: QL1/QL2 の根本改善。
- 前提: 未検証: 共通ブリーフ §5 の「13 StateFlow・6 責務」は本 review で StateFlow 宣言の一部（`:63`〜`:121`）を確認したが、6 責務の内訳は再計数していない。
- やったこと: `di/AppContainer.kt:266-281` が `_podcastViewModel.cancelDownloadsAndClearCache()` を logout cleanup として参照しており、download 責務と auth 経路が既に結合していることを確認した。
- 結果: 本 package の option には含めなかった。分割は F1〜F7 のいずれの authority 問題も直接解決せず（split はファイル境界ではなく fact の所有の問題）、先に SG1–SG6 を閉じてから評価すべき順序だと判断した。
- 残課題: SG1・SG3・SG6 が閉じた後、分割の是非を再評価する価値はある。責務境界の設計は boundary Function の所掌（本 package では扱わない）。

**案 R6: `_errorMessage: StateFlow<String?>` を `StateFlow<UiFailure?>` に置換する**
- 目的: F5 の transport 文言漏れを型で止める。
- 前提: `podcast/PodcastViewModel.kt` の `_errorMessage.value` 代入は 12 箇所（§4 F5 で内訳を明示）。
- やったこと: 12 箇所の内訳を分類し、`e.message` 直代入が 5 箇所であることを特定した。
- 結果: 本 package では option 化せず OB-A2 で contract Function へ引き渡した。失敗の意味型（`unauthorized / not_found / …`）の定義が先に必要で、UI 型はその従属物だから。
- 残課題: なし（OB-A2 に移管済み）。

---

## 14. evidence_integrity（共通ブリーフの引用と本 review の差分）

本 review は「1 ファイル 1 コマンド」で全 Evidence を再取得した。その過程で、共通ブリーフ §4 の path:line 引用のうち **3 件が対象ファイルの総行数を超えている**ことを検出した。意味論（観測内容）は 3 件すべて正しく、行番号のみが誤っている。複数ファイルを連結した出力の行番号を引用した典型的な誤りと推定する（未検証: 原因の特定は行っていない）。

| 共通ブリーフの引用 | 対象ファイルの `wc -l` | 本 review が確認した正しい引用 | 観測内容の正否 |
|---|---|---|---|
| `auth/AuthState.kt:62-71` | 22 | `auth/AuthState.kt:13-22` | 正しい（sealed class 三状態） |
| `network/ApiException.kt:80-92` | 20 | `network/ApiException.kt:8-20` | 正しい（4 種の失敗型、unauthorized なし） |
| `podcast/PlayerController.kt:141` | 78 | `podcast/PlayerController.kt:26` | 正しい（`isPlaying: StateFlow<Boolean>` のみ） |

本 package はこの 3 件を修正した引用で記載している。他ロールの成果物に同じ誤引用が混入していないかの照合は OB-A8 で router へ引き渡す。

なお共通ブリーフ §4/§5 の以下の観測は本 review で直接再確認し、**そのまま正しい**ことを検証した: `auth/AuthViewModel.kt:111-114`・`:167-182`・`:152`、`di/AppContainer.kt:266-281`・`:409`・`:66`、`podcast/PodcastViewModel.kt:83-86`・`:115-121`・`:324-331`・`:522-533`・`:536-542`・`:460-462`・`:445-457`・`:341-346`・`:514-520`・`:348-370`・`:305-311`、`podcast/ExoPlayerController.kt:55-56`・`:71-115`・`:143`、`podcast/PlaybackConstants.kt:12`、`settings/SettingsScreen.kt:1361`・`:1362`、`settings/SettingsViewModel.kt:160`・`:237`、`preferences/DataStorePreferencesStore.kt:117-128`・`:81-87`、`model/PodcastResponse.kt:31`、`playbackservice/PlaybackService.kt:37-39`、`core/` の import 純粋性、Fake 9 ファイル 1,393 行、`.github/workflows/ci.yml` の単一 build step。

---

## 15. 引用行番号の機械照合結果

提出前に、本 package 内の全 `file:N` 形式引用を機械抽出し、各ファイルの `wc -l` と突き合わせた。

実行コマンド（要旨）: 本 package から `[A-Za-z0-9_/.-]+\.(kt|md|yml|kts):[0-9]+(-[0-9]+)?` を `grep -oE` で抽出・`sort -u` し、各引用について `app/src/main/java/com/rioikeda/newslisten/` → `android/` → `news-listen/` の順にパスを解決して `wc -l` と範囲上限を比較。

結果:

```
total=137  ok=131  over=3  unresolved=3
```

- **ok = 131**: 引用範囲の上限がファイル総行数以下。
- **over = 3**: `auth/AuthState.kt:62-71`（実 22 行）、`network/ApiException.kt:80-92`（実 20 行）、`podcast/PlayerController.kt:141`（実 78 行）。**これは §14 の表で「共通ブリーフ側の誤引用」として意図的に原文引用している 3 件であり、本 package 自身の主張の Evidence には一切使っていない**。同じ表の右列に本 review が確認した正しい引用（`:13-22` / `:8-20` / `:26`）を併記している。
- **unresolved = 3**: §4.1 の依存方向図の中で package prefix を省略して書いた `ExoPlayerController.kt:143`・`PlaybackService.kt:37-39`・`SettingsScreen.kt:130` の 3 件。個別に解決して照合した結果は下表のとおりで、いずれも範囲内。

| 引用 | 解決後パス | `wc -l` | 判定 |
|---|---|---|---|
| `ExoPlayerController.kt:143` | `podcast/ExoPlayerController.kt` | 237 | OK |
| `PlaybackService.kt:37-39` | `playbackservice/PlaybackService.kt` | 65 | OK |
| `SettingsScreen.kt:130` | `settings/SettingsScreen.kt` | 1362 | OK |

**結論: 本 package が自らの主張の根拠として用いた引用 134 件すべてが対象ファイルの行数以下であり、範囲外引用は 0 件である。**

秘密値（`local.properties` の API_KEY 等）は本 package に一切含まない（`di/AppContainer.kt:66` は `BuildConfig.API_KEY` を参照する行の所在のみを示し、値は引用していない）。

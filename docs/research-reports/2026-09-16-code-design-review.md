# news-listen-android コード設計レビュー（mino 設計 Skill 群・review mode）

日付: 2026-09-16 ／ 対象: `android/`（news-listen-android submodule、`main` @ 89e8350、tracked dirty 0）／ mode: review（read-only）
成果物種別: `reproducible_development_result.mode_artifact.kind = review_result`
決定の成熟度: **approved**（2026-09-16 user 承認。§8 の dig-me セッション Q1〜Q13。finding の採否・着手順はユーザーが所有）

> 読み方: §1〜§3 が Core（問題定義・前提・要件）、§4 が finding 一覧（優先品質順）、§5 が専門 Function package（lossless 付録）、§6 が追跡表と検証結果、§7 が canonical decision、§8 が人間判断。
> 外部知識源: `mcp__shelf__consult` は本セッションで接続失敗（timeout）のため未使用。設計原則の根拠は各 Skill の references・共有再生仕様・実コードのみ。
> 姉妹レビュー: web（`web/docs/research-reports/2026-09-16-code-design-review.md`）の R1〜R8 を踏襲し、android 固有に R9（共有仕様 conformance）を追加。term ledger は共有仕様の語に揃える。

---

## 0. Decision frame

```yaml
decision_frame:
  mode: review
  requested_outcome: Review Result（finding・Function package・traceability・subject verdict・canonical decision）
  decision_owner: user
  routing_origin: integrated
  mutation_authorized: false   # 本文書と付録の新規作成のみ。ソース・設定・git は不変
  in_scope: [app/src/main/java/com/rioikeda/newslisten/**, app/src/test/**, app/build.gradle.kts, .github/workflows/ci.yml, 共有再生仕様 §2/§3/§6 への conformance]
  out_of_scope: [backend 契約の妥当性, iOS/web の実装, UI 意匠（15-frontend-design）, 修正実装, Firebase/FCM 配信設定, release 署名]
  reversibility: reversible
  public_contract_change_allowed: false   # backend API・共有再生仕様 §2 は不変
  destructive_change_allowed: false
  host_platform: macos
  target_platforms: [android(minSdk 26 / targetSdk 35), jvm-unit-test]
  decision_maturity: {status: proposed, owner: user, scope: [android/], evidence_status: confirmed, approval_evidence: [], baseline_version: "", change_control: ""}
```

```yaml
method_provenance:
  source_derived_principles: [技術より先に actor/purpose/rule を確認, consumer が知る契約と内部技術の分離, code/test/scenario による検証]
  suite_operationalization: [canonical decision, Selection Gate, Requirement Catalog, 12 dimension screening, quality vocabulary]
  repository_policy: [agent-rules/11・12, docs/design/shared-playback-spec.md の Q-*/RT-*/§6 行 ID, android/CLAUDE.md（TDD 必須・トークンをログに出さない）]
```

## 1. Problem Frame と前提監査（Core）

### 1.1 Problem Frame

```yaml
problem_frame:
  actor: android モジュールを変更する開発者（人間・AI エージェント）
  context: Kotlin / Jetpack Compose。プレーンクラスの ViewModel（androidx.lifecycle 非継承）を AppContainer が by lazy で保持し、Media3 ExoPlayer・OkHttp・DataStore を interface（PlayerController / ApiClient / PreferencesStore / FileSystem）越しに注入する。core/ は純粋（import 0）
  desired_state: 再生・認証の状態の真実が一意で、失敗の意味が語彙として消費者に届き、設定値が再生に反映され、テストが production 経路を通る
  observed_barrier: 「現在再生中」が queue と currentPodcast の 2 系統、既定速度と server 保存位置が「書かれるが読まれない」、失敗が status 数値と例外 message のまま 8+8 箇所に散る、失効経路に cleanup が無い、ViewModel テストが 9 個の重複 Fake 越し
  impact: iOS 忠実写像の継ぎ目（キュー後付け・設定後付け）で乖離が手続き補正に依存し、共有仕様 §6.2（resume）に未準拠のまま green、`ApiClient` に 1 メソッド足すと Fake 9 ファイルが同時に壊れる
problem_readiness: ready
```

技術語を除いても問題が説明できる（「再生中の真実が 2 つ」「設定が効かない」「失敗の意味が届かない」「離脱時の後始末が片側だけ」「テストが本番経路を迂回」）。candidate_means は §5 Architecture の Selection Gate に退避し、本レビューでは確定しない。

### 1.2 前提台帳

| ID | 前提 | Evidence | 反証条件 | 誤り時の影響 | status |
|---|---|---|---|---|---|
| P1 | 共有再生仕様 §2/§3 の 49 行 ID は android 実装に全て存在し green | verification-run.md V3/V5（32 + 17 pass） | ID 欠落・red | R9 の Queue/RT 部分が finding 化 | confirmed |
| P2 | 再生位置は server へ 15 秒ごとに書かれるが、再生開始時に読まれない | `podcast/PodcastViewModel.kt:324-331`（seek なし）, `:522-542`, `model/PodcastResponse.kt:31` の唯一の参照 | `seekTo(playbackPositionSeconds)` 相当の経路 | R9/§6.2 finding 撤回 | confirmed |
| P3 | 既定再生速度（PreferencesStore）を PlayerController に適用する経路が無い | `setSpeed` 呼出は `AudioPlayerSection.kt:285` のみ、`defaultPlaybackSpeed` 参照は auth/settings/preferences のみ | player 初期化で defaultPlaybackSpeed を読む箇所 | R3 の速度 finding 撤回 | confirmed |
| P4 | セッション中の 401 を認証状態へ反映する経路は無い | `network/AuthInterceptor.kt:26-39`、`ApiException.kt:8-20`、`401` の grep 全 hit が `AuthViewModel.kt:137,152,158` | 401 → Unauthenticated の経路 | R5 の severity 低下 | confirmed |
| P5 | logout 以外（refreshAuth 失敗）で cleanup は走らない | `auth/AuthViewModel.kt:111-114`（clear のみ）, `:167-182`（logout のみ onLogoutCleanup） | 別の cleanup 経路 | R6 finding 撤回 | confirmed |
| P6 | `ExoPlayerController` は `onPlayerError` を購読していない | `podcast/ExoPlayerController.kt:71-115`（override は onPlaybackStateChanged / onIsPlayingChanged のみ） | 別 listener の存在 | R2 の error 状態 finding 撤回 | confirmed |
| P7 | 現状のテストは現状の実装に対して green（528/528） | verification-run.md V3 | — | — | confirmed（要件充足の Evidence ではない。rule 11） |
| P8 | 利用者・端末共有・次サイクルの変更予定は web と同じ（本人＋少数の知人、端末共有なし、再生領域が次サイクル） | §8 Q1（user 回答 2026-09-16） | android の利用形態が異なる | RF の severity と SG default が変わる | confirmed |

### 1.3 因果鎖（既存負債）

```yaml
causal_chain:
  applicability: required
  reason: 新規能力ではなく既存構造の負債
  symptom: 再生中の真実の二重保持、書かれるが読まれない状態（位置・既定速度）、失敗の意味の分散、離脱時 cleanup の片側性、Fake 9 重複
  violated_goal_or_quality: [QL1 modifiability, QL2 testability, QL3 fault tolerance, QL4 confidentiality]
  violated_rule_or_invariant: [R2, R3, R4, R5, R6, R7, R9]
  incorrect_owner_or_source: 再生 use case の判断（source 選択・挿入・完聴順序・失敗方針）と 6 責務が PodcastViewModel 1 クラスに同居し、設定の owner（PreferencesStore）と再生の owner（PlayerController）を結ぶ層が無い
  structural_cause: iOS 実装の忠実写像として機能単位（フェーズ）で移植され、フェーズ間の接続（キュー ⇄ currentPodcast、設定 ⇄ 再生、位置の read 側、401 ⇄ AuthState）が use case として設計されていない
```

## 2. Context Packet

```yaml
context_packet:
  actors: [開発者（人間/AI）, エンドユーザー（学習者・Listener）, 管理者（admin role）]
  problem: 上記 Problem Frame
  purposes: [変更容易性, テスト容易性, 再生/認証の失敗経路での安全性, 端末上の機密性（トークン・キャッシュ）]
  success_conditions: [finding が path:line 付き, 追跡表に分母/分子, 独立評価済み, decision schema 完全, 共有仕様との差分が明示]
  context:
    time_or_state: [2026-09-16, main @ 89e8350, AGP 8.13.2 / Kotlin 2.1.20 / media3 1.5.0 / OkHttp 4.12 / compileSdk 35 / minSdk 26]
    business_background: [副業有償化は 2026-09-09 に見送り・news-listen は休止中。本レビューは再開時の起点。web/ios レビューと同日並行]
    technical_background: [X-API-Key + Bearer（Keystore AES-GCM 保管）, Media3 ExoPlayer + MediaSessionService, ファイルキャッシュ（audio/<id>.mp3）, DataStore preferences, FCM]
  terminology:   # 共有仕様の語に揃える（web spec §1.3 term ledger と同一語）
    - {term: 現在再生中, meaning: "Queue.current（共有仕様 §2.1 不変条件 4）", alternative_meanings: [PodcastViewModel.currentPodcast, ExoPlayer の MediaItem, 行 UI の「選択中」(PodcastScreen.kt:160)], evidence: [core/PlaybackQueue.kt, podcast/PodcastViewModel.kt:83-86,115-121]}
    - {term: 既定速度, meaning: "新しい再生の初期速度（設定・永続・server 同期）", alternative_meanings: [セッション速度 = PlayerController.playbackSpeed], evidence: [preferences/PreferencesStore.kt:22, podcast/PlayerController.kt:35]}
    - {term: 完聴（completed listen）, meaning: "STATE_ENDED 到達の事象（markCompleted）", alternative_meanings: [生成完了 PodcastResponse.status 'completed'（PodcastStatusBadge.None）], evidence: [podcast/PodcastViewModel.kt:348-359, podcast/PodcastStatusBadge.kt:24-28]}
    - {term: 認証済み, meaning: "AuthState.Authenticated(user)（/auth/me 成功）", alternative_meanings: [保存トークンの存在], evidence: [auth/AuthState.kt:13-22]}
    - {term: 失効, meaning: "保存トークンで /auth/me が 401", alternative_meanings: [一時障害（NetworkError）— 現状は同一視される], evidence: [auth/AuthViewModel.kt:111-114]}
    - {term: 失敗の意味（ApiFailure）, meaning: "network / timeout / unauthorized / forbidden / not_found / conflict / rate_limited / validation / server", alternative_meanings: [HttpError.code 数値, 例外 message 文字列], evidence: [network/ApiException.kt:8-20]}
    - {term: 再生可能（Playable）, meaning: "PodcastStatusBadge.None（status が processing/failed/partial_failed 以外）", alternative_meanings: [audio_url 非空], evidence: [podcast/PodcastStatusBadge.kt:24-28, podcast/PodcastViewModel.kt:549-554]}
  rules:
    - {id: R1, statement: 業務ルール（status の意味解釈・文言・再生可否・quota）は 1 箇所に所有, kind: policy, owner: user, evidence_status: inferred}
    - {id: R2, statement: 再生の不正状態を公開経路から構築できない, kind: invariant, owner: user, evidence_status: inferred}
    - {id: R3, statement: 現在 Podcast / 速度（既定・セッション）/ 位置の source of truth が一意で writer が 1, kind: invariant, owner: user, evidence_status: inferred}
    - {id: R4, statement: 消費者は失敗の意味を受け取り transport 値（status 数値・例外 message）に依存しない, kind: policy, owner: user, evidence_status: inferred}
    - {id: R5, statement: 失効時に認証済み UI と保存トークンを残さず、一時障害を失効扱いにしない, kind: prohibition, owner: user, evidence_status: inferred}
    - {id: R6, statement: 主体が離れる遷移（logout・失効）の事後条件としてユーザー固有キャッシュが消える, kind: prohibition, owner: user, evidence_status: confirmed（共有仕様 §6.3・rule 12）}
    - {id: R7, statement: テストは production 経路を通り契約に対応付く。Fake の重複で契約が分散しない, kind: policy, owner: user, evidence_status: confirmed（agent-rules/11 :70-74）}
    - {id: R8, statement: CI が test・lint・build を独立ゲート化, kind: policy, owner: user, evidence_status: inferred}
    - {id: R9, statement: 共有再生仕様 §2/§3/§6 に準拠（Q/RT 全行・§6.1 source・§6.2 resume・§6.3 logout 消去）, kind: policy, owner: user, evidence_status: confirmed（spec 冒頭「Android を含む 3 プラットフォームが準拠」）}
  quality_lens:
    definitions:
      - {id: QL1, quality: {reference_model: "ISO/IEC 25010:2023", level: subcharacteristic, characteristic: maintainability, subcharacteristic: modifiability, standard_term: modifiability, display_name_ja: 変更容易性, source_terms_ja: [変更容易性]}, evidence: [ユーザー選択 2026-09-16]}
      - {id: QL2, quality: {reference_model: "ISO/IEC 25010:2023", level: subcharacteristic, characteristic: maintainability, subcharacteristic: testability, standard_term: testability, display_name_ja: テスト容易性, source_terms_ja: [テスト容易性]}, evidence: [ユーザー選択 2026-09-16]}
      - {id: QL3, quality: {reference_model: "ISO/IEC 25010:2023", level: subcharacteristic, characteristic: reliability, subcharacteristic: fault tolerance, standard_term: fault tolerance, display_name_ja: 障害許容性, source_terms_ja: [信頼性]}, evidence: [再生/認証の失敗経路]}
      - {id: QL4, quality: {reference_model: "ISO/IEC 25010:2023", level: subcharacteristic, characteristic: security, subcharacteristic: confidentiality, standard_term: confidentiality, display_name_ja: 機密性, source_terms_ja: [セキュリティ]}, evidence: [agent-rules/12, android/CLAUDE.md]}
    primary_ids: [QL1, QL2]
    secondary_ids: [QL3]
    constraint_ids: [QL4]
    intentionally_not_optimized_ids: [performance（Explorer 報告に性能問題の Evidence なし）]
    tradeoff_decisions:
      - {id: TD1, statement: iOS 忠実写像（移植の追跡容易性）と android 側での use case 設計（正本一意）のトレードオフ, affected_quality_ids: [QL1, QL3], decision_maturity: {status: unknown, owner: user}, evidence: [podcast/PodcastViewModel.kt:514-520 の「iOS 忠実写像・spec 改訂候補」自認]}
  change_boundary:
    must_preserve: [共有仕様 §2/§3 の観測挙動（Q-*/RT-*）, backend API 契約, Keystore トークン保管, AuthInterceptor の三点一致, core/ の純粋性]
    may_change: [ViewModel/Screen の責務配置, PlayerController 契約の拡張, ApiException の語彙, テスト戦略（Fake 集約・production 経路）, CI ゲート]
    must_not_change: [backend 契約, 共有仕様 §2 の QueueState 表現, 公開 endpoint]
    out_of_scope: [iOS/web, 意匠, FCM 配信設定, release 署名]
  evidence:
    confirmed: [P1-P8, R6, R7, R9]
    inferred: [R1-R5, R8]
    assumptions: []
    unknowns: []   # §7 に canonical record
    contradictions: []
```

### 2.1 AI 復唱

```yaml
ai_restatement:
  statement: >
    android モジュールの変更容易性・テスト容易性を主眼に、再生/認証の状態の真実、失敗の意味、離脱時の cleanup、
    共有再生仕様への conformance、テストの本番経路同一性を R1〜R9 に正規化し、Architecture / Completeness / Contract / Boundary の
    4 Function で監査して finding と obligation を返す。修正手段は選択せず Selection Gate に隔離し、web の決定は default 候補として併記するが選択済みにしない。
  comparison_basis: [指示書 prompt-android.md, AskUserQuestion 回答（docs/research-reports/・maintainability primary）, ランブック, R1-R9, change_boundary]
  proposed_status: matched
  differences:
    - "§4 は primary=maintainability を宣言しつつ constraint（QL4）違反を先頭配置する。理由: constraint は must-hold で primary の最適化に先行する"
    - "web の R1〜R8 に R9 を追加した。理由: 指示書「共有再生仕様 Q-*/RT-* の conformance を Contract の中核に」"
  reviewed_by: {kind: independent_evaluator, identity: "adversarial-verifier (T5)", review_status: accepted, evidence: ["§7.1", "2026-09-16-code-design-review/t5-adversarial.md"]}   # scope・依頼一致は accepted（RC8 pass）。finding 個別の判定は §7.1
```

## 3. Requirement Catalog と rejection criteria

| ID | actor | trigger / context | expected_result | prohibited_results | 現行分類 | QL | acceptance（観測方法） |
|---|---|---|---|---|---|---|---|
| R1 | 開発者 | 業務ルール（404/409 の意味・admin 判定・速度選択肢・再生可否）を変更 | 1 箇所の変更で全 UI に反映 | 同一ルールの複数実装、Screen 内の status 分岐 | intentional-change | QL1 | grep で同一ルールの実装箇所数 = 1、Composable に `ApiException` 参照 0 |
| R2 | Listener | メディアエラー / ended / 次エピソード取得失敗 | UI が状態を正しく区別し、queue と表示が一致 | error と pause の判別不能、queue.current ≠ 表示中 | intentional-change | QL3 | 状態型が排他 union、不正状態のテスト |
| R3 | 開発者 | 現在 Podcast / 既定速度 / 位置を読む・書く | writer/reader が単一 owner を通り、書いた値が読まれる | 二重保持の乖離、write-only 状態 | intentional-change | QL1/QL3 | owner 表で writer 1・reader ≥ 1 |
| R4 | ViewModel/Screen 実装者 | API 失敗を表示 | 失敗の意味（判別共用体）を受け取る | `code == 4xx` 比較、`e.message` の直接表示 | intentional-change | QL1 | 数値比較 0、`= e.message` 0 |
| R5 | Account owner | 失効（401）／一時障害 | 失効は未認証へ＋トークン破棄、一時障害は認証済み維持＋再試行 | 一時障害でトークン削除、失効後も認証済み UI | intentional-change | QL4/QL3 | refreshAuth の失敗分類テスト、セッション中 401 のテスト |
| R6 | 次の利用者（端末共有時） | logout / 失効後 | 音声キャッシュ・FCM トークン・（要決定）preferences・再生状態が残らない | 前利用者のキャッシュ/設定/再生が残る | must-preserve（logout 音声・FCM）/ missing（失効・preferences・再生状態） | QL4 | 失効経路の cleanup テスト、logout 後の preferences/queue の観測 |
| R7 | 開発者 | テストを緑にする | production 経路（OkHttpApiClient・PlayerController 契約）を通り CI* に対応 | Fake 9 重複、androidTest 0 | intentional-change | QL2 | production 経路の件数、Fake の定義数 |
| R8 | 開発者 | PR を出す | CI が test / lint / build を独立に実行 | `build` 1 ステップのみ | intentional-change | QL2 | ci.yml のステップ |
| R9 | Listener | 別端末／同端末で続きから再生、オフライン、logout | §6.1 source・§6.2 server-wins resume・§6.3 消去 | resume 未適用、§6.3 の対象漏れ | must-preserve（Q/RT/§6.1）/ missing（§6.2） | QL3 | resume のテスト、§6.3 対象一覧 |

```yaml
rejection_criteria:   # 本レビュー成果物自体の拒否条件
  - {id: RC1, requirement_ids: [R1-R9], condition: finding に path:line Evidence が無い／Explorer 報告の未確認転記, gate: verification}
  - {id: RC2, requirement_ids: [R7], condition: trial-log 棄却済み案（@StringRes ラベルの unit test）や web で棄却済みの過剰抽象（factory/Strategy 階層・汎用 Storage port・QueueState branded type）を提案に含める, gate: core}
  - {id: RC3, requirement_ids: [], condition: subject_verdict と artifact_readiness の混同, gate: verification}
  - {id: RC4, requirement_ids: [R7], condition: T*（既存テスト）の coverage 分母・分子を数えていない, gate: contract}
  - {id: RC5, requirement_ids: [], condition: AI 復唱 matched を自己証明にする（独立評価未実施）, gate: core}
  - {id: RC6, requirement_ids: [R1-R3], condition: pattern 名・class 数を根拠にする／将来用抽象を推奨, gate: boundary}
  - {id: RC7, requirement_ids: [], condition: スコープ外（backend 契約・iOS/web・意匠・署名）を finding にする, gate: core}
  - {id: RC8, requirement_ids: [R9], condition: web の人間判断（SG3〜SG5・Q5/Q6）を android で選択済みとして扱う, gate: core}
```
---

## 4. Findings（優先品質順。severity は proposed、§7.1 の独立評価と §8 の人間判断で確定）

凡例: gate = core/requirements/architecture/completeness/contract/boundary/verification。参照 ID は §5 の各 package 内で解決する（F*=Architecture finding、ARCH-SG*=Architecture の Selection Gate、G*=Completeness gap、IV*=不正状態、OB-C*/OB-T*=契約/テスト obligation、CI*=contract item、LF*=leakage、RO*=rejected overdesign）。実測値の出典は `2026-09-16-code-design-review/verification-run.md`。パスは `app/src/main/java/com/rioikeda/newslisten/` 相対。

### 4.1 Constraint（QL4 confidentiality）違反

（RF2 は独立評価の指摘で §4.2 へ移動。QL4 違反として残るのは RF1 (a)(c)(d) のみ）

| ID | severity | gate | finding | Evidence（path:line） | 違反 R | 参照 | required_action（obligation。手段選択は SG） |
|---|---|---|---|---|---|---|---|
| RF1 | (a)(c)(d): major（機密性。端末共有なしの前提 P8 が成立する場合。P8 が偽なら blocker）／ (b): major（可用性。P8 に依存しない無条件。独立評価の指摘で分離） | completeness/architecture | **失効・一時障害・主体離脱の 3 概念が未分離**。(a) `refreshAuth` は `ApiException` 全種（`NetworkError` 含む）で保存トークンを削除し `Unauthenticated` へ落とす＝機内モード起動や 5xx で強制再ログイン。(b) セッション中の 401 を認証状態へ反映する経路が無く、失効後も `Authenticated` のまま全 API が失敗する。(c) cleanup（音声キャッシュ・FCM トークン）は `logout()` のみに結線され、失効経路では走らない。logout でも preferences 8 key と再生状態（queue / currentPodcast / player）は残る。(d) `KeystoreSessionStore` がトークンを黙って削除する経路（復号失敗）が `authState` に通知しない | `auth/AuthViewModel.kt:111-114`, `:167-182`; `network/AuthInterceptor.kt:26-39`（ヘッダ付与のみ）; `network/ApiException.kt:8-20`（unauthorized の型なし）; `di/AppContainer.kt:266-281`; `network/KeystoreSessionStore.kt:88-91`; `preferences/DataStorePreferencesStore.kt:117-128` | R5, R6, R9（§6.3） | F4, G7, G8, G9, G19, IV3, IV4, OB-C7〜C10, OB-C20, CI-A03（既存テスト `AuthViewModelTest.kt:95` が違反挙動を pin＝**contradictory**）, CI-A07/A11/A12/A17/A18, CI-X08, LF13, LF14, LF15, OB-B5, ARCH-SG4, ARCH-SG5 | 失効（unauthorized）と到達不能（network）を型で分け、失効時のみトークン破棄。「主体が離れる」遷移（logout・失効）の事後条件として cleanup を契約化（OB-C9）。cleanup の**範囲**（音声・FCM に preferences・再生状態を加えるか）と **401 の検出点** は SG |

### 4.2 Primary（QL1 modifiability / QL2 testability）

| ID | severity | gate | finding | Evidence | 違反 R | 参照 | required_action |
|---|---|---|---|---|---|---|---|
| RF3 | major | architecture/completeness | **「現在再生中」の authority が split**。`_currentPodcast` と `_queue.currentIndex` が独立に同じ事実を持ち、整合は手続き（`keepCurrentPodcast` flag）で維持。乖離が構築できる経路 2 本: 完聴 → advance → `play(next)` の NETWORK 失敗（`stopInternal` が currentPodcast=null にした後、queue.current は next のまま）、ゲート拒否時。コード自身が「review 指摘」として自認。行 UI の「再生中」は `currentPodcast?.id == podcast.id`（選択中の意味）、QueueSheet は両者が揃うときのみ表示 | `podcast/PodcastViewModel.kt:83-86,115-121`（2 系統）, `:305-310`, `:341-346`（自認）, `:361-368`, `:501-511`; `podcast/PodcastScreen.kt:160`; `podcast/QueueSheet.kt:83` | R3, R2 | F2, G5, IV1, OB-C5, OB-T5, CI-P13, LF9, LF11, ARCH-SG1 | 正本を 1 つ選ぶ（ARCH-SG1。web は `Queue.current`。android では `advance()` が末尾で currentIndex を維持するため「停止」表現の扱いが論点）。派生値化で構造的に乖離不能に |
| RF4 | major | completeness/architecture | **再生位置が write-only**。15 秒ごと＋停止直前に server へ PATCH するが、再生開始時に `playbackPositionSeconds` を読む経路が無い（module 全体で reader 0）。共有仕様 §6.2 `resolveResumePosition`（server-wins）が android に不在で、「続きから聴く」が成立しない。一時停止中も 15 秒 PATCH が続く（コードが spec 改訂候補と自認） | `podcast/PodcastViewModel.kt:324-331`（seek なし）, `:514-542`; `model/PodcastResponse.kt:31`; `podcast/ExoPlayerController.kt:117-135`（prepare に位置なし）; spec §6.2 | R3, R9 | F1, G3, G18, SD1, OB-C3, OB-C19, OB-T3, CI-S04/S05, CI-P24, ARCH-SG3, COMP-SG1, COMP-SG4 | `core/` に純粋関数（`resolvePlaybackSource` と対称）を置き `beginPlayback` で 1 回 seek。完聴境界（server 値 ≒ duration のとき即 ENDED になる）の規則は spec 追記が要る（3 platform 合意・OB-A4） |
| RF5 | major | architecture | **既定速度が再生に適用されない**。`PreferencesStore.defaultPlaybackSpeed` は保存・server 同期されるが `PlayerController.setSpeed` の呼出元は UI の速度選択 1 箇所のみ。速度の選択肢が 8 段（再生 UI）と 5 段（設定 Screen の private val）で二重定義・不一致。setter に値域検証なし（server 値 0.0 が入れば G4 解消と同時に無音再生）。**追記（独立評価 N1）**: 設定画面は保存値を Double 完全一致で 5 段と照合し、一致しなければ 1.0 の index に落とすため、1.75 等の保存値は恒久的に「1.0x」と表示される silent value-loss（Float/Double の型不一致も） | `settings/SettingsScreen.kt:182-183`; `preferences/PreferencesStore.kt:22`; `auth/AuthViewModel.kt:125`; `podcast/AudioPlayerSection.kt:285`（唯一の setSpeed 呼出）; `podcast/PodcastViewModel.kt:465-467`; `podcast/PlaybackConstants.kt:12` vs `settings/SettingsScreen.kt:1361`; `preferences/DataStorePreferencesStore.kt:85-87` | R3, R1 | F3, G4, IV7, OB-C4, OB-C6, OB-T4, CI-P07/P27, CI-X02, LF17, ARCH-SG2, COMP-SG3 | 既定速度の適用点（`beginPlayback`）とセッション速度の扱い（切替で reset するか）を決める（ARCH-SG2。web は 2 概念・load 時に既定から初期化）。選択肢集合を 1 箇所に |
| RF6 | major | boundary/contract | **失敗の意味が transport 値のまま consumer へ漏れる**。`ApiException` は `RateLimited | HttpError(code) | DecodingError | NetworkError` の 4 種で unauthorized / forbidden / not_found / conflict / server の意味型が無い。結果 `HttpError.code` の数値比較が **8 ファイル 8 箇所**（Composable `QuizSheet` を含む）に分散し、404 は 3 意味（機能未提供 / 冪等成功 / リソース消失）、409 は「既登録＝成功」を consumer 側で再発明。rate limit は「本日の生成上限」固定（月次の概念なし） | `network/ApiException.kt:8-20`; `network/OkHttpApiClient.kt:456-463`; 数値比較: `auth/AuthViewModel.kt:152`, `account/AccountViewModel.kt:121-126`, `settings/SettingsViewModel.kt:160`, `engagement/ListeningStreakStore.kt:56`, `network/OkHttpApiClient.kt:303`, `podcast/QuizSheet.kt:194-201`, `onboarding/OnboardingViewModel.kt:86`, `passkey/PasskeyRegistrationViewModel.kt:54`（verification-run §3a）; `feed/FeedViewModel.kt:290-303` | R4, R1 | F5, G10, G11, G13, OB-C11, OB-C12, OB-C14, OB-T11, OB-T12, CI-N02/N09, LF4, LF7, OB-B2 | 失敗を意味の判別共用体に変換する層を network 境界に置き、404 の意味は endpoint ごとに gateway 側で確定。consumer から status 数値を消す |
| RF7 | major | verification/architecture | **テストダブルが production 契約の複製**。`ApiClient` 44 メソッド（abstract 35 + throwing default 9）に対し Fake が **9 ファイル 1,393 行**（各 35〜39 override、8/9 は override 集合が同一）。interface に 1 メソッド足すと 9 ファイルが同時に壊れ、production interface 内の `error("... not stubbed")` は override 漏れを compile で捕まえない。production HTTP 経路（MockWebServer）を通るテストは 53 / 528、`androidTest` ディレクトリ不在、JaCoCo なし | `network/ApiClient.kt:37,66-67`（default 9）; test Fake 9 ファイル（verification-run §3g）; `network/LearningApi.kt:9-11`・`VocabularyTestApi.kt:8-9`（狭い port が既に 2 つ＝不統一） | R7 | F6, CI-N18, CI-P03, LF6, LF8, RO4（44 分割は棄却）, ARCH-SG7 | Fake 基底 1 つへの集約か consumer 別の狭い port（既存の `LearningApi` 方式）への分割（ARCH-SG7）。production interface から throwing default を除去（N3: default に到達すれば handled error ではなく `IllegalStateException` クラッシュ。除去の優先度は高い） |
| RF8 | major | boundary/requirements | **業務ルールの owner が入口層に散る**。admin 判定 `role == "admin"` が Composable と DI の 2 箇所（同一式）＋関数注入の二重ガード、quota 文言決定（`limit == 0` = 無制限）が Composable、クイズ正答率 0.5 の閾値が Screen ファイル、速度・週目標の選択肢が Screen の private val、`PodcastViewModel` は 13 StateFlow・6 責務（一覧・語彙・ダウンロード・再生・キュー・クイズ中継）、`SettingsScreen` 1362 行に 13 セクション、Screen が `PreferencesStore` を直接読み書き（write 4 / read 7。ViewModel を迂回する層の飛び越え。RF9 から移管）。規模（13 StateFlow・1362 行）は根拠ではなく症状で、根拠は「1 ルールの変更が複数層に跨る」こと（CS4 fail） | `settings/SettingsScreen.kt:85,129-135,245,259,271,278`; `AppScaffold.kt:115`, `di/AppContainer.kt:409`, `settings/SettingsViewModel.kt:128`; `settings/SettingsScreen.kt:511-518,1361-1362`; `podcast/QuizSheet.kt:49-50`; `podcast/PodcastViewModel.kt:41-121`（行数は verification-run §3d/§3f） | R1 | LF12, LF20, LF21, CS4 fail（6 rule 中 5 に owner 不在）, RO6（authority 統一前の PodcastViewModel 分割は棄却）, OB-B6, OB-B8 | 各ルールの owner を 1 箇所に。`PodcastViewModel` の分割は **RF3 の authority 統一の後**（RO6: 先に分割すると二重 owner が 2 クラスに跨って悪化） |
| RF9 | major | completeness | **設定値の invariant owner 不在**。`PreferencesStore` の setter は値域検証なし。不正値を構築できる経路は **server 同期 1 本**（`AuthViewModel` が server の `default_playback_speed` / `weekly_goal_episodes` / `default_difficulty` を無検証で setter へ渡す）で、DataStore 永続化がそれを次回起動以降へ増幅する。検証があるのは `SettingsViewModel` の週目標 1 箇所のみ（UI 経路だけ守る）。**訂正（独立評価）**: 初版の「Screen 直書き 4 箇所が検証を迂回」は誤り。4 箇所は enum / Boolean で型が全域であり不正値を構築できない（owner 分散の論点として RF8 へ移す）。観測可能な帰結（N2）: 範囲外の週目標は `indexOf(...).coerceAtLeast(0)` で無言に先頭選択肢として表示され、`/ 7.0` の計算はガードなし | `preferences/DataStorePreferencesStore.kt:81-87,105-107`; `auth/AuthViewModel.kt:124-126`; `settings/SettingsViewModel.kt:193-197`; `settings/SettingsScreen.kt:186,305` | R3, R1 | G14, IV6, IV7, OB-C15, OB-T15, CI-X01/X02/X06, CI-A20, LF17, LF18, OB-B3 | 設定ごとに値域を持つ単一の検証点（store の setter か registry）を置き、server 同期経路も通す |
| RF10 | major | completeness | **UiState が直積で不正状態を構築できる**。`LearningUiState` は `isLoading`/`dashboard`/`loadFailed` が独立（8 通り構築可）で、Screen が組合せ条件で排他を復元し `dashboard!!` を 6 箇所使う。sealed で排他なのは `AuthState` と `VocabularyTestPhase` のみ。`LoginScreen` は送信中状態を UI の `remember` で保持 | `learning/LearningViewModel.kt:15-23`; `learning/LearningScreen.kt:72,86`（`dashboard!!` 6 箇所）; `auth/LoginScreen.kt:64`; `feed/FeedViewModel.kt:44-71` | R2, R1 | G15, IV5, OB-C16, OB-T16, LF19, OB-B9（Contract は subject 外＝OB-N8） | 排他 union（Loading / Loaded / Failed）へ。`VocabularyTestPhase` の方式を踏襲 |
| RF11 | minor | verification | CI が `./gradlew build` 1 ステップ（＋gitleaks）で、test / lint / build の失敗が識別できない。detekt・ktlint・JaCoCo・Dependabot 未導入。ローカル既定 JDK 26 では Kotlin コンパイラが起動できず（`IllegalArgumentException: 26.0.2.1`）、JDK 17/21 が別途要る | `.github/workflows/ci.yml:34-35`（Build and test ステップ）, `:37-47`（gitleaks）（verification-run §1・§2） | R8 | F9, ARCH-SG8 | ゲート分割は運用判断（ARCH-SG8）。JDK 要件を README か `gradle.properties`（toolchain）に固定 |

| RF2 | minor | boundary | 例外 message がそのまま UI 文言になる（`"HTTP Error 500"`、`"Network error: <cause.message>"`＝OkHttp の IOException 文言）。利用者に無意味で i18n 不能。**訂正（独立評価）**: 初版は confidentiality 違反として §4.1 に置き `ApiClient` の throwing default（`error(...)`=IllegalStateException）も Evidence にしていたが、後者は UI ハンドラの `catch (e: ApiException)` に捕まらず UI 文言化経路が無く、9 default は `OkHttpApiClient` が全て override 済みで production 到達もしない（N3: 到達すればクラッシュ）。実害は UX であり §4.2 へ移動 | `network/ApiException.kt:13,19`; `podcast/PodcastViewModel.kt:135,212,214,251,309`; `feed/FeedViewModel.kt:89,106,204`（2 ファイル 8 箇所、verification-run §3b） | R4 | G12, OB-C13, OB-T13, CI-P28, LF5 | UI 文言は失敗の意味から決め、transport 文字列を含めない（RF6 の解決で同時に閉じる） |

### 4.3 Secondary（QL3 fault tolerance）

| ID | severity | gate | finding | Evidence | 違反 R | 参照 | required_action |
|---|---|---|---|---|---|---|---|
| RF12 | major | completeness/contract | **再生状態の公開語彙が 1 bit**（`isPlaying`）で、error / ended / idle / paused が同じ `false`。`ExoPlayerController` は `onPlayerError` を購読せず、到達不能 URL・デコード失敗は「押しても鳴らない」としか観測できない。`seekTo` は clamp せず（skip 系のみ clamp）、`setSpeed` は任意 Float | `podcast/PlayerController.kt:26-35`; `podcast/ExoPlayerController.kt:73-114`（override 2 種のみ）, `:87-98`; `podcast/PodcastViewModel.kt:444-462` | R2 | F7, G1, G2, G6, IV2, OB-C1, OB-C2, OB-C6, OB-T1, OB-T2, OB-T6, CI-P02/P03/P06, CI-S03, LF1, LF3, OB-B1, OB-B7, ARCH-SG6 | 状態 union（idle / preparing / playing / paused / ended / failed(reason)）を境界の出力型に。`isPlaying` は派生値として残す（ARCH-SG6） |
| RF13 | minor | contract | 完聴処理の順序契約が不完全: `markCompleted` は best-effort で黙殺、最終位置の保存は `play(next)` の副作用（`stopInternal`）で行われ、その時点の位置は STATE_ENDED/IDLE のタイミング依存（IDLE では 0 にリセット）。キューが尽きた場合は `keepCurrentPodcast=true` で次の `play()` が二重同期しうる（inferred） | `podcast/PodcastViewModel.kt:348-370,502-512`; `podcast/ExoPlayerController.kt:93-97` | R2, R9 | G18, OB-C19, CI-N07/N08b, CI-P24, OB-N5 | 完聴時の順序（completed → 位置 → advance）と位置の値（0 か duration か）を契約化。backend の first-write-wins 依存はコメントとテストに明記 |
| RF14 | minor | completeness | `PlaybackQueue` の不変条件 2（currentIndex の範囲）が型で未保証: public constructor / `copy` で `currentIndex=5, items=2` を構築でき、`advance()` が永久に停止を返す（現 production 経路では in-range） | `core/PlaybackQueue.kt:23-33,84-91`; spec §2.1 | R2, R9 | G16, IV8, OB-C17, OB-T17, CI-Q01/Q01b/Q02, OB-N1 | 内部 gate（不変条件検査）を置き、公開操作は §2 どおり正規化。Q-01〜Q-32 は不変 |
| RF15 | minor | completeness | キャッシュの完全性概念が無い: `cache()` は bytes を無検証で書き、`isCached` はファイル存在のみ。破損キャッシュは CACHED が常に優先されるため恒久的に再生不能（RF12 の error 未観測と合わさり自己復旧しない） | `network/AudioCacheManager.kt:54-69`; `core/PlaybackSourceResolver.kt:16-21` | R2, R9 | G17, IV9, OB-C18, OB-T18, CI-N06/N17, CI-S03, OB-N4 | 再生失敗時にキャッシュを無効化して NETWORK へ退避する契約 |
| RF16 | minor | contract | `SessionStore.save` が失敗を返さず（暗号化失敗は Log のみ）、`login` は保存失敗でも `Authenticated` へ遷移する（再起動で無言ログアウト）。`KeystoreSessionStore` は単体テスト対象外と自認 | `network/SessionStore.kt:12-19`; `network/KeystoreSessionStore.kt:40-53,88-91`; `auth/AuthViewModel.kt:147-149` | R5, R7 | G19, IV3, OB-C20, OB-T20, CI-A07/A10/A11, LF13, LF14, OB-N6 | save の成否を返し、失敗時は Authenticated へ遷移しない |
| RF17 | low（記録のみ） | architecture | `podcast` ⇄ `playbackservice` の相互参照と生 `Player` の公開（`PlaybackService` が service locator 経由で具象 `ExoPlayerController` に依存）。所有権は doc で規律化済み | `podcast/ExoPlayerController.kt:143,202-203`; `playbackservice/PlaybackService.kt:37-39` | — | F8, LF2, RO7 | 変更しない。ADR に記録 |
| RF18 | minor | requirements | パスワード規則の文言が「12 文字以上・4 種中 3 種」でハードコード（クライアント検証なし・backend の 422 依存）。web レビューは user 判断で 8〜20 文字（SG7）を採用済みで、backend 実値（OB-A1）を含め 3 者の整合が未確認 | `account/AccountViewModel.kt:106-110,121-126,139-140` | R1（cross-module） | web SG7 / OB-A1 | 統一値を backend 正本で確定してから各クライアントの文言を揃える（SG） |

### 4.4 finding にしなかったもの（RC7・反証済み・適合）

- **セキュリティ（rule 12）は適合**: `BuildConfig.API_KEY` 参照は `di/AppContainer.kt:66` のみ、`AuthInterceptor` は scheme/host/port 三点一致でのみヘッダ付与（`:30-37`、6 テスト）、トークンは Keystore AES-GCM、release は cleartext 拒否（debug のみ `10.0.2.2`/`localhost`）、`Log.*` 3 箇所とも固定文字列、CrashReporter は例外 message を送らない。release `signingConfig = debug`（`app/build.gradle.kts:66`）は自認コメント付きで out_of_scope。
- **共有仕様 §2/§3/§6.1 は準拠**: Q-01〜Q-32・RT-01〜15・RT-A01/A02 の 49 ID すべて存在し green（V3/V5）。`resolvePlaybackSource` は §6.1 と一致（4 テスト）。差分は §6.2（RF4）と §6.3 の Android 行不在（RF1、spec 側の不足）。
- **`core/` の純粋性**: `android.*`・`network` への import 0。ViewModel は `android.*` 依存 0・`androidx.lifecycle` 非継承。関数注入（`onLogoutCleanup`/`isAdminProvider`）による層分離は一貫しており、`AccountViewModel` への `authViewModel` 直接注入（`AppContainer.kt:432-437`）は理由が明記され finding にしない。
- **コメントのみの catch 23 箇所**（verification-run §3c）: すべて WHY 付き best-effort で、無言の空 catch は 0。個別には RF1（refreshAuth）・RF13（markCompleted）で扱う。
- **性能**: Evidence なし。intentionally_not_optimized。
- **UI 意匠・backend 契約・iOS/web 実装**: out_of_scope。

## 5. Function packages（lossless 付録）

router の要約は §4 に圧縮しているが、stable ID・Evidence 状態・authority・coverage 分母/分子・subject verdict は次のファイルで保持する（`2026-09-16-code-design-review/` 配下）。

```yaml
function_plan:
  - {function: architecture, run_if: "android 内 data authority が複数＋品質 trade-off", status: completed, artifact: architecture-strategy-package.md, subject_verdict: incomplete, package_decision: {status: proposed, artifact_readiness: ready}, note: "data authority 6 fact のうち一意 2（(d) 語彙不足・(e) 遷移規則違反を含めれば健全 0）。F1〜F9、D1〜D5、ARCH-SG1〜SG8 すべて pending。1,022 行"}
  - {function: discovery, run_if: "用語・context 発見", status: not_applicable, not_applicable_reason: "用語は共有再生仕様と既存 sealed 型（AuthState / PodcastStatusBadge）で確定。term ledger は §2 に固定"}
  - {function: completeness, run_if: "状態・遷移・失敗の欠落判定", status: completed, artifact: completeness-package.md, subject_verdict: "S1〜S4 すべて incomplete", package_decision: {status: pass, artifact_readiness: ready}, note: "subject 軸 applicable 45 / present 7。IV1〜IV9、G1〜G19、OB-C1〜C20 / OB-T1〜T20、SD1〜SD6、COMP-SG1〜SG4。turn 上限で最終要約なし（router がファイルを直接検査して採用）。1,419 行"}
  - {function: contract, run_if: "公開 operation の pre/post/failure", status: completed, artifact: contract-package.md, subject_verdict: insufficient, package_decision: {status: ready_with_obligations, artifact_readiness: ready}, note: "CI 97 件（Q16 / P28 / S5 / A21 / N19 / X8）: met 63 / partial 7 / unmet 27。既存テスト裏付け 69 / 97（うち production 経路 8）。T* 32 件。OB-N1〜N8。§12.1 の自己検査は未記載のため router が代行（191 引用すべて範囲内）。1,156 行"}
  - {function: boundary, run_if: "技術漏出・caller 分岐・長大処理", status: completed, artifact: boundary-package.md, subject_verdict: leaky（C1〜C6 全境界）, package_decision: {status: pass, artifact_readiness: ready}, note: "LF1〜LF21（19 件は自己 grep で行確認、LF4 の 8 サイト中 7 は unverified と明記→router が verification-run §3a で確認済み）、CS1 fail / CS2・CS3 not_applicable / CS4 fail / CS5 fail、RO1〜RO8、OB-B1〜B9。turn 上限で一度成果物ゼロ→再開指示で完走。1,662 行"}
  - {function: change_safety, run_if: "既存挙動変更", status: not_applicable, not_applicable_reason: "review mode・変更提案なし。修正着手時（Implementation Spec）で再判定"}
```

### 5.1 Selection Gate の統合（router 番号 SG-R*。各 package の番号は括弧内）

package ごとに SG 番号が独立しているため、§8 の問答は次の router 番号で行う。web の決定は default 候補であり選択済みではない（RC8）。

| SG-R | 論点 | 元 ID | default 候補（web の決定・iOS の実装） | 決定に必要な Evidence |
|---|---|---|---|---|
| SG-R1 | 「現在再生中」の正本 | ARCH-SG1, CI-P13 | web: `Queue.current`。android では `advance()` が末尾で currentIndex を維持するため「停止」表現が論点 | 署名 URL 再取得後の fresh DTO をどこに置くか |
| SG-R2 | 既定速度の適用方針とセッション速度の扱い | ARCH-SG2, CI-P07/P27/X02 | web: 2 概念・load 時に既定から初期化して以後保持。**iOS も既定速度を player に適用していない**（`ios/.../Podcast/PodcastViewModel.swift:50,291,575-577` は session 速度のみ。router 確認 2026-09-16） | 「このエピソードだけ速く」を許すか |
| SG-R3 | 速度選択肢の正本（8 段 vs 5 段） | COMP-SG3, LF17 | iOS も同じ二重定義（`PlaybackConstants.swift:15` 8 段 / `SettingsView.swift:50` 5 段）＝移植由来 | UX 判断（設定で 0.5/2.5 を選べるべきか） |
| SG-R4 | resume 規則の採用と完聴境界 | ARCH-SG3, COMP-SG4, CI-S04/S05 | spec §6.2 server-wins。**iOS は実装済み**: `position > 0 && !(duration > 0 && position >= duration − 2)` なら seek（`ios/.../PodcastViewModel.swift:296-307`）。backend は位置を duration に clamp して保存するだけで server-wins は client 側規則（`backend/api/routers/podcasts.py:219-242`） | iOS の 2 秒窓をそのまま採るか、spec に閾値を追記するか |
| SG-R5 | 失効時 cleanup の範囲 | ARCH-SG4, COMP-SG2, CI-A18/X08 | web: 失効時も SW 管理キャッシュ消去（音声は残す）。spec §6.3 に Android 行なし。preferences 8 key は user 寄り 4（difficulty / speed / weekly_goal / seen_achievements）と端末寄り 4（sfx / haptics / time_format / article_open_mode） | 端末共有の有無（P8） |
| SG-R6 | 失効と到達不能の分離・401 の検出点 | ARCH-SG5, CI-A03/A11/A12 | web: `unavailable` 状態を追加し一時障害でログアウト表示しない。android は `Unknown` が既存 | 起動時オフラインで何を出すか |
| SG-R7 | 再生状態の語彙と失敗時の方針 | ARCH-SG6, CI-P02/P03, CI-S03 | web: 状態 union（idle/loading/playing/paused/ended/error(reason)）、自動次再生の失敗は停止＋手動再試行、`unavailable` は「オフライン」文言 | player error 時に自動で次へ進むか |
| SG-R8 | Fake 重複と throwing default | ARCH-SG7, CI-N18, RO4 | 既存の狭い port（`LearningApi`）方式 or test 側基底 1 つ。44 分割（RO4）は棄却 | — |
| SG-R9 | 一時停止中の位置 PATCH | COMP-SG1, CI-P24, G18 | iOS 忠実写像（継続）。コード自身が spec 改訂候補と自認 | streak 集計が位置更新を入力にしているか（backend） |
| SG-R10 | Queue 不変条件 2 の構築時保証（拒否 / 正規化） | OB-N1, CI-Q01 | web spec: 内部 gate `Queue.create` で検査し違反は programmer error として throw、公開操作は正規化 | 3 platform 共有型なので web と揃えるか |
| SG-R11 | CI ゲート分割 | ARCH-SG8 | web: lint-test ジョブに typecheck と独立 build を追加 | CI 所要時間 |
| SG-R12 | パスワード規則の統一値 | RF18, web SG7 | web: 8〜20 文字（user 決定）。**backend 正本は 12 文字**（`backend/shared/password_policy.py:23`）。android 文言は 12 | backend を変えるか、web の決定を見直すか（cross-module） |

router による obligation の解決:
- OB-N7（`ApiClient.kt` の throwing default の実在）: router が `grep -c 'error("'` = 9、`:66-67` を確認済み（confirmed）。
- OB-N3（COMP-SG1 の内容）: §5.1 SG-R9 に統合。CI-P24 の met は SG-R9 の決定で反転しうる。
- OB-N8（OB-C12 / C14 / C16 / C19 の未引き受け）: RF6 / RF18（quota 文言は RF6 内）/ RF10 / RF13 として router が finding 化。CI は未作成のまま obligation 保持。
- OB-A3（iOS の速度適用・web の SG6/SG7）: router が確認。iOS も既定速度を適用していない（SG-R2）。web の SG6（`Queue.start/setQueue` 残置）・SG7（パスワード）は SG-R10 / SG-R12 に統合。
- Completeness U1（backend の resume 実装）: backend は保存と clamp のみ。server-wins は client 規則（SG-R4）。
- OB-A7 / Contract §11 / Boundary RO*: 棄却案は `docs/trial-log/mino-design-review-delegation.md` へ転記（本レビューの成果物として同時作成）。

## 6. Traceability と検証

### 6.1 要件 → package → test（分母 9）

| R | Architecture | Completeness | Contract（CI / 既存 test） | Boundary | status |
|---|---|---|---|---|---|
| R1 | F5, F3（選択肢二重定義） | G11, G13, G14 | CI-N02/N09/N18/P26/X03（unmet 3 / partial 2） | LF7, LF17, LF20, LF21, CS4 fail | **missing**（owner 不在） |
| R2 | F2, F7 | G1, G2, G5, G6, G15, G16, G17, IV1, IV2, IV5, IV8, IV9 | CI-Q01/Q02/P02/P03/P06/P07/P13/N17/S03（met 0 / partial 1 / unmet 9） | LF1, LF9, LF19 | **missing** |
| R3 | F1, F2, F3, authority 表 (a)(b)(c) | G3, G4, G14, IV6, IV7 | CI-P13/P27/S04/X01/X02/X06/A20（unmet 7） | LF11, LF16, LF17 | **missing**（authority 未選択） |
| R4 | F5 | G10, G12 | CI-N01/N02/N03/N04/P28/N09（met 3 / unmet 3） | LF4, LF5, LF7 | **partial** |
| R5 | F4 | G7, G8, G19, IV3, IV4 | CI-A03/A04/A07/A08/A10/A11/A12（met 1 / partial 1 / unmet 5）。`AuthViewModelTest.kt:95` が違反挙動を pin | LF13, LF14 | **contradictory**（green だが要件に反する） |
| R6 | F4, authority 表 (f) | G9 | CI-A15/A16/A17/A18/X08/N15（met 3 / unmet 3） | LF15, OB-B5 | **partial**（logout の音声・FCM のみ） |
| R7 | F6 | — | CI-N18/A10/P03（unmet 3）。production 経路 8 / 69 | LF6, LF8, CS5 fail | **partial** |
| R8 | F9 | — | out_of_contract_scope | — | **missing**（inferred 要件） |
| R9 | F1（§6.2） | SD1〜SD6 | Q-01〜Q-32 = 32/32 met、§6.1 met 2 / unmet 1、§6.2 unmet、§6.3 unmet（spec に Android 行なし） | — | **partial**（§2/§3/§6.1 は準拠、§6.2/§6.3 が欠落） |

coverage: covered 0 / partial 4（R4, R6, R7, R9）/ missing 4（R1, R2, R3, R8）/ contradictory 1（R5）。

### 6.2 validation

```yaml
validation:
  executed:
    - {id: V1, command: "./gradlew test（JDK 26、test-execution ロール）", result: fail, evidence: "Kotlin Gradle plugin が JDK 26 のバージョン文字列を解釈できず起動不能。テスト 0 件（環境要因）"}
    - {id: V2, command: "JAVA_HOME=<Android Studio JBR> ./gradlew test", result: fail, evidence: "V1 の異常終了で汚れた増分コンパイル状態により PodcastDecodingTest.kt:58 で Unresolved reference。CI は同コミットで green"}
    - {id: V3, command: "JAVA_HOME=<JBR> ./gradlew clean testDebugUnitTest", result: pass, evidence: "BUILD SUCCESSFUL 2m45s。67 suites / 528 tests / 0 failures / 0 skipped（verification-run.md §2）"}
    - {id: V5, command: "conformance ID の機械照合（grep -o）", result: pass, evidence: "Q-01〜Q-32（32）、RT-01〜15 + RT-A01/A02（17）すべて存在"}
    - {id: V6, command: "grep 定量（status 数値比較 / e.message / catch / MutableStateFlow / wc -l / Fake override）", result: pass, evidence: "verification-run.md §3"}
    - {id: V7, command: "package 内 path:line の範囲検査（wc -l 超過）", result: pass, evidence: "architecture 203/203（正誤表の意図的引用 6 件を除く）、completeness 469/469、contract 191/191、boundary 383/383。router の初版ブリーフの 3 件は Architecture が検出し修正済み"}
    - {id: V8, command: "cross-module 確認（iOS resume/速度、backend clamp/password policy）", result: pass, evidence: "§5.1 SG-R2/R4/R12"}
  passed: [V3, V5, V6, V7, V8]
  failed: [V1（環境）, V2（キャッシュ）]
  unexecuted:
    - {id: UV1, reason: "lint は build 内 lintVital のみ。独立実行は時間コスト", required_runner: "test-execution", planned_commands: ["JAVA_HOME=<JBR> ./gradlew lintDebug"], owner: user}
    - {id: UV2, reason: "契約 test 未 coverage 28 件（T* 32 件）は未実装（design 段階）", required_runner: "tdd-implementation", planned_commands: ["CI-A03 の既存テスト反転 → T-A03 RED から"], owner: user}
    - {id: UV3, reason: "resume 未適用・既定速度未適用・自動次再生失敗時の UI は実機／エミュレータ観測が必要", required_runner: "user 手動（AVD newslisten_e2e）", planned_commands: ["再生 → 停止 → 再タップで位置が 0 に戻ることの確認"], owner: user}
    - {id: UV4, reason: "JVM から到達できない契約（ExoPlayer 配線 CI-P03、Keystore CI-A08/A10）の検証基盤が無い（androidTest 不在）", required_runner: "user 判断（OB-N6）", planned_commands: [], owner: user}
  platform_validation: {required_platforms: [android], executed: [jvm-unit-test], unexecuted: [android-instrumented, emulator], parity_result: not_applicable, platform_specific_risks: ["Media3 の main-thread 制約（LF3）と foreground service 起動は JVM テストで観測不能", "Keystore の鍵欠落・改ざんは実機依存（KeystoreSessionStore が単体テスト対象外と自認）"]}
```

注: V3 は「現状の実装が現状のテストに対して green」であることの Evidence であり、R5 の contradictory（`AuthViewModelTest.kt:95` が一時障害でのトークン破棄を正として pin）が示すとおり要件充足の Evidence ではない（rule 11）。

## 7. Canonical decision

```yaml
decision:
  status: pass                       # review artifact として必須 gate を満たし、未実行事項と risk を明示
  artifact_readiness: ready
  engineering_status: not_started    # 修正は未着手
  release_status: not_applicable
  decision_maturity: {status: approved, owner: user, scope: [android/ の finding 採否と着手順], evidence_status: confirmed, approval_evidence: ["§8 dig-me セッション 2026-09-16（Q1〜Q13 の回答と共通理解の確認）"], baseline_version: "2026-09-16", change_control: "本文書の §8 を更新して再承認"}
  subject_verdict_summary: {architecture: incomplete, completeness: incomplete, contract: insufficient, boundary: leaky}
  next_phase:
    name: 修正計画（Implementation Spec, design mode）
    status: allowed
    reasons: ["SG-R1〜SG-R12 は 2026-09-16 の人間判断（§8）で satisfied", "P8 は Q1 で confirmed（web と同じ）"]
    human_approvals_required: []
    resolved_by: "§8（dig-me セッション 2026-09-16、owner: user）"
  evidence: [V3-V8, 各 package の Evidence 記録]
  assumptions: ["R1-R5・R8 は router が実コードと rule から導いた inferred 要件（§8 の採否で暗黙承認）"]
  unknowns:
    - {id: U1, subject: "streak 集計が位置 PATCH の存在を入力にしているか（SG-R9 の Evidence）", confirmation_method: "backend の streak 集計ロジックを読む", impact_if_unresolved: "CI-P24 の met/unmet が確定しない", owner: user, evidence: [G18]}
    - {id: U2, subject: "markCompleted の backend 冪等性（OB-N5、web U4 と同一）", confirmation_method: "backend の completed handler と ADR-075", impact_if_unresolved: "CI-N07 の client 側抑制の要否", owner: user, evidence: [CI-N07]}
    - {id: U3, subject: "OkHttp timeout が per-attempt か end-to-end か（Boundary U1）", confirmation_method: "OkHttp 4.12 の callTimeout 未設定時の挙動確認", impact_if_unresolved: "retryOnConnectionFailure との二重 retry の可能性", owner: user, evidence: [di/AppContainer.kt:77-81]}
    - {id: U4, subject: "静的 @Test 534 と実行 528 の差 6", confirmation_method: "UV4 相当の集計", impact_if_unresolved: "なし（件数の注記のみ）", owner: router, evidence: [verification-run.md §2]}
  contradictions: ["R5: 既存テスト AuthViewModelTest.kt:95 が green のまま要件（一時障害で失効扱いにしない）に反する（CI-A03）"]
  failed_gates: []
  unexecuted_validation: [UV1, UV2, UV3, UV4]
  platform_validation: {parity_result: not_applicable}
  residual_risks: ["RF1 の major 判定は P8（端末共有なし）を仮定。共有端末があれば blocker", "Boundary LF4 の 8 サイトのうち 7 は Boundary 自身は unverified（router の verification-run §3a で全件確認済み）", "Completeness の Screen 層引用（IV5 の Screen 側等）は router 記述に依存（confirmed_by_router）。独立評価の対象", "Contract の §12.1 自己検査は未記載（router 代行）", "件数主張（Fake 行数・CI 件数・catch 件数）は per-file 内訳のあるもの以外は inferred として読むこと"]
  human_approvals_required: []   # §8 で解決
```

### 7.1 独立評価（adversarial-review ロール）

全文: `2026-09-16-code-design-review/t5-adversarial.md`。評価者は 4 package を判定材料にせず、§4 の主張を自分で grep / read して反証を試みた（「引用行を 1 ファイル 1 コマンドで再取得 → 主張の機構を自分で構成 → 型の全域性で構築可能性を反証」）。

```yaml
reviewed_by:
  kind: independent_evaluator
  identity: adversarial-verifier (T5)
  review_status: partially_accepted   # 初版 verdict は REJECT（scoped）。訂正 2 件を反映した現版で accepted 相当
  scope_accepted: [RF1(a)(b)(c)(d), RF3, RF4, RF5, RF6, RF7, RF12, "RF9 の『検証点 1 箇所』部分", RF17 low, RF18 の cross-module 送付, "RC1/RC2/RC3/RC7/RC8 pass"]
  scope_weakened: [RF9（機構の誤り: Screen 直書き 4 箇所は型が全域）, RF2（Evidence の誤り: throwing default は UI に到達しない。配置は §4.2 へ）, RF1（P8 条件付き severity は (b) に適用不可）, "RC6 conditional（RF8 の規模メトリクス）", "RC2 conditional（RF8 が RO6 の順序前提を未明記）"]
  scope_unverifiable: [RF15, RF17 の引用 3 行, "tests=528 の分母", UV1-UV4]
  citations_checked: 64
  citations_wrong: 0    # ±2 行の drift 2（PodcastViewModel.kt:502-512→501-511、OkHttpApiClient.kt:456-465→456-463）は現版で修正
  new_candidates: [N1（速度保存値の Double 完全一致比較による silent value-loss）, N2（範囲外週目標の無言表示）, N3（throwing default はクラッシュ）]
```

router が反映した差分（初版 → 現版）:

| 対象 | 初版 | 現版 | 理由 |
|---|---|---|---|
| RF1 | severity を一括で「P8 成立なら major」 | (a)(c)(d) = 機密性・P8 条件付き major、(b) = 可用性・無条件 major に分離 | (b)「セッション中 401 が反映されない」は端末共有と無関係 |
| RF2 | §4.1（QL4）に配置。`ApiClient.kt:66-67` の throwing default を Evidence に含む | §4.2 へ移動（実害は UX）。throwing default は `catch (e: ApiException)` に捕まらず UI 文言化経路が無い上、9/9 が override 済みで production 到達しないため Evidence から除外 | 例外階層の照合 |
| RF9 | 「Screen 直書き 4 箇所が検証を迂回」を経路に数えた | 4 箇所は enum / Boolean で型が全域。不正値の構築経路は server 同期 1 本＋DataStore 永続化に限定。4 箇所は owner 分散として RF8 へ移管。N2 を追記 | 型の全域性 |
| RF5 | — | N1（設定画面の Double 完全一致比較で 1.75 等が 1.0x 表示）を追記 | 新規 Evidence |
| RF7 | — | N3（default 到達時はクラッシュ）を required_action に追記 | 失敗モードの訂正 |
| RF8 | 規模（13 StateFlow・1362 行）を並記 | 規模は症状であり根拠は CS4 fail と明記。`PodcastViewModel` 分割は RF3 の後（RO6）と明記 | RC6 / RC2 conditional の解消 |
| RF11 | 行番号なし | `ci.yml:34-35,37-47` | RC1 軽微指摘 |
| RF3 / RF6 | `:502-512` / `:456-465` | `:501-511` / `:456-463` | drift |

削除した finding: なし。severity 変更: RF2 は minor のまま配置のみ変更。

canonical decision への影響: `status: pass` を維持（訂正は finding の機構・Evidence・配置であり gate の失敗ではない）。ただし RF15・RF17 は独立評価が未到達（UNVERIFIABLE）で router の自己確認のみ。RF4 / RF5 / RF12 は「コード上の欠落は確定だが実機観測（UV3）は未実行」の区別を保持する。


## 8. 人間判断の結果（2026-09-16・dig-me セッション、owner: user）

Selection Gate と finding の採否を、ユーザーとの一問一答（Q1〜Q13、1 ターン 1 問・推奨付き）で確定した。AI 復唱ではなく user の回答が Evidence。

### 8.1 前提（user 回答）

- 利用形態は web と同じ: 本人＋少数の知人が自分の端末だけで使う。端末・アカウントの共有はない。次サイクルの変更予定は再生領域（キュー・オフライン・完聴・レジューム）（Q1）。→ P8 confirmed、RF1 (a)(c)(d) は major のまま。

### 8.2 Selection Gate の状態

```yaml
selection_gates_resolution:
  - {id: SG-R1, status: satisfied, decision: "『現在再生中』の正本 = queue.current（共有仕様 §2.1 不変条件 4）。currentPodcast は queue からの派生値。『停止』は再生セッション状態（SG-R7 の union）で表し、queue の currentIndex は Q-18 どおり末尾に残す。署名 URL 再取得後の fresh DTO は再生セッションが保持（web の INV-P1 と同構造）", evidence: [Q3]}
  - {id: SG-R2, status: satisfied, decision: "速度は 2 概念（既定=永続・server 同期 / セッション=非永続）。エピソード開始（beginPlayback）ごとに既定速度で初期化し、以後はセッション内で保持", evidence: [Q4]}
  - {id: SG-R3, status: satisfied, decision: "選択肢は 8 段（PlaybackConstants.speeds 0.5〜2.5）に統一。設定画面も 8 段を表示し、Double 完全一致比較の value-loss（N1）を解消", evidence: [Q4]}
  - {id: SG-R4, status: satisfied, decision: "resume を実装する。iOS の規則（保存位置 > 0 かつ末尾 2 秒以内でなければ seek、末尾なら先頭から）を core/ の純粋関数にし、共有仕様 §6.2 に完聴境界（2 秒窓）を 3 platform 共通規則として追記する", evidence: [Q2, "ios/.../PodcastViewModel.swift:296-307"]}
  - {id: SG-R5, status: satisfied, decision: "失効経路にも現状の cleanup（音声キャッシュ＋FCM トークン）を適用し、logout・失効の両方で再生状態（queue 空・player 停止・現在再生中なし）を消す。preferences 8 key は端末設定として消さない（server 同期で次ログイン時に上書きされる）", evidence: [Q7, Q1]}
  - {id: SG-R6, status: satisfied, decision: "ApiException に Unauthorized を追加。refreshAuth は Unauthorized のみトークン破棄、それ以外（NetworkError 等）はトークン保持のままエラー表示＋手動再試行（Unknown を判定保留として再利用）。セッション中の 401 は AuthInterceptor で検出し、関数注入 onUnauthorized 経由で AuthViewModel へ通知（依存方向を逆流させない）。既存テスト AuthViewModelTest.kt:95 は仕様変更として反転", evidence: [Q6]}
  - {id: SG-R7, status: satisfied, decision: "PlayerController に再生状態 union（idle / loading / playing / paused / ended / error(reason)）を追加し isPlaying は派生値。onPlayerError を購読。自動次再生の失敗は停止＋失敗エピソードを current に保持、手動 play で再試行。オフラインは『オフライン』文言", evidence: [Q5]}
  - {id: SG-R8, status: satisfied, decision: "test 側に基底 Fake 1 つ（全メソッド error）を置き各 Fake は継承して必要分だけ override。production interface の throwing default 9 箇所は削除。再生系だけ狭い port PodcastApi（fetchPodcast / fetchPodcasts / updatePlaybackPosition / markCompleted / downloadAudio）を LearningApi 方式で切り出し PodcastViewModel はそれに依存。44 分割（RO4）は不採用", evidence: [Q8]}
  - {id: SG-R9, status: satisfied, decision: "一時停止中の 15 秒 PATCH は現行維持。送信条件を共有仕様 §6.2 に明文化し、backend の streak 依存（U1）を確認してから変更可否を別途判断", evidence: [Q9]}
  - {id: SG-R10, status: satisfied, decision: "PlaybackQueue の init で不変条件 1〜3 を検査し違反は require で throw（programmer error）。公開操作は §2 どおり正規化。Q-01〜Q-32 不変（web と同じ意味論）", evidence: [Q10]}
  - {id: SG-R11, status: satisfied, decision: "CI を testDebugUnitTest / lintDebug / assembleDebug の独立ステップに分け、JDK 要件を Gradle toolchain（jvmToolchain(17)）で固定。detekt / ktlint / JaCoCo / Dependabot は今回入れない（学習サイクルで再判断）", evidence: [Q11]}
  - {id: SG-R12, status: satisfied, decision: "パスワード規則は backend の 12 文字（shared/password_policy.py）を正本。android は現状の文言のまま変更なし。web の 8〜20 文字決定（web review SG7）は backend 実値に合わせて差し戻し（web review §8 へ記録が必要）", evidence: [Q12, "backend/shared/password_policy.py:23"]}
```

### 8.3 finding の採否と着手順（Q13 で確定）

| 順 | 対象 | 決定 |
|---|---|---|
| 1 | RF1 (a)(b)(d) / SG-R6 | Unauthorized 型、失効のみトークン破棄、AuthInterceptor で 401 検出→関数注入で通知、既存テスト 1 件を反転 |
| 1 | RF1 (c) / SG-R5 | 失効経路にも cleanup（音声＋FCM）、logout・失効の両方で再生状態を停止・空に。preferences は消さない |
| 2 | RF7 / SG-R8 | 基底 Fake 1 つ＋throwing default 削除、再生系だけ狭い port PodcastApi |
| 3 | RF3 / RF12 / SG-R1・R7 | Queue 正本＋再生セッション状態 union、onPlayerError 購読、失敗時停止＋手動再試行 |
| 3 | RF4 / SG-R4 | resume を core/ の純粋関数で実装（末尾 2 秒窓）、共有仕様 §6.2 に閾値追記 |
| 3 | RF5 / SG-R2・R3 | 既定速度を開始ごとに適用、選択肢 8 段へ統一（N1 解消） |
| 3 | RF14 / SG-R10 | PlaybackQueue の init 検査（throw）、Q-* 不変 |
| 3 | RF13・RF15 | 完聴順序の契約化・破損キャッシュの無効化（順 3 の再生作業に同梱） |
| 4 | RF11 / SG-R11 | CI 分割＋JDK toolchain 固定 |
| 保留 | RF6（意味型の全面導入）・RF8・RF9・RF10・RF2 | 学習機能・設定を触るサイクルまで保留。ただし Unauthorized 追加（Q6）は RF6 の第一歩として順 1 に含める |
| 記録のみ | RF16・RF17・RF18 | RF18 は web 側へ差し戻し記録。SG-R9 は現行維持を spec に明文化 |

### 8.4 残存する仮定・未決

- U1（backend の streak 集計が位置 PATCH に依存するか）: SG-R9 の将来変更の前提。今サイクルでは現行維持なので blocker ではない。
- U2（markCompleted の backend 冪等性）: 順 3 の完聴順序契約（RF13）で「client 側は 1 セッション 1 回に抑制、backend first-write-wins 依存はコメントとテストに明記」とする（web と同じ扱い）。
- 共有仕様 §6.2（完聴境界 2 秒窓・位置同期の送信条件）と §6.3（Android 行）の改訂は本レビューの成果物外。順 3 着手前に spec を先に更新する（ADR-053 の「spec が正本」原則）。
- web review §8 への SG7 差し戻し記録は web 側セッションの作業。

### 8.5 Implementation Spec の承認（2026-09-16、Q14〜Q20）

`docs/design/2026-09-16-implementation-spec-playback-auth.md` を P9 事前実装ゲート（`2026-09-16-code-design-review/spec-gate.md`、verdict revise・必須 10 件反映）の後に user が承認。Spec 固有の判断: SG-R13（`Unknown` + `lastFailure`、4 状態化しない）、SG-R14（完聴時は duration を送る）、SG-R15（S2 一括切替）、SG-R16（RF16 を S0 に含める＝§8.3「記録のみ」からの逸脱を採用）、SG-R17（共有仕様は新節 §6.4/§6.5 として起票し 3 platform 合意を S2 の前提に）、SG-R18（`onLogoutCleanup` → `onSubjectLeave`）。次フェーズ: 共有仕様新節の先行 PR → S0 実装。

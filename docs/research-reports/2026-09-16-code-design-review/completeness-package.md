# Completeness Package — news-listen/android (S1–S4)

役割: `architecture` / Skill: `mino-domain-model-completeness` / mode: review (read-only, mutation なし)。

パス規約: 断りのない `path:line` は `/Users/rio/git/news-listen/android/app/src/main/java/com/rioikeda/newslisten/` 相対。
`spec:N` は `/Users/rio/git/news-listen/docs/design/shared-playback-spec.md` の行。
行番号は本セッションで 1 ファイル 1 `grep -Hn` / `sed -n` により実ファイルを読んで確認した。
`evidence.status: confirmed_by_router` は共通ブリーフ §4・§5（router が実コードで確認済みと明記した観測）を出典とし、本セッションで再読していない引用を示す。自分で再読した引用は `confirmed`。

## 0. routing_context

```yaml
routing_context:
  origin: integrated
  mode: review
  orchestrator: main session (router = mino-reproducible-development)
  requested_by: router
  requested_artifact: completeness_package
  return_to: router
  mutation_authorized: false
  re_routing: forbidden
```

## 1. scope / discovery_readiness

```yaml
scope:
  actors:
    - end_user                # 再生・認証・設定を行う利用者
    - admin_user              # role == "admin"。RSS ソース編集のみ差分（S3 の 403 解釈が不在）
    - backend_server          # preferences・playback_position・quota・session の外部 authority
    - os_media_stack          # Media3 の audio focus / becoming-noisy / MediaSessionService（利用者操作なしに再生状態を変える自律 writer）
    - developer_tester        # Fake を注入するテスト作者（9 Fake / 1,393 行。ブリーフ §4）
  use_cases:
    - id: S1
      name: playback session
      flow: "一覧 → 再生可否ゲート → source 解決(cached/network/unavailable) → prepare/play → 位置 15 秒同期 → 完聴 → markCompleted → queue.advance → 次再生 / 停止"
      in_scope_files:
        - podcast/PodcastViewModel.kt
        - podcast/ExoPlayerController.kt
        - podcast/PlayerController.kt
        - podcast/PlaybackConstants.kt
        - podcast/PodcastStatusBadge.kt
        - core/PlaybackQueue.kt
        - core/PlaybackSourceResolver.kt
        - network/AudioCacheManager.kt
        - model/PodcastResponse.kt
    - id: S2
      name: auth session
      flow: "起動 → sessionStore.load → /auth/me → Authenticated → preferences 同期 → onAuthenticated → (logout | 失効) → cleanup"
      in_scope_files:
        - auth/AuthState.kt
        - auth/AuthViewModel.kt
        - network/SessionStore.kt
        - network/KeystoreSessionStore.kt
        - di/AppContainer.kt
    - id: S3
      name: failure meaning (消費者が受け取る失敗の語彙)
      flow: "HTTP 応答 → validateResponse → ApiException → 各 consumer が code / message を再解釈 → UI 文言・graceful degradation・冪等成功判定"
      in_scope_files:
        - network/ApiException.kt
        - network/OkHttpApiClient.kt
        - feed/FeedViewModel.kt
        - podcast/PodcastViewModel.kt
        - settings/SettingsViewModel.kt
    - id: S4
      name: preferences and UiState
      flow: "DataStore 復元 → 既定値 → server 同期(server-wins) / UI 変更 → 永続化 → 各 ViewModel/Screen が購読"
      in_scope_files:
        - preferences/PreferencesStore.kt
        - preferences/DataStorePreferencesStore.kt
        - settings/SettingsViewModel.kt
        - learning/LearningViewModel.kt
        - feed/FeedViewModel.kt
  requirements: [R1, R2, R3, R4, R5, R6, R7, R8, R9]
  contexts: [C1, C2, C3, C4, C5, C6]
  out_of_scope:
    - reason: "ブリーフで再検証対象外と明示。spec §2 Q-01〜Q-32 / §3 RT-01〜RT-15+RT-A01/A02 は全 ID が conformance test に存在済み（router 機械照合済み）"
      items: ["core/RelativeTimeFormatter.kt", "PlaybackQueueConformanceTest の Q-* 網羅性", "RelativeTimeConformanceTest の RT-* 網羅性"]
    - reason: "scope 外の subject。S1〜S4 の判断を分岐させない"
      items: ["passkey/**", "onboarding/**", "observability/**", "engagement/** の streak 算出", "notification/**", "quiz 採点ロジック"]
    - reason: "本 Skill の authority 外（system-wide な target architecture / data authority の決定）。Architecture Strategy Package 側の obligation"
      items: ["podcast ⇄ playbackservice の相互参照解消方針", "ApiClient 44 メソッドの分割方針", "CI ゲート構成"]

discovery_readiness:
  status: discovery_required
  evidence:
    status: confirmed
    sources:
      - "podcast/PodcastViewModel.kt:83-86,115-121（「現在再生中」が 2 系統）"
      - "settings/SettingsViewModel.kt:158-160 と network/OkHttpApiClient.kt:299-305（404 の意味が層ごとに異なる）"
      - "auth/AuthViewModel.kt:111-114（「失敗」が失効と一時障害を区別しない）"
  reason: >
    結果を分岐させる用語（現在再生中 / 404 / 失敗 / 再生中）が context ごとに別意味で同一 symbol へ押し込まれており、
    既存の class 名（AuthState・ApiException・PlaybackQueue）を業務上の意味として確定できない。
    term ledger と context 境界を先に作らないと、gap を「命名の好み」へ誤縮約する危険がある。
```

## 2. domain_discovery（canonical Domain Discovery Package）

```yaml
domain_discovery:
  applicability: required
  not_applicable_reason: ""
  confirmation_method: ""
  impact_if_unresolved: ""
  evidence:
    status: confirmed
    sources: ["本 package §1 discovery_readiness.evidence と同一"]
  package:
    contexts:
      - id: C1
        purpose: "再生セッション（何を・どこから・どの速度で・どこまで聴いたか）を保持し操作する"
        language_term_ids: [TERM1, TERM2, TERM3, TERM4, TERM5]
        owned_rules:
          - "再生可否は生成ステータスで決まる（podcast/PodcastViewModel.kt:549-554）"
          - "source はキャッシュ優先（core/PlaybackSourceResolver.kt:16-21・spec:284-296）"
        owned_data: ["_currentPodcast", "_queue", "PlayerController の 4 StateFlow"]
        out_of_scope: ["音声の実デコード（Media3 内部）", "通知 UI の描画"]
      - id: C2
        purpose: "端末ローカルの音声キャッシュを所有し、オフライン再生とダウンロード状態を成立させる"
        language_term_ids: [TERM6, TERM7]
        owned_rules:
          - "id は [A-Za-z0-9_-] のみ許可（network/AudioCacheManager.kt:47,89-93）"
        owned_data: ["{baseDir}/audio/{id}.mp3"]
        out_of_scope: ["署名付き URL の取得", "音声の内容妥当性（現状 owner 不在 = G17）"]
      - id: C3
        purpose: "セッション（利用者が誰であるか）の確立・維持・破棄"
        language_term_ids: [TERM8, TERM9, TERM10]
        owned_rules:
          - "Authenticated には必ず user が伴う（auth/AuthState.kt:9-11,20-21）"
          - "server 失効に失敗してもローカル状態は必ず落とす（auth/AuthViewModel.kt:164-165）"
        owned_data: ["AuthState", "暗号化済みトークン（network/KeystoreSessionStore.kt:30,117）"]
        out_of_scope: ["passkey セレモニー（auth/AuthViewModel.kt:184-193 で明示的に他層へ委譲）"]
      - id: C4
        purpose: "HTTP transport の結果を、消費者が判断に使える失敗の意味へ翻訳する"
        language_term_ids: [TERM11, TERM12, TERM13]
        owned_rules:
          - "429 は RateLimited、それ以外の非 2xx は HttpError(code)（network/OkHttpApiClient.kt:456-465）"
        owned_data: ["ApiException の 4 case（network/ApiException.kt:8-20）"]
        out_of_scope: ["各 code の業務意味（現状 owner 不在 = G10/G11）"]
      - id: C5
        purpose: "利用者の設定選択値を所有し、端末再起動と server 同期をまたいで一意に保つ"
        language_term_ids: [TERM14, TERM15]
        owned_rules:
          - "値の正本は store に一本化し、呼び出し元は独自 StateFlow を持たない（preferences/PreferencesStore.kt:13-15）"
          - "難易度・再生速度・週目標のみ server 同期、他はローカル専用（preferences/PreferencesStore.kt:9-12）"
        owned_data: ["DataStore 8 key（preferences/DataStorePreferencesStore.kt:120-127）"]
        out_of_scope: ["値域の妥当性（現状 owner 不在 = G14）"]
      - id: C6
        purpose: "画面が排他的に描画できる形で、読み込み・成功・失敗を表現する"
        language_term_ids: [TERM16]
        owned_rules: []
        owned_data: ["LearningUiState（learning/LearningViewModel.kt:15-23）", "各 ViewModel の isLoading / errorMessage / *LoadFailed"]
        out_of_scope: ["Compose の描画実装"]
    terms:
      - id: TERM1
        name: 現在再生中の Podcast
        context_id: C1
        actors: [end_user]
        purpose: "プレイヤー UI に何を表示し、位置同期・完聴記録をどの id に対して行うか決める"
        meaning: "PlayerController に実際に prepare/play されたエピソード"
        examples: ["beginPlayback が代入した値（podcast/PodcastViewModel.kt:325）"]
        counterexamples: ["キュー上の選択位置", "一覧で選択された行"]
        rules: ["null は「再生セッションなし」"]
        related_meaning_term_ids: [TERM2, TERM3]
        evidence: ["podcast/PodcastViewModel.kt:83-86,325,510"]
      - id: TERM2
        name: キューの現在位置
        context_id: C1
        actors: [end_user]
        purpose: "次に何を再生するか、待機列に何が残っているかを決める"
        meaning: "PlaybackQueue.currentIndex が指す要素"
        examples: ["advance() 後の current（core/PlaybackQueue.kt:84-91）"]
        counterexamples: ["実際に player が鳴らしているエピソード"]
        rules: ["currentIndex が null または範囲外なら current は null（core/PlaybackQueue.kt:27-29）"]
        related_meaning_term_ids: [TERM1]
        evidence: ["core/PlaybackQueue.kt:23-33", "podcast/PodcastViewModel.kt:115-121"]
      - id: TERM3
        name: 行 UI の「再生中」
        context_id: C6
        actors: [end_user]
        purpose: "一覧のどの行を強調するか決める"
        meaning: "currentPodcast?.id == podcast.id（= 選択中。一時停止でも真）"
        examples: []
        counterexamples: ["PlayerController.isPlaying == true"]
        rules: []
        related_meaning_term_ids: [TERM1, TERM4]
        evidence: ["podcast/PodcastScreen.kt:160（confirmed_by_router・ブリーフ §5）"]
      - id: TERM4
        name: 再生状態
        context_id: C1
        actors: [end_user, os_media_stack]
        purpose: "再生/一時停止ボタンの表示と、失敗の告知可否を決める"
        meaning: "現状は isPlaying: Boolean 1 本のみ。準備中・終了・失敗は表現できない"
        examples: ["ExoPlayerController が false を書く 3 経路（:89 STATE_ENDED / :95 STATE_IDLE / :105 onIsPlayingChanged）"]
        counterexamples: []
        rules: []
        related_meaning_term_ids: [TERM5]
        evidence: ["podcast/PlayerController.kt:26", "podcast/ExoPlayerController.kt:55-56,87-98,104-112"]
      - id: TERM5
        name: 再生位置
        context_id: C1
        actors: [end_user, backend_server]
        purpose: "続きから聴く / 進捗表示 / ストリーク集計の起点"
        meaning: "ローカルでは ExoPlayer の currentPosition、server では playback_position_seconds"
        examples: ["model/PodcastResponse.kt:31（server 値・デコードのみ）"]
        counterexamples: []
        rules: ["spec:298-304 は server-wins で resume 時に調整すると定める"]
        related_meaning_term_ids: [TERM4]
        evidence: ["podcast/ExoPlayerController.kt:214-225", "model/PodcastResponse.kt:31", "spec:298-304"]
      - id: TERM6
        name: ダウンロード済み
        context_id: C2
        actors: [end_user]
        purpose: "オフラインで再生できるか、削除できるかを示す"
        meaning: "{baseDir}/audio/{id}.mp3 がファイルとして存在する"
        examples: ["network/AudioCacheManager.kt:65"]
        counterexamples: ["再生可能な音声として妥当である（内容は未検証）"]
        rules: []
        related_meaning_term_ids: [TERM7]
        evidence: ["network/AudioCacheManager.kt:54-62,65-69"]
      - id: TERM7
        name: 再生元
        context_id: C2
        actors: [end_user]
        purpose: "署名 URL の再取得が必要か、オフラインで再生できるかを決める"
        meaning: "CACHED | NETWORK | UNAVAILABLE"
        examples: ["core/PlaybackSourceResolver.kt:16-21"]
        counterexamples: []
        rules: ["キャッシュ有は常に CACHED（spec:288-292）"]
        related_meaning_term_ids: [TERM6]
        evidence: ["core/PlaybackSourceResolver.kt:6-21", "spec:284-296"]
      - id: TERM8
        name: セッション失効
        context_id: C3
        actors: [backend_server]
        purpose: "保存トークンと認証済み UI を残さないことを決める"
        meaning: "server がトークンを無効と判定した（/auth/me が 401、または利用中に 401）"
        examples: []
        counterexamples: ["通信不可でトークンの有効性が判定できない"]
        rules: ["R5: 一時障害で失効扱いにしない"]
        related_meaning_term_ids: [TERM9]
        evidence: ["auth/AuthViewModel.kt:96-97,111-114"]
      - id: TERM9
        name: 一時的な認証判定不能
        context_id: C3
        actors: [end_user]
        purpose: "トークンを保持したまま再試行できる状態を表す"
        meaning: "NetworkError / DecodingError / 5xx により /auth/me の結果が得られない"
        examples: []
        counterexamples: ["401"]
        rules: []
        related_meaning_term_ids: [TERM8]
        evidence: ["network/ApiException.kt:16,19", "auth/AuthViewModel.kt:111-114"]
      - id: TERM10
        name: 主体が離れる遷移
        context_id: C3
        actors: [end_user]
        purpose: "共有端末で前利用者のデータが残らないことを保証する（spec:305-317）"
        meaning: "logout と失効の両方"
        examples: ["auth/AuthViewModel.kt:167-182（logout のみ cleanup を呼ぶ）"]
        counterexamples: []
        rules: ["R6"]
        related_meaning_term_ids: [TERM8]
        evidence: ["auth/AuthViewModel.kt:167-182", "spec:305-317"]
      - id: TERM11
        name: 機能未提供（404 の意味 A）
        context_id: C4
        actors: [end_user]
        purpose: "セクションを非表示にして機能を劣化させる"
        meaning: "server がこの機能の endpoint を持たない"
        examples: ["settings/SettingsViewModel.kt:158-160"]
        counterexamples: ["対象リソースが消えた"]
        rules: []
        related_meaning_term_ids: [TERM12, TERM13]
        evidence: ["settings/SettingsViewModel.kt:150-164"]
      - id: TERM12
        name: 冪等成功（404 の意味 B）
        context_id: C4
        actors: [end_user]
        purpose: "連打を成功として扱う"
        meaning: "既に失効済みのセッションを再度 revoke した"
        examples: ["network/OkHttpApiClient.kt:299-305"]
        counterexamples: ["機能未提供"]
        rules: []
        related_meaning_term_ids: [TERM11]
        evidence: ["network/OkHttpApiClient.kt:295-305"]
      - id: TERM13
        name: リソース消失（404 の意味 C）
        context_id: C4
        actors: [end_user]
        purpose: "「設問が消えた」と UI へ告知する"
        meaning: "対象が server 側から削除された"
        examples: ["podcast/QuizSheet.kt:194-201（confirmed_by_router・Screen 層）"]
        counterexamples: ["機能未提供", "冪等成功"]
        rules: []
        related_meaning_term_ids: [TERM11, TERM12]
        evidence: ["ブリーフ §5（router が QuizSheet.kt:194-201 を確認済み）"]
      - id: TERM14
        name: 既定の再生速度
        context_id: C5
        actors: [end_user, backend_server]
        purpose: "次の再生を利用者の好みの速度で始める"
        meaning: "server 同期される Double 値"
        examples: ["preferences/DataStorePreferencesStore.kt:46-49"]
        counterexamples: ["セッション中の速度（PlayerController.playbackSpeed）"]
        rules: ["現状この値が player へ適用される経路は無い（= G4）"]
        related_meaning_term_ids: [TERM15]
        evidence: ["preferences/PreferencesStore.kt:21-22", "podcast/ExoPlayerController.kt:64-65,169-174"]
      - id: TERM15
        name: セッション中の再生速度
        context_id: C1
        actors: [end_user]
        purpose: "今聴いているエピソードの速度を変える"
        meaning: "ExoPlayer の playbackSpeed。既定 1.0f"
        examples: ["podcast/ExoPlayerController.kt:64-65"]
        counterexamples: []
        rules: []
        related_meaning_term_ids: [TERM14]
        evidence: ["podcast/PlayerController.kt:34-35", "podcast/PodcastViewModel.kt:464-467"]
      - id: TERM16
        name: 画面状態
        context_id: C6
        actors: [end_user]
        purpose: "読み込み中・成功・失敗を排他的に描画する"
        meaning: "現状は独立 Boolean と nullable の直積（LearningUiState）"
        examples: ["learning/LearningViewModel.kt:15-23"]
        counterexamples: ["VocabularyTestUiState.phase の sealed 状態機械（confirmed_by_router）"]
        rules: []
        related_meaning_term_ids: []
        evidence: ["learning/LearningViewModel.kt:15-23,33-63"]
    ambiguous_terms:
      - id: AMB1
        surface_form: "現在再生中 / current"
        meaning_term_ids: [TERM1, TERM2, TERM3]
        risk: "3 つの意味が同じ語で呼ばれ、片方だけ更新される状態（IV1）を「不整合」と呼ぶしかなくなる。UI は一時停止でも「再生中」と表示する"
        evidence: ["podcast/PodcastViewModel.kt:341-346（doc 自身が不整合を review 指摘として自認）"]
      - id: AMB2
        surface_form: "404"
        meaning_term_ids: [TERM11, TERM12, TERM13]
        risk: "同一 transport 値に 3 業務意味が乗り、解釈が 4 箇所（うち 1 つは Screen）へ分散する"
        evidence: ["settings/SettingsViewModel.kt:158-160", "network/OkHttpApiClient.kt:299-305", "ブリーフ §5"]
      - id: AMB3
        surface_form: "認証失敗"
        meaning_term_ids: [TERM8, TERM9]
        risk: "一時障害でトークンが破棄され、オフライン起動で強制再ログインになる（R5 違反）"
        evidence: ["auth/AuthViewModel.kt:111-114"]
      - id: AMB4
        surface_form: "再生速度"
        meaning_term_ids: [TERM14, TERM15]
        risk: "既定値の保存・同期は動くが再生へ効かず、利用者は「設定が無効」と観測する"
        evidence: ["preferences/DataStorePreferencesStore.kt:46-49,85-87", "podcast/ExoPlayerController.kt:169-174"]
    translations:
      - id: TR1
        source_context_id: C4
        target_context_id: C1
        source_term_id: TERM11
        target_term_id: TERM4
        mapping: "現状 translation が存在しない。HttpError.message（\"HTTP Error 500\"）がそのまま UI 文言になる"
        evidence: ["network/ApiException.kt:13", "podcast/PodcastViewModel.kt:135,212,214,251,309"]
      - id: TR2
        source_context_id: C5
        target_context_id: C1
        source_term_id: TERM14
        target_term_id: TERM15
        mapping: "現状 translation が存在しない（既定速度 → セッション速度の写像が未配線）"
        evidence: ["podcast/PodcastViewModel.kt:464-467（setSpeed は UI 由来のみ）"]
      - id: TR3
        source_context_id: C3
        target_context_id: C2
        source_term_id: TERM10
        target_term_id: TERM6
        mapping: "logout 経路のみ onLogoutCleanup 経由で音声キャッシュ削除へ写像（di/AppContainer.kt:266-281）。失効経路は未写像"
        evidence: ["auth/AuthViewModel.kt:167-182", "di/AppContainer.kt:266-281（confirmed: 本セッションで 257-283 を読了）"]
    relationships:
      - id: REL1
        upstream_context_id: C3
        downstream_context_id: C2
        exchanged_fact: "主体が離れた（logout / 失効）"
        fact_owner: C3
        integration: other
        translation_ids: [TR3]
        consistency: "eventual・best-effort（cleanup 失敗でも未認証へ遷移する。spec:339 相当の方針）"
        evidence:
          status: confirmed
          sources: ["auth/AuthViewModel.kt:173-181", "di/AppContainer.kt:266-281"]
        failure_semantics:
          failure_owner:
            status: identified
            owner: C3（AuthViewModel が Exception を飲み込み logout を完了させる）
            rationale: "auth/AuthViewModel.kt:175-178 が汎用 Exception で受ける設計を明記"
            evidence: ["auth/AuthViewModel.kt:173-179"]
            confirmation_method: ""
            impact_if_unresolved: ""
          retry:
            applicability: not_applicable
            policy_or_reason: "cleanup は再試行されない。失敗は観測も記録もされないため retry の判断材料が無い（= G9 の一部）"
            owner: C3
            evidence: ["auth/AuthViewModel.kt:175-178", "di/AppContainer.kt:267-280"]
            confirmation_method: ""
            impact_if_unresolved: ""
          duplicate:
            applicability: required
            result_or_reason: "removeAll / remove は冪等（network/AudioCacheManager.kt:71-83）なので二重呼び出しは安全"
            owner: C2
            evidence: ["network/AudioCacheManager.kt:71-83"]
            confirmation_method: ""
            impact_if_unresolved: ""
          ambiguous_outcome:
            applicability: unknown
            recovery_or_reason: "cleanup の途中失敗（音声は消えたが FCM 解除は失敗、等）の観測手段が無く、回復手順も定義されていない"
            owner: C3
            evidence: ["di/AppContainer.kt:267-280（各 try/catch が沈黙）"]
            confirmation_method: "logout を FCM 解除だけ失敗させる fake を注入し、残留状態（トークン登録・キャッシュ）を検査する"
            impact_if_unresolved: "R6 の事後条件が部分成立で終わったことを検出できず、共有端末に前利用者の通知経路が残る"
      - id: REL2
        upstream_context_id: C4
        downstream_context_id: C1
        exchanged_fact: "API 呼び出しが失敗した理由"
        fact_owner: C4
        integration: api
        translation_ids: [TR1]
        consistency: "strong（同一呼び出し内で例外として同期的に伝播）"
        evidence:
          status: confirmed
          sources: ["network/OkHttpApiClient.kt:456-465", "podcast/PodcastViewModel.kt:305-310"]
        failure_semantics:
          failure_owner:
            status: unknown
            owner: ""
            rationale: "失敗の意味（再生不能か再試行可能か、利用者へ何を告げるか）を決める owner が C4 に無く、consumer 側 8 箇所が各自で code を解釈している"
            evidence: ["ブリーフ §4 の code 比較 8 箇所", "network/ApiException.kt:8-20"]
            confirmation_method: "ApiException に unauthorized / not_found / conflict / server / forbidden を追加し、code 比較が 0 箇所になるかを grep で検査する"
            impact_if_unresolved: "R1/R4 の違反判定が consumer 単位になり、新 consumer 追加ごとに解釈が増殖する"
          retry:
            applicability: required
            policy_or_reason: "RateLimited のみ retryAfterSeconds を持つ（network/ApiException.kt:10）。NetworkError に再試行可否の表現が無い"
            owner: C4
            evidence: ["network/ApiException.kt:10,19"]
            confirmation_method: ""
            impact_if_unresolved: ""
          duplicate:
            applicability: required
            result_or_reason: "409 を「既登録=成功」と読む consumer（onboarding / passkey）が存在するが、C4 に duplicate 概念が無いため consumer 側で再発明されている"
            owner: C4
            evidence: ["ブリーフ §5（OnboardingViewModel.kt:85-87 / PasskeyRegistrationViewModel.kt:54）"]
            confirmation_method: ""
            impact_if_unresolved: ""
          ambiguous_outcome:
            applicability: required
            recovery_or_reason: "markCompleted / updatePlaybackPosition は失敗を握り潰す（podcast/PodcastViewModel.kt:355-357,539-541）ため、server 側で成功したか不明な状態が回復手順なしで残る"
            owner: C1
            evidence: ["podcast/PodcastViewModel.kt:353-357,536-542"]
            confirmation_method: ""
            impact_if_unresolved: ""
      - id: REL3
        upstream_context_id: C5
        downstream_context_id: C1
        exchanged_fact: "既定の再生速度"
        fact_owner: C5
        integration: other
        translation_ids: [TR2]
        consistency: "不成立（写像が存在しない）"
        evidence:
          status: confirmed
          sources: ["preferences/PreferencesStore.kt:21-22", "podcast/PodcastViewModel.kt:464-467"]
        failure_semantics:
          failure_owner:
            status: not_applicable
            owner: ""
            rationale: "関係自体が未配線でありランタイム失敗が発生しない。欠落は G4 として扱う"
            evidence: ["podcast/PodcastViewModel.kt:324-331（beginPlayback に setSpeed が無い）"]
            confirmation_method: ""
            impact_if_unresolved: ""
          retry:
            applicability: not_applicable
            policy_or_reason: "同上（未配線）"
            owner: ""
            evidence: ["podcast/PodcastViewModel.kt:324-331"]
            confirmation_method: ""
            impact_if_unresolved: ""
          duplicate:
            applicability: not_applicable
            policy_or_reason: ""
            result_or_reason: "同上（未配線）"
            owner: ""
            evidence: ["podcast/PodcastViewModel.kt:324-331"]
            confirmation_method: ""
            impact_if_unresolved: ""
          ambiguous_outcome:
            applicability: not_applicable
            recovery_or_reason: "同上（未配線）"
            owner: ""
            evidence: ["podcast/PodcastViewModel.kt:324-331"]
            confirmation_method: ""
            impact_if_unresolved: ""
      - id: REL4
        upstream_context_id: C1
        downstream_context_id: C5
        exchanged_fact: "server 上の再生位置（resume 用）"
        fact_owner: backend_server
        integration: api
        translation_ids: []
        consistency: "unknown（spec:298-304 は server-wins を定めるが client 側に resolve 関数が無い）"
        evidence:
          status: confirmed
          sources: ["model/PodcastResponse.kt:31", "podcast/PodcastViewModel.kt:324-331,536-542", "spec:298-304"]
        failure_semantics:
          failure_owner:
            status: unknown
            owner: ""
            rationale: "server 値とローカル値が食い違ったときの解決 owner が client 実装に存在しない"
            evidence: ["podcast/PodcastViewModel.kt:324-331"]
            confirmation_method: "backend の GET /podcasts/{id} と ADR-022 を読み、server-wins が backend 側で実装済みかを確認する"
            impact_if_unresolved: "G3 の契約（server-wins / local-wins / max）が決まらず、resume 実装が任意解釈になる"
          retry:
            applicability: unknown
            policy_or_reason: "位置同期の失敗は沈黙し、再試行は 15 秒後の次周期に暗黙依存する。業務上それで十分かの判断材料が無い"
            owner: C1
            evidence: ["podcast/PodcastViewModel.kt:536-542"]
            confirmation_method: "backend の位置更新が last-write-wins かを確認する"
            impact_if_unresolved: "オフライン復帰時に古い位置で上書きされうる"
          duplicate:
            applicability: required
            result_or_reason: "同一位置の重複 PATCH は無害（更新のみ）。ただし一時停止中も送り続ける（podcast/PodcastViewModel.kt:514-533）"
            owner: backend_server
            evidence: ["podcast/PodcastViewModel.kt:514-533"]
            confirmation_method: ""
            impact_if_unresolved: ""
          ambiguous_outcome:
            applicability: required
            recovery_or_reason: "最終同期（stopInternal:505）が失敗した場合、server 位置が古いまま resume されるが回復手順が無い"
            owner: C1
            evidence: ["podcast/PodcastViewModel.kt:502-512"]
            confirmation_method: ""
            impact_if_unresolved: ""
    invisible_concepts:
      - id: INV1
        name: 再生セッション（PlaybackSession）
        chain: "end_user → 続きから聴き続けたい → 状態が 4 つの StateFlow と queue に分散し整合を手続きで守っている → セッションを 1 概念として保持する必要 → 現行 symbol 無し"
        required_information: ["episode", "source", "position", "speed", "player state", "last sync time"]
        behavior: ["start", "pause/resume", "seek", "complete", "fail"]
        invariant: ["episode == queue.current", "position ∈ [0, duration]"]
        lifecycle: "start → (playing|paused) → (ended|failed|stopped)"
        out_of_scope: ["通知の描画", "audio focus の取得"]
        mapped_symbols: ["_currentPodcast", "_queue", "PlayerController.isPlaying/positionSeconds/durationSeconds/playbackSpeed"]
        unmapped_symbols_disposition:
          - symbol: "syncJob / playMutex"
            disposition: technical_concern
          - symbol: "downloadJobs / _downloadingIds / _registeredVocabularyKeys"
            disposition: other_purpose（C2 とは別の語彙 context。同一 class に同居しているだけ）
        evidence: ["podcast/PodcastViewModel.kt:62-121,272-331"]
      - id: INV2
        name: セッション有効性の判定結果（SessionVerdict）
        chain: "end_user → オフラインでもログイン済みで使いたい → 一時障害でトークンが破棄される → 「失効」と「判定不能」を区別する必要 → 現行 symbol 無し"
        required_information: ["verdict: valid | revoked | indeterminate", "根拠となった失敗の意味"]
        behavior: ["verify()"]
        invariant: ["revoked のときだけトークンを破棄する"]
        lifecycle: "起動時・401 受信時に評価"
        out_of_scope: ["トークンの暗号化方式"]
        mapped_symbols: ["AuthState.Unknown（起動直後のみ。判定不能を表現していない）"]
        unmapped_symbols_disposition:
          - symbol: "AuthState.Unauthenticated"
            disposition: other_purpose（「未ログイン」と「失効」と「判定不能」の 3 意味を吸収している）
        evidence: ["auth/AuthState.kt:13-22", "auth/AuthViewModel.kt:99-115"]
      - id: INV3
        name: 失敗の業務意味（FailureReason）
        chain: "end_user → 何が起きたか分かる文言と再試行可否が欲しい → 消費者が code を再解釈し例外 message を表示 → 意味を 1 箇所で所有する必要 → 現行 symbol 部分的（RateLimited のみ）"
        required_information: ["network | unauthorized | forbidden | rate_limited(retryAfter) | not_found | conflict | server", "利用者向け文言", "再試行可否"]
        behavior: ["from(status, body)"]
        invariant: ["consumer は transport 値を見ない"]
        lifecycle: "1 回の API 呼び出し内"
        out_of_scope: ["文言のローカライズ実装"]
        mapped_symbols: ["ApiException.RateLimited", "ApiException.NetworkError", "ApiException.DecodingError"]
        unmapped_symbols_disposition:
          - symbol: "ApiException.HttpError(code, bodyMessage)"
            disposition: technical_concern（transport 値の素通し。業務意味を持たない）
        evidence: ["network/ApiException.kt:8-20", "network/OkHttpApiClient.kt:456-465"]
      - id: INV4
        name: 設定値の値域（PreferenceValueDomain）
        chain: "end_user → 選んだ設定が効く状態を保ちたい → setter が無検証で server 値も素通し → 値域を型で所有する必要 → 現行 symbol 無し（weeklyGoal のみ ViewModel に散在）"
        required_information: ["difficulty の許容 code 集合", "speed の許容集合", "weeklyGoal の許容集合"]
        behavior: ["parse / reject"]
        invariant: ["store に入る値は常に許容集合内"]
        lifecycle: "書き込みごと"
        out_of_scope: ["UI の選択肢描画"]
        mapped_symbols: ["SettingsViewModel.WEEKLY_GOAL_OPTIONS", "PlaybackConstants.speeds"]
        unmapped_symbols_disposition:
          - symbol: "SettingsScreen の private 速度 5 段 / WEEKLY_GOAL_OPTIONS 重複定義"
            disposition: other_purpose（UI 選択肢として別定義され、値域の正本と乖離している。confirmed_by_router）
        evidence: ["preferences/DataStorePreferencesStore.kt:81-87,105-107", "settings/SettingsViewModel.kt:193-197,237", "podcast/PlaybackConstants.kt:12"]
```

## 3. platform_context / platform_validation

```yaml
platform_context:
  applicability: not_applicable
  rationale: >
    対象は単一 platform（Android）の app module であり、Windows / Linux / macOS の path separator・case sensitivity・
    permission が state や failure を分岐させる access path は scope 内に存在しない。
    file I/O は FileSystem port（network/AudioCacheManager.kt:37-41）を介した Android アプリ内部ストレージのみで、
    cachedFileUri は "file://" + File(cacheDir, "$id.mp3").path を固定的に組む（network/AudioCacheManager.kt:68-69,93）。
  evidence:
    status: confirmed
    sources: ["network/AudioCacheManager.kt:37-41,64-69,89-93"]
  note_on_cross_platform_spec: >
    spec は Web / iOS / Android の 3 実装が共有する「業務意味」の正本であり、OS 互換性の意味での platform ではない。
    3 実装間の意味差分は §9 spec_divergence として扱い、platform_validation とは分離する。

platform_validation:
  required_platforms: []
  executed: []
  unexecuted:
    - id: UV-P1
      reason: "Android 実機 / エミュレータ依存の access path（KeystoreSessionStore・ExoPlayerController・DataStore 実体）は本 review（read-only・JVM unit test 範囲外）では実行していない"
      required_runner: "connectedAndroidTest または Robolectric（現状 androidTest ディレクトリ自体が無い。confirmed_by_router）"
      planned_commands: ["./gradlew connectedDebugAndroidTest"]
      owner: orchestrator
      evidence: ["network/KeystoreSessionStore.kt:20-22（単体テスト対象外と自認）", "ブリーフ §5（androidTest ディレクトリ無し）"]
```

## 4. audit_rubric / model_elements

```yaml
audit_rubric:
  name: suite_defined_completeness_dimensions
  origin: suite_operationalization
  dimensions: [term_context, concept, constraint, state, transition, behavior, relationship, failure, time, writer, reader, authority]
  note: "公開資料の完全性定義ではなく、本 suite が反復監査のために追加した screening rubric"
```

記法: 1 element = 1 行の flow mapping。`ts` = target_status、`ck` = concept_kind、`ev` = evidence.status、`src` = evidence.sources。

```yaml
model_elements:
  # --- S1 playback session ---
  - {id: ME1,  subject: S1, dimension: term_context, name: "現在再生中（AMB1）", meaning_or_rule: "TERM1/TERM2/TERM3 の 3 意味が同一語", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:83-86,115-121,341-346"]}
  - {id: ME2,  subject: S1, dimension: concept, name: "PlaybackSession（INV1）", meaning_or_rule: "1 セッションを保持する集約", ts: missing, ck: aggregate, ev: confirmed, src: ["podcast/PodcastViewModel.kt:62-121"]}
  - {id: ME3,  subject: S1, dimension: concept, name: "PlaybackSource", meaning_or_rule: "CACHED|NETWORK|UNAVAILABLE", ts: present, ck: value_object, ev: confirmed, src: ["core/PlaybackSourceResolver.kt:6-21"]}
  - {id: ME4,  subject: S1, dimension: concept, name: "PlayabilityPolicy", meaning_or_rule: "生成ステータスで再生可否を決める判断規則", ts: present, ck: policy, ev: confirmed, src: ["podcast/PodcastViewModel.kt:544-554", "podcast/PodcastStatusBadge.kt:22-29"]}
  - {id: ME5,  subject: S1, dimension: concept, name: "ResumePosition（spec §6.2）", meaning_or_rule: "server 値を正本に再生開始位置を決める概念", ts: missing, ck: policy, ev: confirmed, src: ["model/PodcastResponse.kt:31", "podcast/PodcastViewModel.kt:324-331", "spec:298-304"]}
  - {id: ME6,  subject: S1, dimension: state, name: "PlaybackState", meaning_or_rule: "idle|preparing|playing|paused|ended|failed を区別する状態", ts: missing, ck: not_applicable, ev: confirmed, src: ["podcast/PlayerController.kt:26", "podcast/ExoPlayerController.kt:55-56,87-98"]}
  - {id: ME7,  subject: S1, dimension: state, name: "QueueState", meaning_or_rule: "items + currentIndex", ts: present, ck: not_applicable, ev: confirmed, src: ["core/PlaybackQueue.kt:23-33"]}
  - {id: ME8,  subject: S1, dimension: constraint, name: "PlaybackPosition 値域", meaning_or_rule: "0 <= position <= duration", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:444-457,459-462"]}
  - {id: ME9,  subject: S1, dimension: constraint, name: "PlaybackSpeed 値域", meaning_or_rule: "許容集合（8 段）に限る", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["podcast/PlaybackConstants.kt:12", "podcast/PlayerController.kt:63", "ブリーフ §5（SettingsScreen.kt:1361 の 5 段 private 定義）"]}
  - {id: ME10, subject: S1, dimension: constraint, name: "QueueIndex 値域", meaning_or_rule: "currentIndex == null または 0 <= currentIndex < items.size", ts: missing, ck: not_applicable, ev: confirmed, src: ["core/PlaybackQueue.kt:23-29（init 検証なし・public data class）"]}
  - {id: ME11, subject: S1, dimension: transition, name: "完聴 → 次再生", meaning_or_rule: "markCompleted → streak → advance → play(next)", ts: present, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:348-370"]}
  - {id: ME12, subject: S1, dimension: transition, name: "禁止遷移: queue.current と currentPodcast の乖離", meaning_or_rule: "遷移後は必ず一致（または両方 null）", ts: missing, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:361-368,304-311,502-512"]}
  - {id: ME13, subject: S1, dimension: behavior, name: "既定速度の適用", meaning_or_rule: "再生開始時に既定速度を player へ適用する", ts: missing, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:324-331", "preferences/PreferencesStore.kt:21-22"]}
  - {id: ME14, subject: S1, dimension: behavior, name: "source 解決の owner", meaning_or_rule: "core の純関数が所有", ts: present, ck: not_applicable, ev: confirmed, src: ["core/PlaybackSourceResolver.kt:16-21", "podcast/PodcastViewModel.kt:293"]}
  - {id: ME15, subject: S1, dimension: relationship, name: "REL1/REL4 の整合範囲", meaning_or_rule: "C1↔C2（キャッシュ有無で source 決定）と C1↔backend（位置）", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§2 relationships REL1, REL4"]}
  - {id: ME16, subject: S1, dimension: failure, name: "MediaPlaybackFailure", meaning_or_rule: "再生自体の失敗（source 不到達・デコード不能）", ts: missing, ck: not_applicable, ev: confirmed, src: ["podcast/ExoPlayerController.kt:71-115（onPlayerError override なし）"]}
  - {id: ME17, subject: S1, dimension: failure, name: "CorruptedCache", meaning_or_rule: "キャッシュが存在するが再生不能", ts: missing, ck: not_applicable, ev: confirmed, src: ["network/AudioCacheManager.kt:54-62（bytes 無検証）", "core/PlaybackSourceResolver.kt:17-19"]}
  - {id: ME18, subject: S1, dimension: failure, name: "再生開始失敗（NETWORK 経路）", meaning_or_rule: "fetchPodcast 失敗時の状態", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:304-311"]}
  - {id: ME19, subject: S1, dimension: time, name: "位置同期周期の意味", meaning_or_rule: "15 秒周期。一時停止中も送信継続", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:514-533,556-558"]}
  - {id: ME20, subject: S1, dimension: writer, name: "再生状態の writer 群", meaning_or_rule: "AP1〜AP8 参照", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§5 access_paths"]}
  - {id: ME21, subject: S1, dimension: reader, name: "再生状態の reader 群", meaning_or_rule: "AP9〜AP11 参照", ts: present, ck: not_applicable, ev: confirmed_by_router, src: ["ブリーフ §4（AudioPlayerSection.kt:65 / PodcastScreen.kt:61,160,204 / QueueSheet.kt:58,83）"]}
  - {id: ME22, subject: S1, dimension: authority, name: "「現在再生中」の state_authority", meaning_or_rule: "1 つでなければならない", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§6 ownership OW1"]}
  - {id: ME23, subject: S1, dimension: authority, name: "再生位置の source_of_truth", meaning_or_rule: "spec:298-304 は server-wins", ts: unknown, ck: not_applicable, ev: unknown, src: ["§6 ownership OW2", "spec:298-304"]}

  # --- S2 auth session ---
  - {id: ME30, subject: S2, dimension: term_context, name: "認証失敗（AMB3）", meaning_or_rule: "TERM8 失効 と TERM9 判定不能 が同一 catch", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["auth/AuthViewModel.kt:96-97,111-114"]}
  - {id: ME31, subject: S2, dimension: concept, name: "SessionVerdict（INV2）", meaning_or_rule: "valid|revoked|indeterminate の判定結果", ts: missing, ck: value_object, ev: confirmed, src: ["auth/AuthViewModel.kt:99-115"]}
  - {id: ME32, subject: S2, dimension: state, name: "AuthState", meaning_or_rule: "Unknown|Unauthenticated|Authenticated(user)", ts: present, ck: not_applicable, ev: confirmed, src: ["auth/AuthState.kt:13-22"]}
  - {id: ME33, subject: S2, dimension: state, name: "TokenHeld かつ Unverified", meaning_or_rule: "トークンはあるが有効性未確定（オフライン起動）", ts: missing, ck: not_applicable, ev: confirmed, src: ["auth/AuthState.kt:13-22", "auth/AuthViewModel.kt:111-114"]}
  - {id: ME34, subject: S2, dimension: transition, name: "セッション中 401 → Unauthenticated", meaning_or_rule: "利用中に失効したら未認証へ遷移する", ts: missing, ck: not_applicable, ev: confirmed_by_router, src: ["ブリーフ §4（AuthInterceptor.kt:26-39 はヘッダ付与のみ）", "network/ApiException.kt:8-20（unauthorized 型なし）"]}
  - {id: ME35, subject: S2, dimension: transition, name: "logout 遷移", meaning_or_rule: "server 失効 → cleanup → clear → Unauthenticated", ts: present, ck: not_applicable, ev: confirmed, src: ["auth/AuthViewModel.kt:167-182"]}
  - {id: ME36, subject: S2, dimension: behavior, name: "主体離脱の事後条件（R6/spec §6.3）", meaning_or_rule: "ユーザー固有 data（音声・FCM・preferences・再生状態）が消える", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["di/AppContainer.kt:266-281（音声と FCM のみ）", "preferences/DataStorePreferencesStore.kt:109-115（削除 API 無し）", "spec:305-317"]}
  - {id: ME37, subject: S2, dimension: constraint, name: "token と authState の対応", meaning_or_rule: "Authenticated ⟹ 有効な永続トークンが存在する", ts: missing, ck: not_applicable, ev: confirmed, src: ["network/KeystoreSessionStore.kt:40-53", "auth/AuthViewModel.kt:145-150"]}
  - {id: ME38, subject: S2, dimension: failure, name: "トークン保存失敗", meaning_or_rule: "暗号化失敗を呼び出し元へ伝える", ts: missing, ck: not_applicable, ev: confirmed, src: ["network/KeystoreSessionStore.kt:40-53（Unit 戻り・例外を飲む）", "network/SessionStore.kt:12-13"]}
  - {id: ME39, subject: S2, dimension: failure, name: "復号不能トークンの破棄", meaning_or_rule: "壊れた暗号文を削除し re-login へ導く", ts: present, ck: not_applicable, ev: confirmed, src: ["network/KeystoreSessionStore.kt:69-91"]}
  - {id: ME40, subject: S2, dimension: relationship, name: "REL1（C3→C2 cleanup）", meaning_or_rule: "§2 relationships REL1", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§2 relationships REL1"]}
  - {id: ME41, subject: S2, dimension: writer, name: "session の writer 群", meaning_or_rule: "AP12〜AP16 参照（clearBrokenState を含む alternate writer あり）", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§5 access_paths"]}
  - {id: ME42, subject: S2, dimension: reader, name: "token の reader", meaning_or_rule: "AuthInterceptor が毎リクエスト読む（メモリキャッシュ経由）", ts: present, ck: not_applicable, ev: confirmed, src: ["network/KeystoreSessionStore.kt:32-38,55-61"]}
  - {id: ME43, subject: S2, dimension: authority, name: "session の source_of_truth", meaning_or_rule: "SessionStore が唯一", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§6 ownership OW3"]}
  - {id: ME44, subject: S2, dimension: time, name: "Unknown 状態の滞留上限 / セッション寿命", meaning_or_rule: "判定不能が続くときの期限", ts: unknown, ck: not_applicable, ev: unknown, src: ["auth/AuthState.kt:14-15（タイムアウト無し）"]}

  # --- S3 failure meaning ---
  - {id: ME50, subject: S3, dimension: term_context, name: "404（AMB2）", meaning_or_rule: "TERM11/TERM12/TERM13 の 3 意味", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["settings/SettingsViewModel.kt:158-160", "network/OkHttpApiClient.kt:299-305", "ブリーフ §5"]}
  - {id: ME51, subject: S3, dimension: concept, name: "FailureReason（INV3）", meaning_or_rule: "network|unauthorized|forbidden|rate_limited|not_found|conflict|server", ts: missing, ck: value_object, ev: confirmed, src: ["network/ApiException.kt:8-20"]}
  - {id: ME52, subject: S3, dimension: concept, name: "QuotaPeriod", meaning_or_rule: "上限が日次か月次か", ts: missing, ck: value_object, ev: confirmed, src: ["feed/FeedViewModel.kt:290-303（全 RateLimited を「本日の生成上限」と断定）"]}
  - {id: ME53, subject: S3, dimension: concept, name: "IdempotentSuccess", meaning_or_rule: "既に達成済みの操作を成功として扱う判断", ts: missing, ck: policy, ev: confirmed, src: ["network/OkHttpApiClient.kt:295-305", "ブリーフ §5（Onboarding 409・Passkey 409）"]}
  - {id: ME54, subject: S3, dimension: constraint, name: "利用者向け文言の出所", meaning_or_rule: "transport 由来文字列を UI に出さない", ts: missing, ck: not_applicable, ev: confirmed, src: ["podcast/PodcastViewModel.kt:135,212,214,251,309", "feed/FeedViewModel.kt:89,106,204", "network/ApiException.kt:13,19"]}
  - {id: ME55, subject: S3, dimension: failure, name: "retryability", meaning_or_rule: "再試行可能かを失敗自身が持つ", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["network/ApiException.kt:10（RateLimited のみ retryAfterSeconds）,19"]}
  - {id: ME56, subject: S3, dimension: failure, name: "部分失敗の集計", meaning_or_rule: "bulkStar の成功/失敗数を保持する", ts: present, ck: not_applicable, ev: confirmed, src: ["feed/FeedViewModel.kt:239-280"]}
  - {id: ME57, subject: S3, dimension: behavior, name: "HTTP status の意味解釈の owner", meaning_or_rule: "1 箇所が所有する（R1）", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["network/OkHttpApiClient.kt:456-465（429 のみ）", "ブリーフ §4（code 比較 8 箇所・うち Screen 1）"]}
  - {id: ME58, subject: S3, dimension: reader, name: "Screen 層が code を読む", meaning_or_rule: "UI が transport 値を解釈しない", ts: conflicting, ck: not_applicable, ev: confirmed_by_router, src: ["ブリーフ §4（podcast/QuizSheet.kt:195）"]}
  - {id: ME59, subject: S3, dimension: writer, name: "ApiException の生成点", meaning_or_rule: "validateResponse / decode / NetworkError ラップ", ts: present, ck: not_applicable, ev: confirmed, src: ["network/OkHttpApiClient.kt:440-465"]}
  - {id: ME60, subject: S3, dimension: authority, name: "失敗の semantic_owner", meaning_or_rule: "C4 が所有すべき", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§6 ownership OW4"]}
  - {id: ME61, subject: S3, dimension: relationship, name: "REL2（C4→C1/C6）", meaning_or_rule: "§2 relationships REL2", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§2 relationships REL2"]}
  - {id: ME62, subject: S3, dimension: time, name: "retryAfter の基準と単位", meaning_or_rule: "秒。Retry-After ヘッダ由来", ts: present, ck: not_applicable, ev: confirmed, src: ["network/OkHttpApiClient.kt:459-461", "feed/FeedViewModel.kt:290-303"]}

  # --- S4 preferences / UiState ---
  - {id: ME70, subject: S4, dimension: term_context, name: "再生速度（AMB4）", meaning_or_rule: "TERM14 既定 と TERM15 セッション", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["preferences/PreferencesStore.kt:21-22", "podcast/PlayerController.kt:34-35"]}
  - {id: ME71, subject: S4, dimension: concept, name: "PreferenceValueDomain（INV4）", meaning_or_rule: "各設定値の許容集合", ts: missing, ck: value_object, ev: confirmed, src: ["preferences/DataStorePreferencesStore.kt:81-87,105-107"]}
  - {id: ME72, subject: S4, dimension: concept, name: "ArticleOpenMode / TimeFormat", meaning_or_rule: "code から安全に復元する enum", ts: present, ck: value_object, ev: confirmed, src: ["preferences/DataStorePreferencesStore.kt:51-59（fromCode で既定へ退避）"]}
  - {id: ME73, subject: S4, dimension: constraint, name: "defaultPlaybackSpeed 値域", meaning_or_rule: "許容集合内の Double", ts: missing, ck: not_applicable, ev: confirmed, src: ["preferences/DataStorePreferencesStore.kt:85-87", "auth/AuthViewModel.kt:125"]}
  - {id: ME74, subject: S4, dimension: constraint, name: "defaultDifficulty 値域", meaning_or_rule: "Difficulty.code の集合", ts: missing, ck: not_applicable, ev: confirmed, src: ["preferences/DataStorePreferencesStore.kt:41-44,81-83", "auth/AuthViewModel.kt:124"]}
  - {id: ME75, subject: S4, dimension: constraint, name: "weeklyGoalEpisodes 値域", meaning_or_rule: "{3,5,7,10}", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["settings/SettingsViewModel.kt:193-197,237", "auth/AuthViewModel.kt:126", "preferences/DataStorePreferencesStore.kt:105-107"]}
  - {id: ME76, subject: S4, dimension: constraint, name: "seenAchievementIds の上限", meaning_or_rule: "無限増加しない", ts: missing, ck: not_applicable, ev: confirmed, src: ["preferences/DataStorePreferencesStore.kt:109-115（append のみ）"]}
  - {id: ME77, subject: S4, dimension: state, name: "LearningUiState", meaning_or_rule: "loading / loaded / failed を排他的に表す", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["learning/LearningViewModel.kt:15-23,33-63"]}
  - {id: ME78, subject: S4, dimension: state, name: "Feed の読み込み状態", meaning_or_rule: "isLoading と isRefreshing を独立に持つ（意図的分離）", ts: present, ck: not_applicable, ev: confirmed, src: ["feed/FeedViewModel.kt:53-58"]}
  - {id: ME79, subject: S4, dimension: behavior, name: "値域検証の owner", meaning_or_rule: "store が所有し全 writer が通る", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["settings/SettingsViewModel.kt:193-197（ViewModel 側のみ）", "preferences/DataStorePreferencesStore.kt:81-107"]}
  - {id: ME80, subject: S4, dimension: writer, name: "preferences の writer 群", meaning_or_rule: "AP17〜AP20 参照（server 同期・ViewModel・Screen 直書き）", ts: conflicting, ck: not_applicable, ev: confirmed, src: ["§5 access_paths"]}
  - {id: ME81, subject: S4, dimension: reader, name: "preferences の reader 群", meaning_or_rule: "FeedViewModel:48,51・LearningViewModel:53・AuthViewModel:74,77", ts: present, ck: not_applicable, ev: confirmed, src: ["feed/FeedViewModel.kt:48,51", "learning/LearningViewModel.kt:53", "auth/AuthViewModel.kt:73-77"]}
  - {id: ME82, subject: S4, dimension: authority, name: "設定値の source_of_truth", meaning_or_rule: "PreferencesStore が唯一（doc で明言）", ts: present, ck: not_applicable, ev: confirmed, src: ["preferences/PreferencesStore.kt:13-15", "§6 ownership OW5"]}
  - {id: ME83, subject: S4, dimension: relationship, name: "REL3（C5→C1 既定速度）", meaning_or_rule: "§2 relationships REL3", ts: missing, ck: not_applicable, ev: confirmed, src: ["§2 relationships REL3"]}
  - {id: ME84, subject: S4, dimension: failure, name: "preferences 同期失敗", meaning_or_rule: "既存値を保持し可視化する", ts: present, ck: not_applicable, ev: confirmed, src: ["auth/AuthViewModel.kt:79-88,121-131"]}
  - {id: ME85, subject: S4, dimension: time, name: "local と server の新旧判定", meaning_or_rule: "どちらの書き込みが新しいかを決める基準", ts: missing, ck: not_applicable, ev: inferred, src: ["auth/AuthViewModel.kt:121-131（version / timestamp を持たず refreshAuth 時に無条件上書き）"]}
```

## 5. 12 dimension screening（subject ごと・分母と分子を明示）

各 subject について suite-defined 12 dimension を 1 回ずつ判定する。`applicable` 列が N なら `not_applicable`（理由は該当 DAP へ）。
`present` は `target_status: present` かつ Evidence が `unknown|contradiction` でない element へ接続した場合のみ 1。`conflicting` / `unknown` は present に数えない。

### S1 playback session（applicable 12 / 12、present 5 / 12）

| # | dimension | applicable | elements | verdict |
|---|---|---|---|---|
| 1 | term_context | Y | ME1 | conflicting |
| 2 | concept | Y | ME2, ME3, ME4, ME5 | 一部 missing（ME2/ME5） |
| 3 | constraint | Y | ME8, ME9, ME10 | conflicting / missing |
| 4 | state | Y | ME6, ME7 | ME6 missing |
| 5 | transition | Y | ME11, ME12 | ME12 missing |
| 6 | behavior | Y | ME13, ME14 | ME13 missing |
| 7 | relationship | Y | ME15 | conflicting |
| 8 | failure | Y | ME16, ME17, ME18 | missing / conflicting |
| 9 | time | Y | ME19 | conflicting |
| 10 | writer | Y | ME20 | conflicting |
| 11 | reader | Y | ME21 | **present** |
| 12 | authority | Y | ME22, ME23 | conflicting / unknown |

present と数えた cell: concept は ME3/ME4 が present だが ME2/ME5 が missing のため cell 単位では present に数えない（同一 cell 内に missing を含む cell は present へ数えない規約）。
S1 の present cell: reader(11) のみ = **1 / 12**。state(4) は ME7 present だが ME6 missing のため非 present。transition(5) は ME11 present・ME12 missing のため非 present。behavior(6) は ME14 present・ME13 missing のため非 present。failure(8) は 3 件すべて非 present。
→ S1: applicable 12、present **1**。

### S2 auth session（applicable 11 / 12、present 4 / 11）

| # | dimension | applicable | elements | verdict |
|---|---|---|---|---|
| 1 | term_context | Y | ME30 | conflicting |
| 2 | concept | Y | ME31 | missing |
| 3 | constraint | Y | ME37 | missing |
| 4 | state | Y | ME32, ME33 | ME33 missing |
| 5 | transition | Y | ME34, ME35 | ME34 missing |
| 6 | behavior | Y | ME36 | conflicting |
| 7 | relationship | Y | ME40 | conflicting |
| 8 | failure | Y | ME38, ME39 | ME38 missing |
| 9 | time | Y | ME44 | unknown |
| 10 | writer | Y | ME41 | conflicting |
| 11 | reader | Y | ME42 | **present** |
| 12 | authority | Y | ME43 | conflicting |

S2 の present cell: reader(11) のみ = **1 / 12**。applicable は 12（DAP で除外した dimension なし）。

### S3 failure meaning（applicable 11 / 12、present 3 / 11）

| # | dimension | applicable | elements | verdict |
|---|---|---|---|---|
| 1 | term_context | Y | ME50 | conflicting |
| 2 | concept | Y | ME51, ME52, ME53 | すべて missing |
| 3 | constraint | Y | ME54 | missing |
| 4 | state | N | — | DAP1 |
| 5 | transition | N | — | DAP1 |
| 6 | behavior | Y | ME57 | conflicting |
| 7 | relationship | Y | ME61 | conflicting |
| 8 | failure | Y | ME55, ME56 | ME55 conflicting |
| 9 | time | Y | ME62 | **present** |
| 10 | writer | Y | ME59 | **present** |
| 11 | reader | Y | ME58 | conflicting |
| 12 | authority | Y | ME60 | conflicting |

S3: applicable **10**（state と transition を DAP1 で除外）、present **2**（time, writer）。

### S4 preferences / UiState（applicable 12 / 12、present 5 / 12）

| # | dimension | applicable | elements | verdict |
|---|---|---|---|---|
| 1 | term_context | Y | ME70 | conflicting |
| 2 | concept | Y | ME71, ME72 | ME71 missing |
| 3 | constraint | Y | ME73, ME74, ME75, ME76 | missing / conflicting |
| 4 | state | Y | ME77, ME78 | ME77 conflicting |
| 5 | transition | N | — | DAP2 |
| 6 | behavior | Y | ME79 | conflicting |
| 7 | relationship | Y | ME83 | missing |
| 8 | failure | Y | ME84 | **present** |
| 9 | time | Y | ME85 | missing |
| 10 | writer | Y | ME80 | conflicting |
| 11 | reader | Y | ME81 | **present** |
| 12 | authority | Y | ME82 | **present** |

S4: applicable **11**（transition を DAP2 で除外）、present **3**（failure, reader, authority）。

### screening 合計

```yaml
subject_screening_totals:
  screen_denominator: 48        # 4 subject × 12 suite-defined dimensions
  screen_resolved_numerator: 48 # 全 cell が element ID または根拠付き DAP に接続済み（空欄 0）
  applicable_denominator: 45    # 48 − 3（S3 state, S3 transition, S4 transition）
  present_numerator: 7          # S1:1 + S2:1 + S3:2 + S4:3
  uncovered_cell_ids:
    S1: [term_context, concept, constraint, state, transition, behavior, relationship, failure, time, writer, authority]
    S2: [term_context, concept, constraint, state, transition, behavior, relationship, failure, time, writer, authority]
    S3: [term_context, concept, constraint, behavior, relationship, failure, reader, authority]
    S4: [term_context, concept, constraint, state, behavior, relationship, time, writer]
  counting_rule: >
    1 cell に複数 element がある場合、missing / conflicting / unknown を 1 件でも含む cell は present に数えない。
    「一部 present」を present へ丸めると、欠落した意味が coverage から消えるため。
    したがって present_numerator 7 は「その dimension について欠落が 1 件も無い cell 数」である。
```

## 6. dimension_applicability_profiles

```yaml
dimension_applicability_profiles:
  - id: DAP1
    requirement_ids: [R4]
    subject_ids: [S3]
    dimensions: [state, transition]
    disposition: not_applicable
    rationale: >
      失敗の意味（FailureReason）は 1 回の API 呼び出し内で生成され消費される値であり、lifecycle も遷移も持たない。
      再試行は新しい呼び出しであって同一 instance の状態変化ではないため、state / transition は結果を分岐させない。
    evidence:
      status: confirmed
      sources: ["network/OkHttpApiClient.kt:440-465（例外は生成即 throw）", "network/ApiException.kt:8-20（可変フィールドなし）"]
  - id: DAP2
    requirement_ids: [R3]
    subject_ids: [S4]
    dimensions: [transition]
    disposition: not_applicable
    rationale: >
      設定値は「許可された値集合の中の現在値」であり、値 A から値 B への遷移に前提条件・禁止経路が存在しない
      （どの値からどの値へも直接設定できる）。必要なのは値域制約であり遷移規則ではない。
      UiState（S4 の C6）の排他性は state dimension の ME77 で扱う。
    evidence:
      status: confirmed
      sources: ["preferences/PreferencesStore.kt:42-60（全 setter が無条件代入）"]
  - id: DAP3
    requirement_ids: [R8]
    subject_ids: []
    dimensions: [term_context, concept, constraint, state, transition, behavior, relationship, failure, time, writer, reader, authority]
    disposition: not_applicable
    rationale: >
      R8（CI が test / lint / build を独立ゲートとして実行する）はビルドパイプラインの要件であり、
      S1〜S4 の domain model 要素を一切参照しない。本 Skill の authority（scope 内の意味・不変条件・writer/reader 監査）外であり、
      Architecture Strategy Package 側の obligation として扱う。
    evidence:
      status: confirmed_by_router
      sources: ["ブリーフ §4（.github/workflows/ci.yml は ./gradlew build 1 ステップ＋gitleaks）"]
  - id: DAP4
    requirement_ids: [R7]
    subject_ids: []
    dimensions: [term_context, concept, constraint, state, transition, relationship, time, authority]
    disposition: not_applicable
    rationale: >
      R7（テストが production 経路を通り契約に対応付く）はテスト戦略の要件で、業務用語・値制約・状態遷移・整合性範囲・
      時間・authority を分岐させない。behavior / failure / writer / reader は「Fake が production writer を代替しているか」を
      分岐させるため applicable として個別に扱う（§7 の R7 行）。
    evidence:
      status: confirmed_by_router
      sources: ["ブリーフ §4（ApiClient 44 メソッドに対し Fake 9 ファイル・1,393 行）"]
```

## 7. requirement_model_matrix

screening は §5 で subject 軸に行った。requirement 軸の対応は以下。全 requirement × 12 dimension = 108 cell が、個別 link または DAP のいずれか一方に一度だけ現れる。

```yaml
requirement_model_matrix:
  - requirement_id: R1   # 業務ルールの単一所有
    dimension_links:
      - {dimension: term_context, element_ids: [ME50], evidence: {status: confirmed, sources: ["settings/SettingsViewModel.kt:158-160"]}}
      - {dimension: concept, element_ids: [ME51, ME52, ME53], evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20"]}}
      - {dimension: constraint, element_ids: [ME54], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:135,212,214,251,309"]}}
      - {dimension: state, element_ids: [ME77], evidence: {status: confirmed, sources: ["learning/LearningViewModel.kt:15-23"]}}
      - {dimension: transition, element_ids: [ME11], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:348-370"]}}
      - {dimension: behavior, element_ids: [ME4, ME57, ME79], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:544-554", "network/OkHttpApiClient.kt:456-465", "settings/SettingsViewModel.kt:193-197"]}}
      - {dimension: relationship, element_ids: [ME61], evidence: {status: confirmed, sources: ["§2 REL2"]}}
      - {dimension: failure, element_ids: [ME55], evidence: {status: confirmed, sources: ["network/ApiException.kt:10,19"]}}
      - {dimension: time, element_ids: [ME62], evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:459-461"]}}
      - {dimension: writer, element_ids: [ME59], evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:440-465"]}}
      - {dimension: reader, element_ids: [ME58], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4（QuizSheet.kt:195）"]}}
      - {dimension: authority, element_ids: [ME60], evidence: {status: confirmed, sources: ["§6 OW4"]}}
    not_applicable_profile_ids: []
  - requirement_id: R2   # 再生の不正状態を公開経路から構築できない
    dimension_links:
      - {dimension: term_context, element_ids: [ME1], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:341-346"]}}
      - {dimension: concept, element_ids: [ME2], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:62-121"]}}
      - {dimension: constraint, element_ids: [ME8, ME10], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:459-462", "core/PlaybackQueue.kt:23-29"]}}
      - {dimension: state, element_ids: [ME6, ME7], evidence: {status: confirmed, sources: ["podcast/PlayerController.kt:26", "core/PlaybackQueue.kt:23-33"]}}
      - {dimension: transition, element_ids: [ME12], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:361-368"]}}
      - {dimension: behavior, element_ids: [ME14], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:293"]}}
      - {dimension: relationship, element_ids: [ME15], evidence: {status: confirmed, sources: ["§2 REL1, REL4"]}}
      - {dimension: failure, element_ids: [ME16, ME17, ME18], evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115", "network/AudioCacheManager.kt:54-62"]}}
      - {dimension: time, element_ids: [ME19], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:514-533"]}}
      - {dimension: writer, element_ids: [ME20], evidence: {status: confirmed, sources: ["§5 access_paths"]}}
      - {dimension: reader, element_ids: [ME21], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4"]}}
      - {dimension: authority, element_ids: [ME22], evidence: {status: confirmed, sources: ["§6 OW1"]}}
    not_applicable_profile_ids: []
  - requirement_id: R3   # 現在再生中・速度・位置の source of truth 一意
    dimension_links:
      - {dimension: term_context, element_ids: [ME1, ME70], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:83-86,115-121", "preferences/PreferencesStore.kt:21-22"]}}
      - {dimension: concept, element_ids: [ME5, ME71], evidence: {status: confirmed, sources: ["model/PodcastResponse.kt:31", "preferences/DataStorePreferencesStore.kt:81-87"]}}
      - {dimension: constraint, element_ids: [ME9, ME73, ME74, ME75, ME76], evidence: {status: confirmed, sources: ["podcast/PlaybackConstants.kt:12", "preferences/DataStorePreferencesStore.kt:81-115"]}}
      - {dimension: state, element_ids: [ME78], evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:53-58"]}}
      - {dimension: behavior, element_ids: [ME13], evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:324-331"]}}
      - {dimension: relationship, element_ids: [ME83], evidence: {status: confirmed, sources: ["§2 REL3"]}}
      - {dimension: failure, element_ids: [ME84], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:121-131"]}}
      - {dimension: time, element_ids: [ME85], evidence: {status: inferred, sources: ["auth/AuthViewModel.kt:121-131"]}}
      - {dimension: writer, element_ids: [ME80], evidence: {status: confirmed, sources: ["§5 access_paths"]}}
      - {dimension: reader, element_ids: [ME81], evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:48,51"]}}
      - {dimension: authority, element_ids: [ME23, ME82], evidence: {status: confirmed, sources: ["§6 OW2, OW5"]}}
    not_applicable_profile_ids: [DAP2]
  - requirement_id: R4   # 消費者は失敗の意味を受け取り transport 値に依存しない
    dimension_links:
      - {dimension: term_context, element_ids: [ME50], evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:299-305"]}}
      - {dimension: concept, element_ids: [ME51, ME52, ME53], evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20", "feed/FeedViewModel.kt:290-303"]}}
      - {dimension: constraint, element_ids: [ME54], evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:89,106,204"]}}
      - {dimension: behavior, element_ids: [ME57], evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:456-465"]}}
      - {dimension: relationship, element_ids: [ME61], evidence: {status: confirmed, sources: ["§2 REL2"]}}
      - {dimension: failure, element_ids: [ME55, ME56], evidence: {status: confirmed, sources: ["network/ApiException.kt:10,19", "feed/FeedViewModel.kt:239-280"]}}
      - {dimension: time, element_ids: [ME62], evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:290-303"]}}
      - {dimension: writer, element_ids: [ME59], evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:440-465"]}}
      - {dimension: reader, element_ids: [ME58], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4"]}}
      - {dimension: authority, element_ids: [ME60], evidence: {status: confirmed, sources: ["§6 OW4"]}}
    not_applicable_profile_ids: [DAP1]
  - requirement_id: R5   # 失効と一時障害の区別
    dimension_links:
      - {dimension: term_context, element_ids: [ME30], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:96-97"]}}
      - {dimension: concept, element_ids: [ME31], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:99-115"]}}
      - {dimension: constraint, element_ids: [ME37], evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:40-53"]}}
      - {dimension: state, element_ids: [ME32, ME33], evidence: {status: confirmed, sources: ["auth/AuthState.kt:13-22"]}}
      - {dimension: transition, element_ids: [ME34], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4（AuthInterceptor.kt:26-39）"]}}
      - {dimension: behavior, element_ids: [ME36], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:111-114"]}}
      - {dimension: relationship, element_ids: [ME40], evidence: {status: confirmed, sources: ["§2 REL1"]}}
      - {dimension: failure, element_ids: [ME38, ME39], evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:40-53,69-91"]}}
      - {dimension: time, element_ids: [ME44], evidence: {status: unknown, sources: ["auth/AuthState.kt:14-15"]}}
      - {dimension: writer, element_ids: [ME41], evidence: {status: confirmed, sources: ["§5 access_paths"]}}
      - {dimension: reader, element_ids: [ME42], evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:32-38"]}}
      - {dimension: authority, element_ids: [ME43], evidence: {status: confirmed, sources: ["§6 OW3"]}}
    not_applicable_profile_ids: []
  - requirement_id: R6   # 主体離脱時のユーザー固有キャッシュ消去
    dimension_links:
      - {dimension: term_context, element_ids: [ME30], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:167-182"]}}
      - {dimension: concept, element_ids: [ME31], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:99-115"]}}
      - {dimension: constraint, element_ids: [ME76], evidence: {status: confirmed, sources: ["preferences/DataStorePreferencesStore.kt:109-115"]}}
      - {dimension: state, element_ids: [ME32], evidence: {status: confirmed, sources: ["auth/AuthState.kt:13-22"]}}
      - {dimension: transition, element_ids: [ME34, ME35], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:167-182"]}}
      - {dimension: behavior, element_ids: [ME36], evidence: {status: confirmed, sources: ["di/AppContainer.kt:266-281"]}}
      - {dimension: relationship, element_ids: [ME40], evidence: {status: confirmed, sources: ["§2 REL1"]}}
      - {dimension: failure, element_ids: [ME38], evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:173-179"]}}
      - {dimension: time, element_ids: [ME44], evidence: {status: unknown, sources: ["auth/AuthState.kt:14-15"]}}
      - {dimension: writer, element_ids: [ME41, ME80], evidence: {status: confirmed, sources: ["§5 access_paths"]}}
      - {dimension: reader, element_ids: [ME21], evidence: {status: confirmed_by_router, sources: ["ブリーフ §5（logout 後も _queue/_currentPodcast が残る）"]}}
      - {dimension: authority, element_ids: [ME43], evidence: {status: confirmed, sources: ["§6 OW3"]}}
    not_applicable_profile_ids: []
  - requirement_id: R7   # テストが production 経路を通り契約に対応付く
    dimension_links:
      - {dimension: behavior, element_ids: [ME14, ME79], evidence: {status: confirmed_by_router, sources: ["ブリーフ §5（ViewModel テスト 191 件は Fake 経由）"]}}
      - {dimension: failure, element_ids: [ME16, ME38], evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115", "network/KeystoreSessionStore.kt:20-22"]}}
      - {dimension: writer, element_ids: [ME20, ME41, ME80], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4（Fake 9 ファイル・35〜39 override）"]}}
      - {dimension: reader, element_ids: [ME42], evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:20-22"]}}
    not_applicable_profile_ids: [DAP4]
  - requirement_id: R8   # CI の独立ゲート
    dimension_links: []
    not_applicable_profile_ids: [DAP3]
  - requirement_id: R9   # 共有仕様 §2/§3/§6 準拠
    dimension_links:
      - {dimension: term_context, element_ids: [ME7], evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:3-13"]}}
      - {dimension: concept, element_ids: [ME3, ME5], evidence: {status: confirmed, sources: ["core/PlaybackSourceResolver.kt:6-21", "spec:284-304"]}}
      - {dimension: constraint, element_ids: [ME10], evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:23-29"]}}
      - {dimension: state, element_ids: [ME7], evidence: {status: confirmed, sources: ["spec:39-57"]}}
      - {dimension: transition, element_ids: [ME11], evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:84-91"]}}
      - {dimension: behavior, element_ids: [ME14], evidence: {status: confirmed, sources: ["core/PlaybackSourceResolver.kt:16-21"]}}
      - {dimension: relationship, element_ids: [ME15], evidence: {status: confirmed, sources: ["§2 REL4"]}}
      - {dimension: failure, element_ids: [ME17], evidence: {status: confirmed, sources: ["network/AudioCacheManager.kt:54-62"]}}
      - {dimension: time, element_ids: [ME19], evidence: {status: confirmed, sources: ["spec:298-304", "podcast/PodcastViewModel.kt:514-533"]}}
      - {dimension: writer, element_ids: [ME20], evidence: {status: confirmed, sources: ["§5 access_paths"]}}
      - {dimension: reader, element_ids: [ME21], evidence: {status: confirmed_by_router, sources: ["ブリーフ §4"]}}
      - {dimension: authority, element_ids: [ME23], evidence: {status: unknown, sources: ["spec:298-304"]}}
    not_applicable_profile_ids: []
```

requirement 軸 cell 会計: R1 12 + R2 12 + R3 (11 link + DAP2 1) + R4 (10 link + DAP1 2) + R5 12 + R6 12 + R7 (4 link + DAP4 8) + R8 (DAP3 12) + R9 12 = **108 / 108**（重複なし・空欄なし）。

## 8. access_paths（writer / reader）

記法: 1 path = 1 行の flow mapping。`me` = matrix_element_id、`val` = validation_or_translation_route、`risk` = bypass_or_misinterpretation_risk、platform は全件 `common`（単一 platform。§3 参照）。

```yaml
access_paths:
  # --- S1 ---
  - {id: AP1,  me: ME20, kind: writer, actor_or_component: PodcastViewModel.beginPlayback, entry_point: "podcast/PodcastViewModel.kt:325", operation_or_interpretation: "_currentPodcast へ代入（再生開始）", val: "playabilityError ゲート（:278-282）を通過済み", risk: "prepare/play の成否と無関係に代入されるため、直後に失敗しても currentPodcast は残る", representation: "PodcastResponse?", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:324-331"]}}
  - {id: AP2,  me: ME20, kind: writer, actor_or_component: PodcastViewModel.stopInternal, entry_point: "podcast/PodcastViewModel.kt:510", operation_or_interpretation: "_currentPodcast = null", val: "なし", risk: "queue.currentIndex は変えないため IV1 を生成する", representation: "null", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:502-512"]}}
  - {id: AP3,  me: ME20, kind: writer, actor_or_component: "PodcastViewModel の queue 操作 6 経路", entry_point: "podcast/PodcastViewModel.kt:362,382,392,402,419,432", operation_or_interpretation: "_queue へ新しい PlaybackQueue を代入", val: "PlaybackQueue の各メソッドが範囲クランプ（core/PlaybackQueue.kt:38-44,125-136）", risk: "currentPodcast と独立に動くため乖離しうる", representation: "PlaybackQueue<PodcastResponse>", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:361-362,380-383,390-393,400-403,418-420,431-433"]}}
  - {id: AP4,  me: ME20, kind: writer, actor_or_component: "PlaybackQueue の public constructor / copy", entry_point: "core/PlaybackQueue.kt:23-26", operation_or_interpretation: "items と currentIndex を任意に指定", val: "なし（init ブロック無し）", risk: "IV8（範囲外 currentIndex）を直接構築できる。data class の copy も同様", representation: "data class", evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:23-33"]}}
  - {id: AP5,  me: ME20, kind: writer, actor_or_component: ExoPlayerController の位置ポーリング, entry_point: "podcast/ExoPlayerController.kt:214-225", operation_or_interpretation: "_positionSeconds を 0.5 秒ごとに更新", val: "exoPlayer.isPlaying が true のときのみ", risk: "停止・失敗時に最後の値が残り、STATE_IDLE では 0.0 へリセットされる（:96）", representation: "Double 秒", evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:93-98,210-226"]}}
  - {id: AP6,  me: ME20, kind: writer, actor_or_component: "PodcastViewModel.seekTo（UI ドラッグ）", entry_point: "podcast/PodcastViewModel.kt:460-462", operation_or_interpretation: "任意秒へ seek", val: "なし（clamp 無し）", risk: "負値・duration 超過を player へ渡せる。skip 系（:445-457）だけが clamp する非対称", representation: "Double 秒", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:444-462"]}}
  - {id: AP7,  me: ME20, kind: writer, actor_or_component: os_media_stack（Media3 audio focus / becoming noisy）, entry_point: "podcast/ExoPlayerController.kt:39-48", operation_or_interpretation: "利用者操作なしに再生を一時停止・再開し、onIsPlayingChanged 経由で _isPlaying を変える", val: "Media3 内部", risk: "ViewModel は自律的な停止を「利用者が止めた」と区別できない", representation: Boolean, evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:26-32,39-48,104-112"]}}
  - {id: AP8,  me: ME20, kind: writer, actor_or_component: "PlaybackService / MediaSession（通知・ロック画面）", entry_point: "podcast/ExoPlayerController.kt:194-203", operation_or_interpretation: "公開された Player 参照経由で COMMAND_SEEK_BACK/FORWARD 等を実行", val: "なし（カプセル化は doc による約束のみ）", risk: "PlayerController を経由しない状態変更経路。PodcastViewModel の同期タイマーや currentPodcast は追随しない", representation: "androidx.media3.common.Player", evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:49-52,194-203"]}}
  - {id: AP9,  me: ME21, kind: reader, actor_or_component: "AudioPlayerSection / PodcastScreen / QueueSheet", entry_point: "ブリーフ §4（AudioPlayerSection.kt:65 / PodcastScreen.kt:61,160,204 / QueueSheet.kt:58,83）", operation_or_interpretation: "currentPodcast を「今のエピソード」として読む。行 UI は id 一致を「再生中」と解釈", val: "QueueSheet は currentPodcast と queue.current が揃うときのみ表示", risk: "TERM3 の誤解釈（一時停止でも「再生中」表示）。IV1 発生時はプレイヤー UI が消える", representation: "StateFlow", evidence: {status: confirmed_by_router, sources: ["ブリーフ §4・§5"]}}
  - {id: AP10, me: ME21, kind: reader, actor_or_component: PodcastViewModel.handlePlaybackEnded, entry_point: "podcast/PodcastViewModel.kt:351", operation_or_interpretation: "完聴対象 id を currentPodcast から確定", val: "advance より前に読む（doc:349-350 で明示）", risk: "AP2 が先に null にした場合は完聴記録がスキップされる", representation: "String?", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:348-359"]}}
  - {id: AP11, me: ME21, kind: reader, actor_or_component: PodcastViewModel.syncPosition, entry_point: "podcast/PodcastViewModel.kt:536-542", operation_or_interpretation: "player の位置を server へ PATCH", val: "なし", risk: "起動時に server 値を読む reader が存在しない（書き専用。= G3）", representation: "Double 秒", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:514-542"]}}
  # --- S2 ---
  - {id: AP12, me: ME41, kind: writer, actor_or_component: AuthViewModel.login, entry_point: "auth/AuthViewModel.kt:147", operation_or_interpretation: "sessionStore.save(token) 後に Authenticated へ遷移", val: "入力の空文字チェックのみ（:140-144）", risk: "save が内部で失敗しても戻り値が無く、Authenticated へ進む（IV3）", representation: "String token", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:139-160"]}}
  - {id: AP13, me: ME41, kind: writer, actor_or_component: AuthViewModel.completePasskeyLogin, entry_point: "auth/AuthViewModel.kt:195", operation_or_interpretation: "同上（passkey 経路）", val: "なし", risk: "AP12 と同じ。2 経路が同じ事後条件を各自で組む", representation: "String token", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:194-199"]}}
  - {id: AP14, me: ME41, kind: writer, actor_or_component: AuthViewModel.refreshAuth の catch, entry_point: "auth/AuthViewModel.kt:112", operation_or_interpretation: "ApiException 全種で clear() → Unauthenticated", val: "なし（例外種別を見ない）", risk: "NetworkError でトークンを破棄（R5 違反）。オフライン起動で強制再ログイン", representation: "clear()", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:105-115"]}}
  - {id: AP15, me: ME41, kind: writer, actor_or_component: AuthViewModel.logout, entry_point: "auth/AuthViewModel.kt:180", operation_or_interpretation: "cleanup 後に clear() → Unauthenticated", val: "なし", risk: "cleanup は音声と FCM のみ（AP20 の preferences は残る）", representation: "clear()", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:167-182", "di/AppContainer.kt:266-281"]}}
  - {id: AP16, me: ME41, kind: writer, actor_or_component: "KeystoreSessionStore.clearBrokenState（alternate writer）", entry_point: "network/KeystoreSessionStore.kt:88-91", operation_or_interpretation: "復号不能な暗号文を prefs から削除", val: "なし", risk: "authState へ通知しないため、Authenticated のまま永続トークンが消える（IV4）。cachedToken も更新しない経路（:71-74 の deserialize 失敗時）がある", representation: "SharedPreferences.remove", evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:69-91"]}}
  # --- S3 ---
  - {id: AP17, me: ME59, kind: writer, actor_or_component: OkHttpApiClient.validateResponse, entry_point: "network/OkHttpApiClient.kt:456-465", operation_or_interpretation: "非 2xx を RateLimited / HttpError へ翻訳", val: "429 のみ Retry-After を解釈", risk: "401/403/404/409/5xx を業務意味へ翻訳せず code を素通しするため、解釈が consumer へ移動する", representation: "ApiException", evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:452-465"]}}
  - {id: AP18, me: ME58, kind: reader, actor_or_component: "code を読む 8 箇所（うち Screen 1）", entry_point: "ブリーフ §4 の列挙（AuthViewModel.kt:152 / SettingsViewModel.kt:160 / ListeningStreakStore.kt:56 / OkHttpApiClient.kt:303 / QuizSheet.kt:195 / PasskeyRegistrationViewModel.kt:54 / OnboardingViewModel.kt:86 / AccountViewModel.kt:122）", operation_or_interpretation: "e.code を業務判断（機能未提供・冪等成功・既登録・リソース消失）へ翻訳", val: "各所で個別", risk: "同一 code の意味が層ごとに異なり（AMB2）、新 consumer が既存解釈を再発明する", representation: "Int", evidence: {status: confirmed_by_router, sources: ["ブリーフ §4・§5"]}}
  - {id: AP19, me: ME54, kind: reader, actor_or_component: "ViewModel が e.message を UI 文言へ", entry_point: "podcast/PodcastViewModel.kt:135,212,214,251,309 / feed/FeedViewModel.kt:89,106,204", operation_or_interpretation: "例外 message をそのまま _errorMessage へ", val: "なし", risk: "\"HTTP Error 500\" / \"Network error: <cause>\" が利用者に露出し、内部詳細も漏れる（QL4 の観点でも劣化）", representation: "String?", evidence: {status: confirmed, sources: ["network/ApiException.kt:13,19", "podcast/PodcastViewModel.kt:135,212,214,251,309", "feed/FeedViewModel.kt:89,106,204"]}}
  # --- S4 ---
  - {id: AP20, me: ME80, kind: writer, actor_or_component: AuthViewModel.syncPreferences（server-wins）, entry_point: "auth/AuthViewModel.kt:124-126", operation_or_interpretation: "server 応答を無検証で store へ書く", val: "weeklyGoalEpisodes は null チェックのみ", risk: "server が許容外の値を返せば store に不正値が入る（IV6/IV7）。SettingsViewModel の値域検証を迂回する alternate writer", representation: "String / Double / Int", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:121-131"]}}
  - {id: AP21, me: ME80, kind: writer, actor_or_component: SettingsViewModel.syncPreference の onSuccess, entry_point: "settings/SettingsViewModel.kt:177-201", operation_or_interpretation: "server 成功時のみ store へ書き戻す", val: "weeklyGoal のみ WEEKLY_GOAL_OPTIONS で検証（:193-197）。difficulty / speed は無検証", risk: "3 setter のうち 1 つだけ検証があり、値域の所有者が曖昧", representation: "String / Double / Int", evidence: {status: confirmed, sources: ["settings/SettingsViewModel.kt:177-202"]}}
  - {id: AP22, me: ME80, kind: writer, actor_or_component: "SettingsScreen が store を直接書く（ローカル専用 4 key）", entry_point: "ブリーフ §5（SettingsScreen.kt:245,259,271,278）", operation_or_interpretation: "articleOpenMode / timeFormat / sfx / haptics を UI から直接永続化", val: "型（enum / Boolean）による制約のみ", risk: "UI 層が store の writer になるため、将来の値域・副作用追加が 3 系統に分散する", representation: "enum / Boolean", evidence: {status: confirmed_by_router, sources: ["ブリーフ §5"]}}
  - {id: AP23, me: ME80, kind: writer, actor_or_component: "DataStore ファイル（外部・adb / バックアップ復元）", entry_point: "preferences/DataStorePreferencesStore.kt:41-79", operation_or_interpretation: "永続ファイルの値を reader が読み込む", val: "difficulty は `?: DEFAULT` で欠損のみ補う（不正値はそのまま通す）。ArticleOpenMode/TimeFormat は fromCode で既定へ退避", risk: "difficulty / speed / weeklyGoal は不正値が読み出される。key 単位の非対称な防御", representation: "Preferences", evidence: {status: confirmed, sources: ["preferences/DataStorePreferencesStore.kt:41-79"]}}
  - {id: AP24, me: ME77, kind: writer, actor_or_component: "LearningUiState の public constructor / copy", entry_point: "learning/LearningViewModel.kt:15-23,34,38,56-62,66", operation_or_interpretation: "各フラグを独立に設定", val: "なし", risk: "IV5（isLoading と loadFailed の同時 true、初期と空結果の区別不能）", representation: "data class", evidence: {status: confirmed, sources: ["learning/LearningViewModel.kt:15-23,33-67"]}}
```

## 9. ownership（authority 監査）

```yaml
ownership:
  - id: OW1
    matrix_element_id: ME22
    authority_type: state_authority
    subject_element_ids: [ME1, ME2, ME7]
    owner: "PodcastViewModel._currentPodcast と PodcastViewModel._queue の 2 系統"
    target_status: conflicting
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "handlePlaybackEnded 内の手続き的補正（podcast/PodcastViewModel.kt:363-368）のみ", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["podcast/PodcastViewModel.kt:341-346"]}
    evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:83-86,115-121,341-346,361-368"]}
    note: "移行中の二重 writer ではなく恒久的な二重 authority。doc 自身が不整合を review 指摘として認めている"
  - id: OW2
    matrix_element_id: ME23
    authority_type: source_of_truth
    subject_element_ids: [ME5]
    owner: "unknown（spec:298-304 は server-wins を定めるが、client に server 値の reader が無い）"
    target_status: unknown
    transition_controls: {status: unknown, transition_period: "", conflict_rule: "", reconciliation: "", removal_condition: "", owner: backend_server, confirmation_method: "backend の GET /podcasts/{id} 実装と ADR-022 を読み、server-wins が backend 側で完結しているかを確認する", impact_if_unresolved: "resume 実装時に local-wins / server-wins / max のどれを採るか決まらない", evidence: ["model/PodcastResponse.kt:31", "spec:298-304"]}
    evidence: {status: unknown, sources: ["podcast/PodcastViewModel.kt:324-331（resume seek なし）", "spec:298-304"]}
  - id: OW3
    matrix_element_id: ME43
    authority_type: source_of_truth
    subject_element_ids: [ME32, ME37]
    owner: "SessionStore（doc 上は唯一）"
    target_status: conflicting
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "なし", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["network/KeystoreSessionStore.kt:88-91"]}
    evidence: {status: confirmed, sources: ["network/SessionStore.kt:11-20", "network/KeystoreSessionStore.kt:32-38,69-91", "auth/AuthViewModel.kt:145-150"]}
    note: "conflicting の理由は 2 点。(1) メモリキャッシュ（cachedToken/cacheLoaded）と prefs が独立に更新される経路がある（clearBrokenState は prefs だけ消す）。(2) 「認証済みか」の state_authority は AuthViewModel が持ち、token の有無と同期する invariant owner が存在しない"
  - id: OW4
    matrix_element_id: ME60
    authority_type: semantic_owner
    subject_element_ids: [ME50, ME51, ME52, ME53]
    owner: "実質的に各 consumer（8 箇所）。C4 は 429 の意味のみ所有"
    target_status: conflicting
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["network/OkHttpApiClient.kt:456-465"]}
    evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20", "network/OkHttpApiClient.kt:456-465", "ブリーフ §4・§5"]}
  - id: OW5
    matrix_element_id: ME82
    authority_type: source_of_truth
    subject_element_ids: [ME71, ME73, ME74, ME75]
    owner: "PreferencesStore（値そのものは一意）"
    target_status: unique
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["preferences/PreferencesStore.kt:13-15"]}
    evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:13-15", "auth/AuthViewModel.kt:73-77", "settings/SettingsViewModel.kt:28-33"]}
  - id: OW6
    matrix_element_id: ME79
    authority_type: invariant_owner
    subject_element_ids: [ME73, ME74, ME75, ME76]
    owner: "不在（store は無検証、検証は SettingsViewModel に 1 件のみ）"
    target_status: conflicting
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["preferences/DataStorePreferencesStore.kt:81-107"]}
    evidence: {status: confirmed, sources: ["preferences/DataStorePreferencesStore.kt:81-115", "settings/SettingsViewModel.kt:193-197", "auth/AuthViewModel.kt:124-126"]}
    note: "値の source_of_truth（OW5・unique）と invariant_owner（OW6・不在）を区別する。正本が一意でも値域が守られるとは限らない"
  - id: OW7
    matrix_element_id: ME8
    authority_type: invariant_owner
    subject_element_ids: [ME8]
    owner: "不在（skipForward/skipBackward は clamp、seekTo は素通し、ExoPlayer 側にも client 側 clamp なし）"
    target_status: conflicting
    transition_controls: {status: not_applicable, transition_period: "", conflict_rule: "", reconciliation: "", removal_condition: "", owner: "", confirmation_method: "", impact_if_unresolved: "", evidence: ["podcast/PodcastViewModel.kt:444-462"]}
    evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:444-462", "podcast/ExoPlayerController.kt:161-167"]}
```

## 10. 不正状態 IV*（型が許すが意味上あり得ない組合せ）

| ID | 不正状態 | 構築経路（path:line） | 業務影響 | Evidence |
|---|---|---|---|---|
| IV1 | `queue.current == next` かつ `currentPodcast == null`（キュー上は次を再生中なのに再生セッションが無い） | `podcast/PodcastViewModel.kt:361-362`（advance で queue 前進）→ `:365` `play(next)` → `:291` `stopInternal()` が `:510` で `_currentPodcast = null` → `:293` source=NETWORK → `:306` `fetchPodcast` が `ApiException` → `:308-310` は `_errorMessage` のみ設定し `beginPlayback`（`:325`）に到達しない | プレイヤー UI（`currentPodcast` を読む）が消え、QueueSheet（両者が揃うときのみ表示）も現在項目を出さない。利用者はキューが進んだのに何も再生されていない状態に取り残される | confirmed（本セッションで全行読了） |
| IV2 | `isPlaying == false` が「一時停止」「終了」「未準備」「再生失敗」を区別しない | `podcast/ExoPlayerController.kt:89`（STATE_ENDED）・`:95-97`（STATE_IDLE で位置 0・duration null）・`:105`（onIsPlayingChanged）の 3 writer が同じ `false` を書く。`PlayerController` の公開状態は `isPlaying`/`positionSeconds`/`durationSeconds`/`playbackSpeed` の 4 本のみ（`podcast/PlayerController.kt:26-35`） | 再生失敗が「停止」と同一表現になり、利用者にも ViewModel にも失敗を告知できない。`onPlayerError` 未実装（`:71-115`）と合わせ、失敗は完全に不可視 | confirmed |
| IV3 | `AuthState.Authenticated(user)` かつ永続トークン無し | `network/KeystoreSessionStore.kt:49-52` が暗号化失敗を飲んで no-op → `auth/AuthViewModel.kt:147` の `save` は `Unit` 戻りで検査不能 → `:149` で `Authenticated` へ遷移 | 当該起動中は memory キャッシュ（`:47-48` も未更新のため実際は `cachedToken` も未設定）で API が 401 を返し続け、再起動で無言のログアウト。利用者には原因が伝わらない | confirmed |
| IV4 | 永続トークンが消えたのに `Authenticated` のまま | `network/KeystoreSessionStore.kt:71-74`（envelope deserialize 失敗）・`:83`（復号失敗）が `clearBrokenState()`（`:88-91`）で prefs を削除。`authState` への通知経路が無く、セッション中 401 → Unauthenticated の遷移（ME34）も存在しない | 認証済み UI のまま全 API が失敗し、利用者は「アプリが壊れた」と観測する。R5 の「認証済み UI と保存トークンを残さない」に反する | confirmed |
| IV5 | `LearningUiState(isLoading = true, loadFailed = true)` / `(isLoading = false, dashboard = null, loadFailed = false)` | `learning/LearningViewModel.kt:15-23` の public data class（既定値つき・`copy` 可）。`:34` が `isLoading = true` を立て、`:38` が `isLoading = false, loadFailed = true` を書くが、`copy` の組合せ自体は 8 通り構築できる。`:56-62` の成功パスは `loadFailed` を明示的に false へ戻さない新 instance を作る | 前者は「読込中かつ失敗」で Screen の排他判定（`LearningScreen.kt:72,86`・`dashboard!!` 6 箇所。confirmed_by_router）が任意順序に依存する。後者は「初期状態」と「取得成功だが dashboard 無し」を区別できない | confirmed（ViewModel 側）/ confirmed_by_router（Screen 側） |
| IV6 | `weeklyGoalEpisodes ∉ {3,5,7,10}` | `auth/AuthViewModel.kt:126` が server 値を `preferencesStore.setWeeklyGoalEpisodes` へ無検証で渡す → `preferences/DataStorePreferencesStore.kt:105-107` が無条件に永続化。`settings/SettingsViewModel.kt:193-197` の検証は UI 由来経路だけを守る | Settings の選択肢にどれも一致せず「未選択」に見える。週目標の達成判定が定義外の値で行われる | confirmed |
| IV7 | `defaultPlaybackSpeed` が許容集合外（例 0.0 / 12.0） | `auth/AuthViewModel.kt:125` → `preferences/DataStorePreferencesStore.kt:85-87`（無検証）。`PlayerController.setSpeed(Float)`（`podcast/PlayerController.kt:63`）にも値域が無い | 現状は既定速度が player へ適用されないため無音だが（G4）、G4 を解消した瞬間に 0.0 倍速（無音再生）や極端な速度が実再生へ流れる。潜在的な回帰の種 | confirmed |
| IV8 | `PlaybackQueue(items = [a,b], currentIndex = 5)`（非空キューで current も next も無く、advance が永久に停止を返す） | `core/PlaybackQueue.kt:23-26` の public primary constructor と data class の `copy` に `init` 検証が無い。`current`（`:27-29`）は `getOrNull` で null、`upNext`（`:32-33`）は `drop(6)` で空、`advance()`（`:84-91`）は `next=6 >= size` で `(this, null)` を返し続ける | 共有仕様 §2.1 の状態モデル（spec:39-57）が前提とする「currentIndex は範囲内または null」を型で守っていない。テスト・将来の永続化復元・他 writer が到達しうる | confirmed |
| IV9 | `isCached(id) == true` だが中身が再生不能（破損・部分・非音声） | `network/AudioCacheManager.kt:54-62` が `bytes` を無検証で書く（`downloadAudio` の非 2xx は `OkHttpApiClient` 側で例外化されるが、2xx で返った本文の妥当性は誰も検査しない）→ `core/PlaybackSourceResolver.kt:17-19` はキャッシュ有を常に CACHED と判定 | 当該エピソードは恒久的に再生不可になる。CACHED 経路は `fetchPodcast` を通らず、失敗は ME16 の欠落により観測もされないため、利用者は「押しても何も起きない」状態から自力で復帰できない（`removeDownload` を知っていれば回避可能） | confirmed |

## 11. gaps G*

```yaml
gaps:
  - {id: G1,  requirement_ids: [R2, R4], element_ids: [ME6], kind: missing_state, severity: blocker, impact: "再生の失敗・準備中・終了を表現できず、IV2 を構造的に生む。利用者への告知も ViewModel の分岐も不可能", evidence: [{status: confirmed, sources: ["podcast/PlayerController.kt:26-35", "podcast/ExoPlayerController.kt:55-56,87-98,104-112"]}], contract_obligation_ids: [OB-C1], test_obligation_ids: [OB-T1]}
  - {id: G2,  requirement_ids: [R2], element_ids: [ME16], kind: missing_failure, severity: blocker, impact: "Media3 の onPlayerError を購読しないため、source 不到達・デコード失敗が完全に不可視。G1 と合わせて「押しても鳴らない」が唯一の症状になる", evidence: [{status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115"]}], contract_obligation_ids: [OB-C2], test_obligation_ids: [OB-T2]}
  - {id: G3,  requirement_ids: [R3, R9], element_ids: [ME5, ME23], kind: missing_behavior, severity: major, impact: "spec:298-304 の resolveResumePosition 相当が存在せず、15 秒ごとに書いた位置を誰も読まない。続きから聴く機能が成立しない（書き専用）", evidence: [{status: confirmed, sources: ["model/PodcastResponse.kt:31", "podcast/PodcastViewModel.kt:324-331,514-542", "spec:298-304"]}], contract_obligation_ids: [OB-C3], test_obligation_ids: [OB-T3]}
  - {id: G4,  requirement_ids: [R3], element_ids: [ME13, ME83], kind: missing_behavior, severity: major, impact: "既定速度は保存・server 同期されるが再生に適用されない（REL3 未配線）。利用者には設定が無効に見える", evidence: [{status: confirmed, sources: ["preferences/PreferencesStore.kt:21-22", "podcast/PodcastViewModel.kt:324-331,464-467"]}], contract_obligation_ids: [OB-C4], test_obligation_ids: [OB-T4]}
  - {id: G5,  requirement_ids: [R2, R3], element_ids: [ME1, ME12, ME22], kind: authority_conflict, severity: blocker, impact: "「現在再生中」の state authority が 2 つあり、IV1 を公開経路から構築できる。整合は手続き（:363-368）で守られており、新しい遷移を追加するたびに再実装が必要", evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:83-86,115-121,341-346,361-368,502-512"]}], contract_obligation_ids: [OB-C5], test_obligation_ids: [OB-T5]}
  - {id: G6,  requirement_ids: [R2], element_ids: [ME8, ME9], kind: missing_constraint, severity: major, impact: "seekTo は clamp せず（skip 系のみ clamp）、速度も任意 Float を受ける。値域の invariant owner が不在（OW7）", evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:444-462", "podcast/PlayerController.kt:63", "podcast/PlaybackConstants.kt:12"]}], contract_obligation_ids: [OB-C6], test_obligation_ids: [OB-T6]}
  - {id: G7,  requirement_ids: [R5], element_ids: [ME30, ME31, ME33], kind: missing_concept, severity: blocker, impact: "失効と一時障害を区別する概念が無く、NetworkError でトークンを破棄する。機内モード起動やサーバ 5xx で強制再ログインになる", evidence: [{status: confirmed, sources: ["auth/AuthViewModel.kt:96-97,105-115", "network/ApiException.kt:16,19"]}], contract_obligation_ids: [OB-C7], test_obligation_ids: [OB-T7]}
  - {id: G8,  requirement_ids: [R5], element_ids: [ME34], kind: missing_transition, severity: blocker, impact: "セッション中に server がトークンを失効させても Authenticated のまま。IV4 の主要因", evidence: [{status: confirmed, sources: ["network/ApiException.kt:8-20"]}, {status: confirmed_by_router, sources: ["ブリーフ §4（AuthInterceptor.kt:26-39 はヘッダ付与のみ）"]}], contract_obligation_ids: [OB-C8], test_obligation_ids: [OB-T8]}
  - {id: G9,  requirement_ids: [R6, R9], element_ids: [ME36, ME40], kind: missing_behavior, severity: major, impact: "主体離脱の事後条件が logout 経路のみ・かつ音声と FCM のみ。失効経路には cleanup が無く、logout でも preferences 8 key と再生状態（_queue/_currentPodcast/player）が残る", evidence: [{status: confirmed, sources: ["auth/AuthViewModel.kt:111-114,167-182", "di/AppContainer.kt:266-281", "preferences/DataStorePreferencesStore.kt:109-127", "spec:305-317"]}], contract_obligation_ids: [OB-C9, OB-C10], test_obligation_ids: [OB-T9, OB-T10]}
  - {id: G10, requirement_ids: [R1, R4], element_ids: [ME51], kind: missing_failure, severity: blocker, impact: "失敗の語彙に unauthorized / forbidden / not_found / conflict / server が無い。R4 が要求する意味を消費者へ渡せない", evidence: [{status: confirmed, sources: ["network/ApiException.kt:8-20", "network/OkHttpApiClient.kt:456-465"]}], contract_obligation_ids: [OB-C11], test_obligation_ids: [OB-T11]}
  - {id: G11, requirement_ids: [R1, R4], element_ids: [ME50, ME57, ME58, ME60], kind: leakage, severity: major, impact: "HTTP code の業務解釈が 8 箇所（Screen 層 1 件を含む）へ分散し、404 に 3 意味・409 に 1 意味が consumer 側で再発明されている", evidence: [{status: confirmed, sources: ["settings/SettingsViewModel.kt:158-160", "network/OkHttpApiClient.kt:299-305"]}, {status: confirmed_by_router, sources: ["ブリーフ §4・§5"]}], contract_obligation_ids: [OB-C12], test_obligation_ids: [OB-T12]}
  - {id: G12, requirement_ids: [R4], element_ids: [ME54], kind: leakage, severity: major, impact: "例外 message（\"HTTP Error 500\" / \"Network error: <cause.message>\"）が 8 箇所で UI 文言になる。利用者に無意味で、内部詳細も露出する", evidence: [{status: confirmed, sources: ["network/ApiException.kt:13,19", "podcast/PodcastViewModel.kt:135,212,214,251,309", "feed/FeedViewModel.kt:89,106,204"]}], contract_obligation_ids: [OB-C13], test_obligation_ids: [OB-T13]}
  - {id: G13, requirement_ids: [R1, R4], element_ids: [ME52], kind: missing_concept, severity: minor, impact: "quota の期間（日次/月次）概念が無く、全 RateLimited を「本日の生成上限」と断定する。月次上限が導入されれば文言が誤りになる", evidence: [{status: confirmed, sources: ["feed/FeedViewModel.kt:290-303"]}], contract_obligation_ids: [OB-C14], test_obligation_ids: [OB-T14]}
  - {id: G14, requirement_ids: [R3], element_ids: [ME71, ME73, ME74, ME75, ME79], kind: missing_constraint, severity: major, impact: "設定値の invariant owner が不在（OW6）。server 同期・Screen 直書き・DataStore 復元の 3 経路が検証を迂回し IV6/IV7 を構築できる", evidence: [{status: confirmed, sources: ["preferences/DataStorePreferencesStore.kt:41-49,81-87,105-107", "auth/AuthViewModel.kt:124-126", "settings/SettingsViewModel.kt:193-197"]}], contract_obligation_ids: [OB-C15], test_obligation_ids: [OB-T15]}
  - {id: G15, requirement_ids: [R1, R2], element_ids: [ME77], kind: invalid_state, severity: major, impact: "LearningUiState が 3 意味を直積で表し 8 通り構築可能（IV5）。Screen が組合せ条件で排他を復元し `dashboard!!` に依存する", evidence: [{status: confirmed, sources: ["learning/LearningViewModel.kt:15-23,33-67"]}, {status: confirmed_by_router, sources: ["ブリーフ §5（LearningScreen.kt:72,86・dashboard!! 6 箇所）"]}], contract_obligation_ids: [OB-C16], test_obligation_ids: [OB-T16]}
  - {id: G16, requirement_ids: [R2, R9], element_ids: [ME10], kind: invalid_state, severity: major, impact: "共有 core の PlaybackQueue が範囲外 currentIndex を public constructor / copy で受け付ける（IV8）。spec:39-57 の状態モデル前提が型で守られていない", evidence: [{status: confirmed, sources: ["core/PlaybackQueue.kt:23-33,84-91", "spec:39-57"]}], contract_obligation_ids: [OB-C17], test_obligation_ids: [OB-T17]}
  - {id: G17, requirement_ids: [R2, R9], element_ids: [ME17], kind: missing_failure, severity: major, impact: "キャッシュの完全性概念が無く、破損キャッシュが恒久的な再生不能（IV9）になる。CACHED は常に優先されるため自動復旧しない", evidence: [{status: confirmed, sources: ["network/AudioCacheManager.kt:54-62", "core/PlaybackSourceResolver.kt:16-21"]}], contract_obligation_ids: [OB-C18], test_obligation_ids: [OB-T18]}
  - {id: G18, requirement_ids: [R9], element_ids: [ME19], kind: missing_time, severity: minor, impact: "一時停止中も 15 秒ごとに位置を PATCH し続ける。「聴取していること」と「位置」の意味が混同されており、ストリーク集計の意味が不明確（コード自身が spec 改訂候補と自認）", evidence: [{status: confirmed, sources: ["podcast/PodcastViewModel.kt:514-533"]}], contract_obligation_ids: [OB-C19], test_obligation_ids: [OB-T19]}
  - {id: G19, requirement_ids: [R5, R7], element_ids: [ME38, ME41, ME43], kind: missing_writer, severity: major, impact: "SessionStore の save が失敗を報告せず（ME38）、clearBrokenState が authState に通知しない alternate writer になっている（IV3/IV4）。さらに KeystoreSessionStore は単体テスト対象外と自認されており、この writer の契約は検証されていない", evidence: [{status: confirmed, sources: ["network/SessionStore.kt:12-19", "network/KeystoreSessionStore.kt:20-27,40-53,69-91"]}], contract_obligation_ids: [OB-C20], test_obligation_ids: [OB-T20]}
```

## 12. destruction_probes（思考実験。本番 data への破壊操作なし）

```yaml
destruction_probes:
  - id: DP1
    requirement_ids: [R2, R3]
    writer_access_path_id: AP2
    entry_point: "podcast/PodcastViewModel.kt:348 handlePlaybackEnded（onPlaybackCompleted から）"
    destructive_input_or_sequence: "キュー [A, B] を再生中に A が完聴 → advance で B へ → B は未キャッシュ・オンライン → fetchPodcast(B) を ApiException.NetworkError にする"
    propagation: ["stopInternal が _currentPodcast=null", "queue.currentIndex は B を指す", "beginPlayback 未到達", "_errorMessage に \"Network error: ...\" のみ"]
    business_impact: ["プレイヤー UI が消失", "QueueSheet が現在項目を表示しない", "再開手段は利用者が一覧から再タップするしかない"]
    expected_invariant: "遷移後は currentPodcast == queue.current、または両方 null"
    observed_result: {status: constructed, evidence: ["podcast/PodcastViewModel.kt:291,304-311,361-368,502-512"]}
    defense_assessment: {status: absent, mechanisms: ["handlePlaybackEnded の事前ゲート（:363-367）は「再生可否」だけを見て NETWORK 失敗を防げない"], evidence: ["podcast/PodcastViewModel.kt:341-346"]}
    gap: {kind: invalid_state, element_ids: [ME12, ME22]}
    obligations: {applicability: required, rationale: "R2 の不変条件違反を型または遷移関数で防ぐ必要がある", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C5], test_obligation_ids: [OB-T5]}
  - id: DP2
    requirement_ids: [R5]
    writer_access_path_id: AP14
    entry_point: "auth/AuthViewModel.kt:99 refreshAuth（アプリ起動）"
    destructive_input_or_sequence: "有効トークン保存済み・機内モードで起動 → apiClient.me() が ApiException.NetworkError"
    propagation: ["sessionStore.clear()", "_authState = Unauthenticated"]
    business_impact: ["有効なセッションが端末から消える", "圏内復帰後も再ログインが必要", "オフラインで再生できるはずのキャッシュ済みエピソードへも到達できない（認証ゲートの先にあるため）"]
    expected_invariant: "revoked と判定できない失敗ではトークンを破棄しない"
    observed_result: {status: constructed, evidence: ["auth/AuthViewModel.kt:105-115"]}
    defense_assessment: {status: absent, mechanisms: [], evidence: ["auth/AuthViewModel.kt:96-97（catch-all を意図的と明記）"]}
    gap: {kind: missing_concept, element_ids: [ME31, ME33]}
    obligations: {applicability: required, rationale: "R5 の prohibition に直接違反する", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C7], test_obligation_ids: [OB-T7]}
  - id: DP3
    requirement_ids: [R3]
    writer_access_path_id: AP20
    entry_point: "auth/AuthViewModel.kt:121 syncPreferences（認証確立直後）"
    destructive_input_or_sequence: "server が default_playback_speed=0.0、weekly_goal_episodes=999 を返す"
    propagation: ["DataStore へ 0.0 / 999 が永続化", "Settings の選択肢はどれも一致せず未選択表示", "G4 解消後は 0.0 倍速が player へ渡る"]
    business_impact: ["週目標の達成判定が定義外", "無音再生（将来）", "端末を再インストールしない限り自己回復しない"]
    expected_invariant: "store に入る値は常に許容集合内"
    observed_result: {status: constructed, evidence: ["auth/AuthViewModel.kt:124-126", "preferences/DataStorePreferencesStore.kt:85-87,105-107"]}
    defense_assessment: {status: absent, mechanisms: ["settings/SettingsViewModel.kt:193-197 は UI 由来経路のみ"], evidence: ["settings/SettingsViewModel.kt:193-197"]}
    gap: {kind: missing_constraint, element_ids: [ME71, ME73, ME75]}
    obligations: {applicability: required, rationale: "alternate writer が検証を迂回している", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C15], test_obligation_ids: [OB-T15]}
  - id: DP4
    requirement_ids: [R2, R9]
    writer_access_path_id: AP4
    entry_point: "core/PlaybackQueue.kt:23 primary constructor / copy"
    destructive_input_or_sequence: "PlaybackQueue(items = [a, b], currentIndex = 5)"
    propagation: ["current == null", "upNext == []", "advance() が無限に (this, null) を返す"]
    business_impact: ["非空キューが恒久的に停止状態", "spec §2.1 の状態モデル前提が破れる"]
    expected_invariant: "currentIndex == null または 0 <= currentIndex < items.size"
    observed_result: {status: constructed, evidence: ["core/PlaybackQueue.kt:23-33,84-91"]}
    defense_assessment: {status: absent, mechanisms: ["setQueue のみ coerceIn で守る（:38-44）。constructor 直呼びは素通し"], evidence: ["core/PlaybackQueue.kt:38-44"]}
    gap: {kind: invalid_state, element_ids: [ME10]}
    obligations: {applicability: required, rationale: "共有仕様の状態モデルを型で守る必要がある", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C17], test_obligation_ids: [OB-T17]}
  - id: DP5
    requirement_ids: [R2, R9]
    writer_access_path_id: AP1
    entry_point: "network/AudioCacheManager.kt:54 cache（download 経由）"
    destructive_input_or_sequence: "downloadAudio が 200 で HTML エラーページ本文（または途中で切れた bytes）を返す → cache へ書き込み → play"
    propagation: ["isCached=true → PlaybackSource.CACHED", "ExoPlayer が STATE_IDLE で失敗", "onPlayerError 未購読のため誰も気づかない", "_isPlaying=false・position=0.0 に戻るだけ"]
    business_impact: ["当該エピソードが恒久的に再生不能", "「ダウンロード済み」表示は残る", "利用者は removeDownload を知らないと復旧できない"]
    expected_invariant: "キャッシュ済みと表示されるものは再生可能である、または失敗が観測されて自動的に無効化される"
    observed_result: {status: partially_observed, evidence: ["network/AudioCacheManager.kt:54-62", "core/PlaybackSourceResolver.kt:17-19", "podcast/ExoPlayerController.kt:93-98"]}
    defense_assessment: {status: absent, mechanisms: ["OkHttpApiClient.validateResponse は非 2xx のみ弾く（:456-465）"], evidence: ["network/OkHttpApiClient.kt:452-465"]}
    gap: {kind: missing_failure, element_ids: [ME16, ME17]}
    obligations: {applicability: required, rationale: "失敗が表現も観測もされないため、検知と無効化の契約が必要", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C2, OB-C18], test_obligation_ids: [OB-T2, OB-T18]}
  - id: DP6
    requirement_ids: [R6]
    writer_access_path_id: AP15
    entry_point: "auth/AuthViewModel.kt:167 logout"
    destructive_input_or_sequence: "利用者 A が再生中・設定変更済みで logout → 利用者 B が同一端末でログイン"
    propagation: ["音声キャッシュと FCM トークンは削除（di/AppContainer.kt:266-281）", "preferences 8 key は残存（削除 API が存在しない）", "_queue / _currentPodcast / playerController は停止も初期化もされない"]
    business_impact: ["B が A の既定難易度・再生速度・週目標・実績既読を引き継ぐ", "B のプレイヤーに A のエピソードが表示されうる"]
    expected_invariant: "主体が離れたらユーザー固有 data が残らない（R6 / spec:305-317）"
    observed_result: {status: constructed, evidence: ["auth/AuthViewModel.kt:167-182", "di/AppContainer.kt:266-281", "preferences/PreferencesStore.kt:42-60（clear 系 API なし）"]}
    defense_assessment: {status: absent, mechanisms: [], evidence: ["preferences/DataStorePreferencesStore.kt:109-127"]}
    gap: {kind: missing_behavior, element_ids: [ME36]}
    obligations: {applicability: required, rationale: "R6 の事後条件が部分的にしか実装されていない", confirmation_method: "", impact_if_unresolved: "", contract_obligation_ids: [OB-C9, OB-C10], test_obligation_ids: [OB-T9, OB-T10]}
  - id: DP7
    requirement_ids: [R5]
    writer_access_path_id: AP16
    entry_point: "network/KeystoreSessionStore.kt:69 load（端末バックアップ復元後の初回起動）"
    destructive_input_or_sequence: "Keystore の鍵が失われた状態で load → 復号失敗 → clearBrokenState で prefs 削除 → その後 refreshAuth 以外の経路で API 呼び出し"
    propagation: ["トークンが消える", "authState は Unknown / Authenticated のまま（通知経路が無い）", "AuthInterceptor は Authorization ヘッダを付けずに送る", "server は 401 を返すが ME34 が無いため状態遷移しない"]
    business_impact: ["認証済み UI のまま全機能が失敗", "利用者は原因不明の全面エラーを見る"]
    expected_invariant: "トークンが失われたら未認証状態へ遷移する"
    observed_result: {status: partially_observed, evidence: ["network/KeystoreSessionStore.kt:69-91", "auth/AuthState.kt:13-22"]}
    defense_assessment: {status: unknown, mechanisms: ["起動時は refreshAuth → load()==null → Unauthenticated で正しく遷移する（auth/AuthViewModel.kt:100-104）。問題は起動後に load が初めて失敗する順序のときだけ"], evidence: ["auth/AuthViewModel.kt:99-104", "network/KeystoreSessionStore.kt:55-61"]}
    gap: {kind: missing_transition, element_ids: [ME34, ME41]}
    obligations: {applicability: unknown, rationale: "cacheLoaded のメモリキャッシュ機構により、起動時 refreshAuth が必ず先に load を呼ぶなら到達不能な可能性がある", confirmation_method: "アプリ起動シーケンス（NewsListenApplication / AppContainer の初期化順と refreshAuth 呼び出し位置）を読み、load の初回呼び出しが常に refreshAuth 内で起きるかを確認する", impact_if_unresolved: "IV4 の到達可能性が確定せず、G8 の severity を blocker と判断する根拠が 1 つ減る（G8 自体は 401 経路で独立に blocker）", contract_obligation_ids: [OB-C8], test_obligation_ids: [OB-T8]}
```

## 13. obligations（contract / test）

契約条件・テストは本 Skill では作らない。下流 Function（`mino-design-by-contract` → CI* / T*）への obligation として返す。未作成 ID は捏造しない。

```yaml
contract_obligations:
  - {id: OB-C1,  requirement_ids: [R2, R4], model_element_ids: [ME6], required_contract_kind: invariant, statement: "PlayerController は再生状態を idle|preparing|playing|paused|ended|failed(reason) を区別できる単一の値として公開し、isPlaying はそこから導出される", evidence: {status: confirmed, sources: ["podcast/PlayerController.kt:26-35"]}}
  - {id: OB-C2,  requirement_ids: [R2], model_element_ids: [ME16], required_contract_kind: failure_guarantee, statement: "実装は player の失敗（source/decode/network）を PlaybackFailure として観測可能にし、利用者へ告知可能な意味で公開する", evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115"]}}
  - {id: OB-C3,  requirement_ids: [R3, R9], model_element_ids: [ME5, ME23], required_contract_kind: postcondition, statement: "再生開始後の position は resolveResumePosition(server, local) の結果に等しい（spec:298-304 の server-wins。純関数として core に置く）", evidence: {status: confirmed, sources: ["spec:298-304", "podcast/PodcastViewModel.kt:324-331"]}}
  - {id: OB-C4,  requirement_ids: [R3], model_element_ids: [ME13, ME83], required_contract_kind: postcondition, statement: "再生開始後の playbackSpeed は、セッション中に明示指定が無い限り preferencesStore.defaultPlaybackSpeed に等しい", evidence: {status: confirmed, sources: ["preferences/PreferencesStore.kt:21-22", "podcast/PodcastViewModel.kt:324-331"]}}
  - {id: OB-C5,  requirement_ids: [R2, R3], model_element_ids: [ME1, ME12, ME22], required_contract_kind: invariant, statement: "再生セッションの遷移完了後、currentPodcast は queue.current と同一 id か、両方 null である（NETWORK 失敗時は queue を巻き戻すか currentPodcast を維持するかを契約で確定する）", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:361-368,502-512"]}}
  - {id: OB-C6,  requirement_ids: [R2], model_element_ids: [ME8, ME9], required_contract_kind: precondition, statement: "seekTo は 0 <= seconds <= (duration ?: seconds) に正規化してから実装へ渡す。setSpeed は PlaybackConstants.speeds の要素のみ受け付ける", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:444-462", "podcast/PlaybackConstants.kt:12"]}}
  - {id: OB-C7,  requirement_ids: [R5], model_element_ids: [ME31, ME33], required_contract_kind: prohibited_transition, statement: "unauthorized 以外の失敗（network / decoding / server）では sessionStore.clear() を呼ばず、Authenticated / Unknown を維持したまま再試行可能な状態へ遷移する", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:105-115"]}}
  - {id: OB-C8,  requirement_ids: [R5], model_element_ids: [ME34], required_contract_kind: postcondition, statement: "セッション中に unauthorized を受けた場合、authState は Unauthenticated へ遷移し保存トークンは削除される", evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20"]}}
  - {id: OB-C9,  requirement_ids: [R6], model_element_ids: [ME36], required_contract_kind: postcondition, statement: "主体が離れる遷移（logout と失効の両方）の完了後、ユーザー固有 data（音声キャッシュ・FCM トークン・server 同期 preferences・再生セッション）が端末に残らない", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:111-114,167-182", "spec:305-317"]}}
  - {id: OB-C10, requirement_ids: [R6], model_element_ids: [ME36, ME40], required_contract_kind: idempotency, statement: "cleanup は部分失敗しても再実行で同じ事後条件へ到達し、失敗した構成要素が観測可能である（現状は各 try/catch が沈黙）", evidence: {status: confirmed, sources: ["di/AppContainer.kt:266-281"]}}
  - {id: OB-C11, requirement_ids: [R1, R4], model_element_ids: [ME51], required_contract_kind: postcondition, statement: "API 失敗は network | unauthorized | forbidden | rate_limited(retryAfter) | not_found | conflict | server のいずれかの意味として消費者へ渡る", evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20", "network/OkHttpApiClient.kt:456-465"]}}
  - {id: OB-C12, requirement_ids: [R1, R4], model_element_ids: [ME50, ME53, ME57], required_contract_kind: invariant, statement: "consumer（ViewModel / Screen）は HTTP status 数値を参照しない。404 の 3 意味（機能未提供・冪等成功・リソース消失）は呼び出しごとの契約として endpoint 側で確定する", evidence: {status: confirmed, sources: ["settings/SettingsViewModel.kt:158-160", "network/OkHttpApiClient.kt:299-305"]}}
  - {id: OB-C13, requirement_ids: [R4], model_element_ids: [ME54], required_contract_kind: invariant, statement: "UI へ出す文言は失敗の意味から決まり、例外 message / transport 文字列を含まない", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:135,212,214,251,309", "feed/FeedViewModel.kt:89,106,204"]}}
  - {id: OB-C14, requirement_ids: [R1, R4], model_element_ids: [ME52], required_contract_kind: postcondition, statement: "生成上限の文言は quota の期間（日次 / 月次 / 不明）を入力として決まり、rate_limited を無条件に「本日」と断定しない", evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:290-303"]}}
  - {id: OB-C15, requirement_ids: [R3], model_element_ids: [ME71, ME73, ME74, ME75], required_contract_kind: precondition, statement: "PreferencesStore の各 setter は許容集合外の値を拒否する（server 同期・UI・DataStore 復元の全経路が同じ検証を通る）", evidence: {status: confirmed, sources: ["preferences/DataStorePreferencesStore.kt:41-49,81-107", "auth/AuthViewModel.kt:124-126"]}}
  - {id: OB-C16, requirement_ids: [R1, R2], model_element_ids: [ME77], required_contract_kind: invariant, statement: "LearningUiState は Loading | Loaded(dashboard) | Failed(reason) を排他的に表し、Screen が組合せから排他性を復元しない", evidence: {status: confirmed, sources: ["learning/LearningViewModel.kt:15-23"]}}
  - {id: OB-C17, requirement_ids: [R2, R9], model_element_ids: [ME10], required_contract_kind: invariant, statement: "PlaybackQueue は currentIndex == null または 0 <= currentIndex < items.size を構築時に保証する（不正入力は拒否または正規化）", evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:23-33", "spec:39-57"]}}
  - {id: OB-C18, requirement_ids: [R2, R9], model_element_ids: [ME17], required_contract_kind: failure_guarantee, statement: "キャッシュ済みエピソードの再生が失敗した場合、当該キャッシュは無効として扱われ（削除または CACHED 判定から除外）、次回は NETWORK 経路へ退避する", evidence: {status: confirmed, sources: ["network/AudioCacheManager.kt:54-62", "core/PlaybackSourceResolver.kt:16-21"]}}
  - {id: OB-C19, requirement_ids: [R9], model_element_ids: [ME19], required_contract_kind: environment_condition, statement: "位置同期の送信条件（再生中のみ / 一時停止中も継続）を spec 側で明示し、実装がそれに一致する。SG1 の決定が前提", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:514-533"]}}
  - {id: OB-C20, requirement_ids: [R5, R7], model_element_ids: [ME38, ME41], required_contract_kind: failure_guarantee, statement: "SessionStore.save は保存の成否を呼び出し元へ返し、失敗時に Authenticated へ遷移しない。トークンを削除する全 writer（clearBrokenState を含む）は authState へ通知する", evidence: {status: confirmed, sources: ["network/SessionStore.kt:12-19", "network/KeystoreSessionStore.kt:40-53,88-91"]}}

test_obligations:
  - {id: OB-T1,  requirement_ids: [R2], model_element_ids: [ME6], contract_obligation_ids: [OB-C1], scenario: "prepare 直後 / play 後 / pause 後 / 終了後 / 失敗後の 5 点で状態値が相互に区別できる", required_oracle: "公開された再生状態値の等価比較（isPlaying の Boolean では不可）", evidence: {status: confirmed, sources: ["podcast/PlayerController.kt:26-35"]}}
  - {id: OB-T2,  requirement_ids: [R2], model_element_ids: [ME16], contract_obligation_ids: [OB-C2], scenario: "到達不能 URL / 非音声バイト列を prepare して play したとき、失敗が観測可能な値として公開される", required_oracle: "PlaybackFailure の発生と reason", evidence: {status: confirmed, sources: ["podcast/ExoPlayerController.kt:71-115"]}}
  - {id: OB-T3,  requirement_ids: [R3, R9], model_element_ids: [ME5], contract_obligation_ids: [OB-C3], scenario: "server 位置 120 秒・ローカル 30 秒で再生開始 → seek 先が 120 秒（server-wins）。純関数 resolveResumePosition の表駆動テストと、beginPlayback が seek を呼ぶ結合テスト", required_oracle: "seekTo の引数", evidence: {status: confirmed, sources: ["spec:298-304"]}}
  - {id: OB-T4,  requirement_ids: [R3], model_element_ids: [ME13], contract_obligation_ids: [OB-C4], scenario: "defaultPlaybackSpeed=1.5 の store を注入して play → playbackSpeed が 1.5", required_oracle: "setSpeed の引数 / playbackSpeed の値", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:324-331"]}}
  - {id: OB-T5,  requirement_ids: [R2, R3], model_element_ids: [ME12], contract_obligation_ids: [OB-C5], scenario: "DP1 の系列（完聴 → advance → 次が NETWORK 失敗）で currentPodcast と queue.current が乖離しない", required_oracle: "currentPodcast?.id == queue.current?.id または両方 null", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:361-368"]}}
  - {id: OB-T6,  requirement_ids: [R2], model_element_ids: [ME8, ME9], contract_obligation_ids: [OB-C6], scenario: "seekTo(-10) / seekTo(duration+100) / setSpeed(0f) / setSpeed(9f) が正規化または拒否される", required_oracle: "実装へ渡った値、または投げられた失敗", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:444-462"]}}
  - {id: OB-T7,  requirement_ids: [R5], model_element_ids: [ME31], contract_obligation_ids: [OB-C7], scenario: "DP2 の系列（トークンあり・me() が NetworkError）でトークンが保持され、状態が Unauthenticated にならない。401 のときだけ破棄される対照ケースも置く", required_oracle: "sessionStore.load() != null かつ authState != Unauthenticated", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:105-115"]}}
  - {id: OB-T8,  requirement_ids: [R5], model_element_ids: [ME34], contract_obligation_ids: [OB-C8], scenario: "認証済み状態で任意 API が unauthorized を返したとき authState が Unauthenticated になり token が消える", required_oracle: "authState と sessionStore.load()", evidence: {status: confirmed, sources: ["network/ApiException.kt:8-20"]}}
  - {id: OB-T9,  requirement_ids: [R6], model_element_ids: [ME36], contract_obligation_ids: [OB-C9], scenario: "DP6 の系列（logout）後に preferences 8 key が既定値へ戻り、_queue / _currentPodcast が空で player が停止している。失効経路（401 / me() 401）でも同じ事後条件を検査する", required_oracle: "store の各 StateFlow 値・queue・currentPodcast・player の停止", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:167-182", "spec:305-317"]}}
  - {id: OB-T10, requirement_ids: [R6], model_element_ids: [ME36], contract_obligation_ids: [OB-C10], scenario: "cleanup の一部（FCM 解除）だけを失敗させ、失敗が観測可能で、再実行が同じ事後条件へ到達する", required_oracle: "cleanup 結果の可観測値と再実行後の状態", evidence: {status: confirmed, sources: ["di/AppContainer.kt:266-281"]}}
  - {id: OB-T11, requirement_ids: [R1, R4], model_element_ids: [ME51], contract_obligation_ids: [OB-C11], scenario: "MockWebServer で 401 / 403 / 404 / 409 / 429(Retry-After) / 500 / 接続断 を返し、production 経路（OkHttpApiClient）が意味ごとに区別できる失敗を返す", required_oracle: "返された失敗の意味の等価比較", evidence: {status: confirmed, sources: ["network/OkHttpApiClient.kt:452-465"]}}
  - {id: OB-T12, requirement_ids: [R1, R4], model_element_ids: [ME50, ME58], contract_obligation_ids: [OB-C12], scenario: "consumer（ViewModel / Screen）のソースに HTTP status 数値リテラルが現れないことを構造検査し、404 の 3 意味それぞれに endpoint 単位の振る舞いテストを置く", required_oracle: "静的検査（code 比較 0 箇所）＋各 endpoint の振る舞い", evidence: {status: confirmed_by_router, sources: ["ブリーフ §4（code 比較 8 箇所）"]}}
  - {id: OB-T13, requirement_ids: [R4], model_element_ids: [ME54], contract_obligation_ids: [OB-C13], scenario: "500 / 接続断で errorMessage が \"HTTP Error 500\" / \"Network error: ...\" を含まず、意味に対応した固定文言になる", required_oracle: "errorMessage の文字列", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:135,309", "feed/FeedViewModel.kt:89,106,204"]}}
  - {id: OB-T14, requirement_ids: [R1, R4], model_element_ids: [ME52], contract_obligation_ids: [OB-C14], scenario: "quota 期間が日次 / 月次 / 不明の 3 入力で文言が変わる（表駆動）", required_oracle: "生成された文言", evidence: {status: confirmed, sources: ["feed/FeedViewModel.kt:290-303"]}}
  - {id: OB-T15, requirement_ids: [R3], model_element_ids: [ME73, ME74, ME75], contract_obligation_ids: [OB-C15], scenario: "DP3 の系列（server が 0.0 / 999 / 未知 difficulty を返す）で store の値が既定または直前の妥当値に留まる。Screen 直書き経路・DataStore に不正値が入った状態からの読み出しも同様に検査する", required_oracle: "store の StateFlow 値", evidence: {status: confirmed, sources: ["auth/AuthViewModel.kt:124-126", "preferences/DataStorePreferencesStore.kt:41-49"]}}
  - {id: OB-T16, requirement_ids: [R1, R2], model_element_ids: [ME77], contract_obligation_ids: [OB-C16], scenario: "load 中 / 成功 / dashboard 取得失敗 / dashboard 成功かつ従属取得失敗 の 4 系列で状態が排他的な 1 値になり、Screen 側の組合せ判定と `!!` が不要になる", required_oracle: "UiState の型と値", evidence: {status: confirmed, sources: ["learning/LearningViewModel.kt:33-67"]}}
  - {id: OB-T17, requirement_ids: [R2, R9], model_element_ids: [ME10], contract_obligation_ids: [OB-C17], scenario: "DP4 の入力（items 2 件・currentIndex 5、負値、items 空で currentIndex 0）が拒否または正規化される。既存の Q-* conformance は不変のまま green を維持する", required_oracle: "構築結果または投げられた失敗、および Q-01〜Q-32 の再実行", evidence: {status: confirmed, sources: ["core/PlaybackQueue.kt:23-33"]}}
  - {id: OB-T18, requirement_ids: [R2, R9], model_element_ids: [ME17], contract_obligation_ids: [OB-C18], scenario: "DP5 の系列（破損キャッシュ）で再生失敗後に isCached が false になる（または CACHED に選ばれなくなる）", required_oracle: "isCached / resolvePlaybackSource の結果", evidence: {status: confirmed, sources: ["network/AudioCacheManager.kt:54-69"]}}
  - {id: OB-T19, requirement_ids: [R9], model_element_ids: [ME19], contract_obligation_ids: [OB-C19], scenario: "SG1 の決定に従い、一時停止 15 秒後に PATCH が送られる / 送られないことを検査する", required_oracle: "updatePlaybackPosition の呼び出し回数", evidence: {status: confirmed, sources: ["podcast/PodcastViewModel.kt:514-533"]}}
  - {id: OB-T20, requirement_ids: [R5, R7], model_element_ids: [ME38, ME41], contract_obligation_ids: [OB-C20], scenario: "save が失敗する SessionStore を注入して login → Authenticated へ遷移しない。トークン削除 writer が authState を Unauthenticated にする", required_oracle: "authState と save の戻り値", evidence: {status: confirmed, sources: ["network/KeystoreSessionStore.kt:40-53"]}}
```

## 14. 共有仕様（shared-playback-spec.md）との差分

| ID | spec 箇所 | spec の規定 | android 実装 | 判定 |
|---|---|---|---|---|
| SD1 | `spec:298-304`（§6.2） | オフライン復帰時に `resolveResumePosition()` で server 値優先（server-wins）に調整する | 対応する関数・呼び出しが存在しない。`playback_position_seconds` はデコードされるだけ（`model/PodcastResponse.kt:31`）で、`beginPlayback`（`podcast/PodcastViewModel.kt:324-331`）は `prepare → play` のみ。位置は 15 秒ごとに書かれるが（`:514-542`）読まれない | **不在**（G3・OB-C3/OB-T3） |
| SD2 | `spec:305-317`（§6.3） | logout 時にユーザー固有キャッシュを完全削除。表に Web 行と iOS 行のみ（`spec:330-333` 相当の表本体）で **Android 行が無く、削除対象の定義が Android について未規定** | 音声キャッシュと FCM トークンのみ削除（`di/AppContainer.kt:266-281`）。preferences 8 key と再生セッションは残る。失効経路には cleanup が無い | **spec 側が不足 + 実装が部分適用**（G9・SG2） |
| SD3 | `spec:284-296`（§6.1） | キャッシュ優先で `cached | network | unavailable` を決定 | `core/PlaybackSourceResolver.kt:16-21` が一致。`podcast/PodcastViewModel.kt:293` が単一呼び出し点 | **一致** |
| SD4 | `spec:39-57`（§2.1 状態モデル） | `items` と `currentIndex` の状態モデル（current は範囲内のときのみ存在） | `core/PlaybackQueue.kt:23-33` は表現は一致するが、範囲内であることを構築時に保証しない（IV8） | **表現一致・不変条件不足**（G16） |
| SD5 | `spec:337-341` 相当（§6.3 末尾の共通方針） | logout は非同期ベストエフォート。cleanup 失敗でも即座に未認証へ遷移 | `auth/AuthViewModel.kt:173-181` が一致（Exception を飲んで clear → Unauthenticated） | **一致** |
| SD6 | §2 Q-01〜Q-32 / §3 RT-* | 表駆動 conformance | ブリーフの指示により本 review では再検証しない（router が全 ID 存在を機械照合済み） | **out_of_scope**（§1 参照） |

```yaml
existing_downstream_links:
  - {kind: conformance_test, id_space: "Q-01..Q-32", location: "app/src/test/.../PlaybackQueueConformanceTest", status: "全 ID 存在（router 機械照合済み・本 review では再検証せず）", evidence: {status: confirmed_by_router, sources: ["ブリーフ §4"]}}
  - {kind: conformance_test, id_space: "RT-01..RT-15, RT-A01, RT-A02", location: "app/src/test/.../RelativeTimeConformanceTest", status: "全 ID 存在（同上）", evidence: {status: confirmed_by_router, sources: ["ブリーフ §4"]}}
  - {kind: contract_package, id_space: "CI*/T*", location: "未作成", status: "本 package の OB-C*/OB-T* が入力になる。将来 ID は捏造しない", evidence: {status: confirmed, sources: ["本 package §13"]}}
```

## 15. Selection Gate（AI が確定せず人間へ隔離する判断）

```yaml
selection_gates:
  - id: SG1
    subject: "一時停止中の位置同期を継続するか（G18 / OB-C19）"
    options: ["現行維持（一時停止中も 15 秒ごとに PATCH）", "再生中のみ送信", "一時停止時に 1 回送って停止"]
    selection_condition: "ストリーク集計が「位置更新の存在」を入力にしているかどうか"
    evidence_acquisition: "backend の streak 集計ロジックと playback_position 更新の関係を読む"
    owner: human（プロダクト所有者）
    status: pending
    rationale_for_gate: "data の意味（「聴取した」の定義）に触れる。実装都合で決めてはならない"
  - id: SG2
    subject: "主体離脱時に消すユーザー固有 data の範囲（G9 / OB-C9）"
    options: ["音声 + FCM のみ（現行）", "音声 + FCM + server 同期 preferences + 再生セッション", "全 preferences（ローカル専用 4 key を含む）"]
    selection_condition: "共有端末での残留をどこまで許容するか。spec §6.3 に Android 行を追加する必要がある"
    evidence_acquisition: "spec 改訂（ADR-053 の「spec が正本」原則に従い spec を先に更新する）"
    owner: human（spec 所有者）
    status: pending
    rationale_for_gate: "公開契約（クロスプラットフォーム仕様）と機密性（QL4）に触れる"
  - id: SG3
    subject: "速度選択肢の正本（G6 / OB-C6）"
    options: ["PlaybackConstants の 8 段（0.5〜2.5）", "SettingsScreen の 5 段（0.75〜2.0）", "両者を統合した新集合"]
    selection_condition: "iOS PlaybackConstants.swift との一致要件と UX 判断"
    evidence_acquisition: "iOS 実装と UI 仕様の所有者へ確認"
    owner: human
    status: pending
    rationale_for_gate: "公開された UI 契約とクロスプラットフォーム一致に触れる"
  - id: SG4
    subject: "resume 戦略（G3 / OB-C3）"
    options: ["server-wins（spec 記載）", "local-wins", "max(server, local)"]
    selection_condition: "backend に server-wins が実装済みかどうか（U1）"
    evidence_acquisition: "backend の GET /podcasts/{id} と ADR-022 を読む"
    owner: human
    status: pending
    rationale_for_gate: "data の意味と複数端末間の整合に触れる。spec は server-wins と記すが実装 Evidence が未確認"
```

## 16. coverage / subject_verdict / decision

```yaml
coverage:
  # suite-defined rubric の screening coverage（対象 model の完全性そのものではない）
  audit_screen_denominator: 108        # in-scope requirement 9 × 12 suite-defined dimensions
  audit_screen_resolved_numerator: 108 # 全 cell が element ID または根拠付き DAP に一度だけ接続（§7 の会計参照）
  # applicable な model coverage
  applicable_model_denominator: 61     # 108 − 47（DAP1 2 + DAP2 1 + DAP3 12 + DAP4 8 = 23 cell が not_applicable、残り 85 のうち… 下記 note 参照）
  present_model_numerator: 14
  subject_axis_screening:              # ブリーフ指定の subject 軸（§5）
    screen_denominator: 48
    screen_resolved_numerator: 48
    applicable_denominator: 45
    present_numerator: 7
  note_on_denominators: >
    requirement 軸（108 / 61 / 14）と subject 軸（48 / 45 / 7）は同じ実体を別の分母で数えたものであり、加算しない。
    requirement 軸の applicable_model_denominator は 108 から DAP で not_applicable とした 23 cell を引いた 85 のうち、
    同一 element へ複数 requirement から接続している重複 cell 24 件を除いた 61（= 一意な requirement×dimension 組のうち
    element 接続があり N/A でないもの）である。present_model_numerator 14 は、その 61 cell のうち接続先 element 群が
    すべて present かつ Evidence が unknown / contradiction でない cell 数。
  uncovered_summary:
    missing_element_ids: [ME2, ME5, ME6, ME10, ME12, ME13, ME16, ME17, ME31, ME33, ME34, ME37, ME38, ME51, ME52, ME53, ME54, ME71, ME73, ME74, ME76, ME83, ME85]
    conflicting_element_ids: [ME1, ME8, ME9, ME15, ME18, ME19, ME20, ME22, ME30, ME36, ME40, ME41, ME43, ME50, ME55, ME57, ME58, ME60, ME61, ME70, ME75, ME77, ME79, ME80]
    unknown_element_ids: [ME23, ME44]
    present_element_ids: [ME3, ME4, ME7, ME11, ME14, ME21, ME32, ME35, ME39, ME42, ME56, ME59, ME62, ME72, ME78, ME81, ME82, ME84]

subject_verdict:
  S1: incomplete
  S2: incomplete
  S3: incomplete
  S4: incomplete
subject_verdict_rationale:
  S1: "blocker 3 件（G1 missing_state・G2 missing_failure・G5 authority_conflict）と invalid construction 4 件（IV1, IV2, IV8, IV9）を特定できる十分な Evidence がある。spec §6.2 準拠も不在（SD1）"
  S2: "blocker 2 件（G7 missing_concept・G8 missing_transition）と invalid construction 2 件（IV3, IV4）。R5/R6 の prohibition に直接違反する経路を特定した"
  S3: "blocker 1 件（G10 missing_failure）と leakage 2 件（G11, G12）。失敗の semantic_owner が consumer へ流出している（OW4 conflicting）"
  S4: "invalid construction 3 件（IV5, IV6, IV7）と invariant_owner 不在（OW6）。値の source_of_truth は一意（OW5 unique）だが値域が守られていない"

decision:
  status: pass
  artifact_readiness: ready
  engineering_status: not_started
  release_status: not_applicable
  decision_maturity:
    status: proposed
    owner: orchestrator（main session / router）
    scope: ["S1〜S4 の model completeness 監査結果", "OB-C1〜OB-C20 / OB-T1〜OB-T20 の obligation", "SG1〜SG4 の未決事項の隔離"]
    evidence_status: confirmed
    approval_evidence: []
    baseline_version: ""
    change_control: "本 package は review artifact。承認は orchestrator が独立評価（adversarial-verifier）または人間の判断で行う"
  next_phase:
    name: "契約化（mino-design-by-contract で CI* / T* を作る）"
    status: awaiting_approval
    reasons:
      - "SG1〜SG4（pending）を選択済みとして契約に組み込んではならない。SG2/SG4 は spec 改訂を伴う"
      - "U1（backend の resume 実装）が未確認のため OB-C3 の条件式が確定しない"
    human_approvals_required: [SG1, SG2, SG3, SG4]
  evidence:
    - "本セッションで読了した in-scope ファイル 19 件（§18 の引用検査結果を参照）"
    - "spec の §2.1 / §6.1 / §6.2 / §6.3 本文"
  assumptions:
    - "ブリーフ §4・§5 の観測（evidence: confirmed_by_router と記した引用）は router が実コードで確認済みという記述を信頼した。本セッションでは再読していない"
  unknowns:
    - {id: U1, subject: "backend が resume の server-wins を実装しているか（spec:298-304 の「現在の実装」が backend 側か client 側か）", confirmation_method: "backend の GET /podcasts/{id} 実装と ADR-022 を読む", impact_if_unresolved: "OB-C3 の事後条件（server-wins / local-wins / max）が確定せず SG4 も決まらない", owner: orchestrator, evidence: ["spec:298-304", "model/PodcastResponse.kt:31"]}
    - {id: U2, subject: "主体離脱時に preferences を消すべきか（spec §6.3 は音声のみ明記し Android 行が無い）", confirmation_method: "SG2 の owner 判断と spec 改訂", impact_if_unresolved: "OB-C9 の事後条件範囲が決まらず、G9 の修正が過剰または不足になる", owner: human, evidence: ["spec:305-317", "di/AppContainer.kt:266-281"]}
    - {id: U3, subject: "速度選択肢の正本（8 段 vs 5 段）", confirmation_method: "iOS PlaybackConstants.swift と UI 仕様所有者への確認", impact_if_unresolved: "OB-C6 の許容集合が定義できない", owner: human, evidence: ["podcast/PlaybackConstants.kt:12", "ブリーフ §5"]}
    - {id: U4, subject: "LearningUiState に「取得成功だが dashboard が空」という業務状態が存在するか", confirmation_method: "backend の learning dashboard 応答で dashboard が空になりうるかを確認する", impact_if_unresolved: "OB-C16 の状態数（3 か 4）が決まらない", owner: orchestrator, evidence: ["learning/LearningViewModel.kt:15-23,56-62"]}
    - {id: U5, subject: "一時停止中の位置同期がストリーク集計で意味を持つか", confirmation_method: "backend の streak 集計ロジックを読む（SG1 の Evidence 取得）", impact_if_unresolved: "OB-C19 が書けず、G18 を「仕様」と「バグ」のどちらとも判定できない", owner: human, evidence: ["podcast/PodcastViewModel.kt:514-520（コード自身が spec 改訂候補と自認）"]}
    - {id: U6, subject: "DP7 の経路（起動後に初回 load が失敗する順序）が実際に到達可能か", confirmation_method: "NewsListenApplication / AppContainer の初期化順と refreshAuth 呼び出し位置を読む", impact_if_unresolved: "IV4 の到達経路が 1 つ未確定（G8 自体は 401 経路で独立に blocker なので判定は変わらない）", owner: orchestrator, evidence: ["network/KeystoreSessionStore.kt:55-61", "auth/AuthViewModel.kt:99-104"]}
  contradictions: []
  failed_gates: []
  unexecuted_validation:
    - {id: UV1, reason: "本 review は read-only であり、テスト実行・ビルドを行っていない。OB-T* はすべて未作成・未実行", required_runner: "./gradlew testDebugUnitTest（Android Studio 同梱 JBR）", planned_commands: ["./gradlew testDebugUnitTest"], owner: orchestrator, evidence: ["ブリーフ §4（検証実行は router が verification-run.md に追記）"]}
    - {id: UV-P1, reason: "§3 platform_validation.unexecuted と同一（Android 実機依存の access path は未検証）", required_runner: "connectedAndroidTest または Robolectric", planned_commands: ["./gradlew connectedDebugAndroidTest"], owner: orchestrator, evidence: ["network/KeystoreSessionStore.kt:20-22"]}
  platform_validation:
    required_platforms: []
    executed: []
    unexecuted: [UV-P1]
    trace_not_applicable:
      reason: "単一 platform（Android）の app module であり、OS 別 representation / writer / reader の分岐が scope 内に存在しない（§3 参照）"
      evidence: ["network/AudioCacheManager.kt:37-41,64-69,89-93"]
```

## 17. 検討して採らなかった案（orchestrator が trial-log へ転記する）

| # | 目的 | 前提 | やったこと | 結果（棄却理由） | 残課題 |
|---|---|---|---|---|---|
| 1 | 12 dimension screening を requirement 軸だけで出す | 未検証: Skill の canonical coverage 定義（requirement × 12）が本依頼の唯一の分母である | requirement 9 × 12 = 108 の matrix を先に組んだ | ブリーフが subject 軸（S1〜S4）の分母・分子を明示的に要求しているため、requirement 軸だけでは要求を満たさない。両軸を併記し加算しない旨を明記する方式へ変更した | 2 軸の対応表を機械検査する手段が無い（人間または verifier の確認が必要） |
| 2 | ブリーフ §4・§5 の観測をすべて自分で再読して confirmed に格上げする | 前提: 全 path:line を自分で検証しないと Evidence 状態を confirmed と書けない | 対象 19 ファイルのうち in-scope の核（S1〜S4）は全行読了した | Screen 層（QuizSheet / SettingsScreen / LearningScreen / PodcastScreen / AudioPlayerSection / QueueSheet）は scope 外ファイルであり、再読すると読み込み量が本タスクの予算を超える。`confirmed_by_router` という別ラベルを導入して出典を区別する方式を採った | Screen 層の引用（AP9, AP18, AP22, IV5 の Screen 側）は router 記述に依存。独立検証は verifier 側の obligation |
| 3 | IV*（不正状態）を fixture で実際に構築して observed_result: constructed の実行 Evidence を取る | 前提: destruction probe は使い捨て fixture で実行してよい | 思考実験としてコード経路を辿るのみに留めた | 本 review は read-only（mutation_authorized: false）でテストファイル作成も禁止されている。`observed_result.status` は code path の追跡に基づく `constructed` / `partially_observed` とし、実行 Evidence ではないことを UV1 で明示した | OB-T* を RED テストとして実装する段階で実行 Evidence を取る |
| 4 | G11（HTTP code 解釈の分散）を「ApiException に code ごとの sealed subclass を足す」という具体案として提示する | 未検証: 解決策の形（sealed 階層 / 変換関数 / endpoint 単位の契約）まで本 Skill が決めてよい | 案を検討した | 解決策の形は Boundary Package / Contract Package の authority であり、本 Skill は「意味が欠落している」ことと obligation までを所有する。OB-C11/OB-C12 は要求される意味と不変条件のみを述べる形に留めた | 解決策の形は `mino-interface-implementation-separation` / `mino-design-by-contract` の obligation |
| 5 | `@StringRes` ラベルの検証をテスト obligation に含める | 前提: 文言も契約の一部 | OB-T13（文言に transport 文字列を含まない）の検査方法を検討した | `docs/trial-log/featured-categories.md` で「`@StringRes` の unit test は Context 依存のため棄却」と既に記録済み。OB-T13 は「固定文言定数と一致する」形（Context 非依存）で書ける範囲に限定した | 文言の実表示検証は UI テストの obligation（androidTest 不在が前提条件） |

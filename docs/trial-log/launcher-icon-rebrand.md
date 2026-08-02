# launcher-icon-rebrand (android issue #21 / B5)

## 目的
launcher icon をサービスアイコン（iOS AppIcon-1024.png 準拠）へ差し替える。

## 合成仕様（確定・実施）
- 1024x1024 キャンバスを `#0F2549` で塗り、マスターを62%(635px)へ縮小して中央配置。
- 縮小画像に外周8%の線形フェードアルファマスクを適用（各辺 d<8% で alpha=d/8%、四隅は両軸min）。
- LANCZOS で mdpi(108)/hdpi(162)/xhdpi(216)/xxhdpi(324)/xxxhdpi(432) へ保存。
- スクリプト: `/private/tmp/claude-501/-Users-rio-git-news-listen/074bb5ee-bfb0-43c0-ae9b-c6000011b401/scratchpad/gen_launcher_icon.py`（PIL 9.5.0）。

## リソース配線
- colors.xml の `ic_launcher_background` を `#0F2549` に変更。
- ic_launcher.xml / ic_launcher_round.xml の foreground を `@mipmap/ic_launcher_foreground` に変更し `monochrome` を追加。
- 旧仮アイコン drawable/ic_launcher_foreground.xml（青丸+再生三角）を削除。
- drawable/ic_launcher_monochrome.xml を新規作成。web/components/ui/BrandLogo.tsx:29-34 の24x24グリフ（角丸矩形+波形5本）を
  VectorDrawable（108ビューポート, group scale=2.75/translate=21でセーフゾーン66/108へ収める）として移植。
  角丸矩形はVectorDrawableにrect要素が無いためpathDataのarc(a)コマンドで表現。

## ビルド検証
- 初回 `./gradlew test assembleDebug` は `com.android.application` プラグイン未解決で失敗 →
  daemon再起動+ `--refresh-dependencies` で解消（ネットワーク自体はcurlで到達確認済み、原因はdaemonの古い解決キャッシュと推測。未検証: 厳密な原因切り分け）。
- 次に `google-services.json is missing` で失敗 → gitignore対象のためworktreeに存在せず。
  本体チェックアウト `android/app/google-services.json` をコピーして解消（コミット対象外）。
- 同様に `local.properties`（sdk.dir のみ）を作成。
- 最終結果: `BUILD SUCCESSFUL in 1m 35s`（test + assembleDebug）。

## 残課題
- エミュレータでの実機確認は親セッションが実施予定（本タスクスコープ外）。

## 2026-08-02 ai-antipattern-reviewer 判定: PASS（WARNING 1 件・適用済み）

- XML 配線・PNG 5 密度・VectorDrawable 座標計算（24×2.75=66・translate 21）・スコープはすべて健全。local.properties / google-services.json は gitignore 済みでコミット非対象を確認。
- 指摘: monochrome の strokeWidth が移植元 BrandLogo.tsx の 2.5 に対し 2 へ無断変更 → 2.5 へ統一して解消（メインセッション適用）。

## 2026-08-03 エミュレータ実表示検証（メインセッション）

AVD `newslisten_e2e`（ヘッドレス・swiftshader）に assembleDebug APK を導入して実測:
- **アプリドロワー**: 円形マスクで新聞＋波形が明瞭に表示。他アプリのアイコンと並べて視認性・意匠とも問題なし。
- **スプラッシュ**（`Theme.NewsListen.Starting` の `windowSplashScreenAnimatedIcon=@mipmap/ic_launcher`）: 懸念だった「foreground 層のみ描画で紺のフルブリード矩形が露出する」事象は発生せず、adaptive icon が円形マスクで正常表示（白背景中央に紺円＋アートワーク）。themes.xml のコメントが警告する foreground 専用素材への差し替えは不要と判断。
- 証跡スクリーンショット 2 枚はセッション成果物としてオーナーへ提示済み。

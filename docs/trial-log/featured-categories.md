# featured-categories 実装（TDD）

## 完了日時
2026-08-29

## 実装内容
- FeaturedSite.kt に `category: String? = null` フィールド追加
- FeaturedCategory.kt 新規作成（enum相当、normalize、groupByCategoryInOrder、getDisplayOrderCategories）
- strings.xml にカテゴリラベル 5 件追加
- OnboardingScreen.kt をカテゴリ別セクション表示に修正
- SettingsScreen.kt をカテゴリ別セクション表示に修正

## テスト結果
- FeaturedSiteTest.kt: category 有り/欠落のデコード テスト追加
- FeaturedCategoryTest.kt 新規作成: normalize、表示順、グルーピング、空カテゴリ除外テスト
- 全テスト実行: BUILD SUCCESSFUL

## 試行と棄却
- getCategoryLabel のユニットテストを含めたが、@StringRes は Context が必要なため削除（UI テストで担保される設計）

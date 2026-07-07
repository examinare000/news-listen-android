package com.rioikeda.newslisten.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Material3 Shapes の上書き（android-design.md §5.4）
// コントロール=10 / カード=14
private val DSShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
)

/**
 * NewsListen Compose テーマ
 *
 * iOS Editorial デザインシステム（ios-design-system.md）をベースに、
 * Material3 スロットへ写像したテーマ。
 *
 * @param darkTheme ダークモードを有効にするか（デフォルト: システム設定）
 * @param content テーマが適用される Composable コンテンツ
 */
@Composable
fun NewsListenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Material You (dynamicColor) は無効化
    // 理由: 壁紙由来のパレット生成はブランド色（紙・墨・朱）を破壊し、
    // iOS / Web との端末間視覚一貫性が失われるため
    // （android-design.md §5.1 参照）

    val colorScheme: ColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DSTypography,
        shapes = DSShapes,
        content = content
    )
}

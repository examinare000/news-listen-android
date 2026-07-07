package com.rioikeda.newslisten.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// iOS Editorial デザインシステムの書体写像 (android-design.md §5.3)
// 見出し = Serif（Noto Serif 相当）、本文・メタ = Sans（SF 相当）

// TODO: フォント外部取得（Noto Serif）の追加検討は follow-up として別タスク
// 現状は FontFamily.Serif（システムセリフ）を使用

internal val DSTypography = Typography(
    // display → headlineLarge (largeTitle / serif / bold)
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    // title → headlineSmall (title2 / serif / semibold)
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    // headline → titleLarge (title3 / serif / semibold)
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // body → bodyLarge (body / SF)
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,  // SF 相当（デフォルトサンセリフ）
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // meta → bodyMedium (subheadline / SF)
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    // caption → bodySmall (caption / SF)
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // eyebrow → labelMedium (caption / semibold / 大文字 + 字間)
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp  // 字間拡大（eyebrow 相当）
    ),
)

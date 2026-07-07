package com.rioikeda.newslisten.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// iOS Editorial デザインシステムの色トークン
// ios-design-system.md §2 より hex 値を転記

// ライトモード
private val DSPaperLight = Color(0xFFFBF9F4)
private val DSSurfaceLight = Color(0xFFFFFFFF)
private val DSInkLight = Color(0xFF1C1A17)
private val DSInkSecondaryLight = Color(0xFF6E665B)
private val DSInkTertiaryLight = Color(0xFF9A9286)
private val DSHairlineLight = Color(0xFFE7E2D8)
private val DSAccentLight = Color(0xFFA8402E) // 朱
private val DSOnAccentLight = Color(0xFFFBF9F4)
// accentSoft ライト: accent に alpha 0.10 を適用（DSColor.swift 準拠）
private val DSAccentSoftLight = DSAccentLight.copy(alpha = 0.10f)
private val DSDangerLight = Color(0xFFC0392B)
private val DSStarLight = Color(0xFFC8902E) // 金
private val DSSuccessLight = Color(0xFF4F7A3A) // 森緑

// ダークモード
private val DSPaperDark = Color(0xFF14130F)
private val DSSurfaceDark = Color(0xFF1E1C17)
private val DSInkDark = Color(0xFFF4F0E7)
private val DSInkSecondaryDark = Color(0xFFABA496)
private val DSInkTertiaryDark = Color(0xFF756E61)
private val DSHairlineDark = Color(0xFF322F28)
private val DSAccentDark = Color(0xFFE08A6E) // 朱
private val DSOnAccentDark = Color(0xFF14130F)
// accentSoft ダーク: accent に alpha 0.16 を適用（DSColor.swift 準拠）
private val DSAccentSoftDark = DSAccentDark.copy(alpha = 0.16f)
private val DSDangerDark = Color(0xFFE57373)
private val DSStarDark = Color(0xFFE0B65C) // 金
private val DSSuccessDark = Color(0xFF8BB87A) // 森緑

// Material3 ColorScheme の構築（android-design.md §5.2 の写像に従う）

internal val LightColorScheme = lightColorScheme(
    background = DSPaperLight,           // paper
    onBackground = DSInkLight,            // ink
    surface = DSSurfaceLight,              // surface
    onSurface = DSInkLight,                // ink
    onSurfaceVariant = DSInkSecondaryLight, // inkSecondary
    outline = DSInkTertiaryLight,          // inkTertiary
    outlineVariant = DSHairlineLight,      // hairline
    primary = DSAccentLight,               // accent（朱）
    onPrimary = DSOnAccentLight,           // onAccent
    primaryContainer = DSAccentSoftLight,  // accentSoft（淡朱）
    onPrimaryContainer = DSAccentLight,    // accent 濃色（参照: onPrimaryContainer はアクセント面での前景）
    error = DSDangerLight,                 // danger
    onError = Color.White,                 // error 面上の前景（白保証）
    tertiary = DSStarLight,                // star（金）
    onTertiary = Color.White,              // star 面上の前景（白保証）
    // secondary / secondaryContainer は使わない（支配色 + 1 アクセント原則）
    // 既定参照で破綻しないよう ink/surface 系で埋める
    secondary = DSInkSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = DSSurfaceLight,
    onSecondaryContainer = DSInkSecondaryLight,
)

internal val DarkColorScheme = darkColorScheme(
    background = DSPaperDark,            // paper
    onBackground = DSInkDark,             // ink
    surface = DSSurfaceDark,               // surface
    onSurface = DSInkDark,                 // ink
    onSurfaceVariant = DSInkSecondaryDark, // inkSecondary
    outline = DSInkTertiaryDark,           // inkTertiary
    outlineVariant = DSHairlineDark,       // hairline
    primary = DSAccentDark,                // accent（朱）
    onPrimary = DSOnAccentDark,            // onAccent（逆相で可読性確保）
    primaryContainer = DSAccentSoftDark,   // accentSoft（淡朱）
    onPrimaryContainer = DSAccentDark,     // accent 濃色
    error = DSDangerDark,                  // danger
    onError = Color.Black,                 // error 面上の前景（黒保証）
    tertiary = DSStarDark,                 // star（金）
    onTertiary = Color.Black,              // star 面上の前景（黒保証）
    // secondary / secondaryContainer
    secondary = DSInkSecondaryDark,
    onSecondary = Color.Black,
    secondaryContainer = DSSurfaceDark,
    onSecondaryContainer = DSInkSecondaryDark,
)

// 拡張色：success（森緑、ColorScheme 外）
val DSSuccessLight_Extended = DSSuccessLight
val DSSuccessDark_Extended = DSSuccessDark

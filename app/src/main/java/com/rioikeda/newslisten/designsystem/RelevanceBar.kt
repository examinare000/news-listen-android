package com.rioikeda.newslisten.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 関連スコア（0〜1）を細い横バーで可視化する Editorial コンポーネント。
 *
 * iOS RelevanceBar.swift のミラー。雑誌的に主張しすぎない 3dp の極細バー。
 * トラックは hairline（outlineVariant）、満たし部分は accent（primary）。
 */
@Composable
fun RelevanceBar(
    score: Double,
    modifier: Modifier = Modifier
) {
    val clamped = score.coerceIn(0.0, 1.0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
            .semantics {
                contentDescription = "関連スコア %.0f%%".format(clamped * 100)
            }
    ) {
        // 満たしバー: accent 色で clamp × maxWidth だけ描画
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped.toFloat())
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

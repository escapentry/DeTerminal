package com.example.display

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class WindowWidthTier {
  COMPACT,
  MEDIUM,
  EXPANDED,
}

@Immutable
data class TerminalDisplayProfile(
  val widthTier: WindowWidthTier,
  val isCompactHeight: Boolean,
  val isLandscape: Boolean,
  val availableWidthDp: Float,
  val availableHeightDp: Float,
  val density: Float,
  val systemFontScale: Float,
  val horizontalPaddingDp: Dp,
  val terminalFontSp: TextUnit,
  val terminalLineHeightSp: TextUnit,
  val promptBadgeFontSp: TextUnit,
  val accessoryBarHeightDp: Dp,
  val accessoryFontSp: TextUnit,
)

object DisplayAutoScaler {

  fun computeProfile(
    availableWidthDp: Float,
    availableHeightDp: Float,
    density: Float,
    systemFontScale: Float,
  ): TerminalDisplayProfile {
    val safeWidth = availableWidthDp.coerceAtLeast(280f)
    val safeHeight = availableHeightDp.coerceAtLeast(240f)
    val isLandscape = safeWidth > safeHeight * 1.1f
    val isCompactHeight = safeHeight < 420f

    val widthTier = when {
      safeWidth < 600f -> WindowWidthTier.COMPACT
      safeWidth < 840f -> WindowWidthTier.MEDIUM
      else -> WindowWidthTier.EXPANDED
    }

    val horizontalPaddingDp = when {
      safeWidth < 360f -> 10.dp
      widthTier == WindowWidthTier.COMPACT -> 14.dp
      widthTier == WindowWidthTier.MEDIUM -> 20.dp
      else -> 24.dp
    }

    // Clamp extreme system font scales so terminal columns remain aligned and readable
    val fontGuard = if (systemFontScale > 1.25f) 1.25f / systemFontScale else 1f

    val baseTerminalSp = when {
      isCompactHeight -> 11.8f
      safeWidth < 360f -> 12.2f
      widthTier == WindowWidthTier.COMPACT -> 13.2f
      widthTier == WindowWidthTier.MEDIUM -> 14.4f
      else -> 15.2f
    }

    val terminalSp = (baseTerminalSp * fontGuard).coerceIn(11f, 16.5f)
    val lineHeightSp = (terminalSp * 1.38f).coerceIn(15.5f, 23f)

    return TerminalDisplayProfile(
      widthTier = widthTier,
      isCompactHeight = isCompactHeight,
      isLandscape = isLandscape,
      availableWidthDp = safeWidth,
      availableHeightDp = safeHeight,
      density = density,
      systemFontScale = systemFontScale,
      horizontalPaddingDp = horizontalPaddingDp,
      terminalFontSp = terminalSp.sp,
      terminalLineHeightSp = lineHeightSp.sp,
      promptBadgeFontSp = (terminalSp * 0.90f).coerceIn(10f, 14.5f).sp,
      accessoryBarHeightDp = 48.dp,
      accessoryFontSp = (if (isCompactHeight) 12.5f else 13.5f * fontGuard).coerceIn(11.5f, 15f).sp,
    )
  }
}

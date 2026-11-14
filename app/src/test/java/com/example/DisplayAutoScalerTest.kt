package com.example

import com.example.display.DisplayAutoScaler
import com.example.display.WindowWidthTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayAutoScalerTest {

  @Test
  fun `compact phone computes balanced terminal font size`() {
    val profile = DisplayAutoScaler.computeProfile(
      availableWidthDp = 390f,
      availableHeightDp = 844f,
      density = 3f,
      systemFontScale = 1f,
    )
    assertEquals(WindowWidthTier.COMPACT, profile.widthTier)
    assertTrue(profile.terminalFontSp.value in 12.5f..14.0f)
    assertTrue(profile.terminalLineHeightSp.value > profile.terminalFontSp.value)
  }

  @Test
  fun `compact height with open keyboard scales down font cleanly`() {
    val profile = DisplayAutoScaler.computeProfile(
      availableWidthDp = 390f,
      availableHeightDp = 340f,
      density = 3f,
      systemFontScale = 1f,
    )
    assertTrue(profile.isCompactHeight)
    assertTrue(profile.terminalFontSp.value < 13f)
  }

  @Test
  fun `tablet scales up monospace font cleanly`() {
    val tablet = DisplayAutoScaler.computeProfile(
      availableWidthDp = 1024f,
      availableHeightDp = 768f,
      density = 2f,
      systemFontScale = 1f,
    )
    val phone = DisplayAutoScaler.computeProfile(
      availableWidthDp = 360f,
      availableHeightDp = 780f,
      density = 3f,
      systemFontScale = 1f,
    )
    assertEquals(WindowWidthTier.EXPANDED, tablet.widthTier)
    assertTrue(tablet.terminalFontSp.value > phone.terminalFontSp.value)
  }

  @Test
  fun `extreme system font scale is clamped to prevent broken wrapping`() {
    val normal = DisplayAutoScaler.computeProfile(390f, 844f, 3f, 1.0f)
    val extreme = DisplayAutoScaler.computeProfile(390f, 844f, 3f, 2.0f)
    assertTrue(extreme.terminalFontSp.value < normal.terminalFontSp.value)
    assertTrue(extreme.terminalFontSp.value >= 11f)
  }
}

package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSettingsTest {

  @Test
  fun defaultsMatchTheSpecification() {
    val s = VisualSettings()
    assertEquals(1.00f, s.brightness, 0f)
    assertEquals(1.10f, s.contrast, 0f)
    assertEquals(0.95f, s.saturation, 0f)
    assertEquals(1.00f, s.sunIntensity, 0f)
    assertEquals(0.00f, s.warmth, 0f)
    assertEquals(1.00f, s.flashlightIntensity, 0f)
    assertTrue(s.isDefault())
  }

  @Test
  fun rangesMatchTheSpecification() {
    fun range(p: VisualParam, lo: Float, hi: Float) {
      assertEquals(p.name, lo, p.min, 0f)
      assertEquals(p.name, hi, p.max, 0f)
      assertTrue(p.name, p.default in lo..hi)
    }
    range(VisualParam.BRIGHTNESS, 0.50f, 1.60f)
    range(VisualParam.CONTRAST, 0.70f, 1.60f)
    range(VisualParam.SATURATION, 0.30f, 1.50f)
    range(VisualParam.SUN_INTENSITY, 0.50f, 1.80f)
    range(VisualParam.TERRAIN_CONTRAST, 0.60f, 1.80f)
    range(VisualParam.NORMAL_STRENGTH, 0.50f, 2.20f)
    range(VisualParam.WARMTH, -0.30f, 0.30f)
    range(VisualParam.FLASHLIGHT_INTENSITY, 0.50f, 2.00f)
  }

  @Test
  fun valuesAreClampedToTheirRange() {
    val s = VisualSettings()
    s[VisualParam.BRIGHTNESS] = 9f
    assertEquals(1.60f, s.brightness, 0f)
    s[VisualParam.BRIGHTNESS] = -3f
    assertEquals(0.50f, s.brightness, 0f)
    s[VisualParam.WARMTH] = Float.NaN
    assertEquals(0f, s.warmth, 0f)
  }

  @Test
  fun saveAndLoadRoundTrip() {
    val a = VisualSettings()
    for ((i, p) in VisualParam.values().withIndex()) a[p] = p.min + (p.max - p.min) * (i + 1) / 10f
    val store = HashMap<String, Float>()
    a.save { k, v -> store[k] = v }
    assertEquals(VisualParam.values().size, store.size)

    val b = VisualSettings()
    b.load { k, d -> store[k] ?: d }
    for (p in VisualParam.values()) assertEquals(p.name, a[p], b[p], 0f)
  }

  @Test
  fun loadFallsBackToDefaultsAndRejectsGarbage() {
    val s = VisualSettings()
    s.load { _, d -> d }
    assertTrue(s.isDefault())
    s.load { _, _ -> 1e9f }
    for (p in VisualParam.values()) assertEquals(p.max, s[p], 0f)
    s.load { _, _ -> Float.NaN }
    assertTrue(s.isDefault())
  }

  @Test
  fun resetRestoresDefaultsAndBumpsRevision() {
    val s = VisualSettings()
    s[VisualParam.CONTRAST] = 1.5f
    s[VisualParam.WARMTH] = 0.2f
    assertFalse(s.isDefault())
    val before = s.revision
    s.resetToDefaults()
    assertTrue(s.isDefault())
    assertTrue(s.revision > before)
  }

  @Test
  fun warmthTintsTowardsGoldAndBlue() {
    val s = VisualSettings()
    s[VisualParam.WARMTH] = 0.3f
    assertTrue(s.tintR > 1f && s.tintB < 1f)
    s[VisualParam.WARMTH] = -0.3f
    assertTrue(s.tintR < 1f && s.tintB > 1f)
    s[VisualParam.WARMTH] = 0f
    assertEquals(1f, s.tintR, 0f)
    assertEquals(1f, s.tintG, 0f)
    assertEquals(1f, s.tintB, 0f)
  }

  @Test
  fun cpuGradeIsNeutralAtIdentityAndMonotonic() {
    val s = VisualSettings()
    s[VisualParam.CONTRAST] = 1f
    s[VisualParam.SATURATION] = 1f
    val out = FloatArray(3)
    s.grade(0.4f, 0.5f, 0.6f, out)
    assertEquals(0.4f, out[0], 1e-5f)
    assertEquals(0.5f, out[1], 1e-5f)
    assertEquals(0.6f, out[2], 1e-5f)

    s[VisualParam.BRIGHTNESS] = 1.4f
    s.grade(0.4f, 0.5f, 0.6f, out)
    assertTrue(out[0] > 0.4f && out[1] > 0.5f && out[2] > 0.6f)

    // zero saturation -> grey
    s.resetToDefaults()
    s[VisualParam.SATURATION] = 0.3f
    s.grade(0.9f, 0.2f, 0.1f, out)
    val low = out[0] - out[2]
    s[VisualParam.SATURATION] = 1.5f
    s.grade(0.9f, 0.2f, 0.1f, out)
    assertTrue(out[0] - out[2] > low)
  }

  @Test
  fun gradeNeverGoesNegative() {
    val s = VisualSettings()
    s[VisualParam.CONTRAST] = 1.6f
    s[VisualParam.BRIGHTNESS] = 0.5f
    s[VisualParam.WARMTH] = 0.3f
    val out = FloatArray(3)
    s.grade(0f, 0f, 0f, out)
    for (c in out) assertTrue(c >= 0f)
  }

  @Test
  fun everyLitShaderUsesTheSharedGradeAndFlashlightShape() {
    val glsl = VisualGrade.GLSL
    for (u in listOf("u_brightness", "u_contrast", "u_saturation", "u_warmth", "visualGrade", "visualGradeLight")) {
      assertTrue(u, glsl.contains(u))
    }
    assertTrue(VisualGrade.SPOT_GLSL.contains("u_flashlightSoftness"))
    assertTrue(VisualGrade.SPOT_GLSL.contains("smoothstep"))
  }

  @Test
  fun panelFormatsValues() {
    assertEquals("1.10", VisualPanel.format(VisualParam.CONTRAST, 1.1f))
    assertEquals("+0.12", VisualPanel.format(VisualParam.WARMTH, 0.12f))
    assertEquals("-0.20", VisualPanel.format(VisualParam.WARMTH, -0.2f))
  }
}

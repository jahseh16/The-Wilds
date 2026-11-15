package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyExposureTest {

  private val top = 32

  /** Column of grid levels 0..32: solid (density 2) up to and including level [surface], air above. */
  private fun column(surface: Int, carved: IntRange = IntRange.EMPTY): FloatArray {
    val out = FloatArray(top + 1) { -1f }
    SkyExposure.fillColumn(out, 0, top, 1f) { gy ->
      when {
        gy in carved -> -2f
        gy <= surface -> 2f
        else -> -2f
      }
    }
    return out
  }

  @Test
  fun openGroundSeesTheWholeSky() {
    val exposure = column(surface = 12)
    for (gy in 12..top) assertEquals("level $gy", 1f, exposure[gy], 1e-6f)
  }

  @Test
  fun exposureFallsSmoothlyWithCover() {
    val exposure = column(surface = 20)
    // Each metre of rock above multiplies the light by exp(-ABSORPTION)
    assertEquals(SkyExposure.exposureForCover(1f), exposure[19], 1e-5f)
    assertEquals(SkyExposure.exposureForCover(3f), exposure[17], 1e-5f)
    for (gy in 1..20) assertTrue("monotonic at $gy", exposure[gy - 1] <= exposure[gy] + 1e-6f)
  }

  @Test
  fun aTunnelUnderThreeMetresOfRockIsDark() {
    // Air pocket at levels 10..12 under rock up to level 15: 3 m of roof above every point of it.
    // exp(-0.8 * 3) = 0.09, and TerrainShader's smoothstep(0.04, 0.85, x) turns that into ~0.01
    val exposure = column(surface = 15, carved = 10..12)
    assertTrue("tunnel floor is dark (${exposure[10]})", exposure[10] < 0.1f)
    assertTrue("tunnel ceiling is dark (${exposure[12]})", exposure[12] < 0.1f)
    assertEquals("open sky above the ground", 1f, exposure[top], 1e-6f)
  }

  @Test
  fun aShaftOpenToTheSkyStaysBright() {
    // Pit dug from the surface down to level 8: the floor still sees the sky
    val exposure = column(surface = 20, carved = 8..32)
    assertEquals(1f, exposure[8], 1e-6f)
  }

  @Test
  fun fractionalDensityGivesFractionalCover() {
    val out = FloatArray(top + 1)
    SkyExposure.fillColumn(out, 0, top, 1f) { gy -> if (gy == 10) 0.4f else -1f }
    assertEquals(SkyExposure.exposureForCover(0.4f), out[9], 1e-5f)
    assertEquals(1f, out[10], 1e-6f)
  }

  @Test
  fun deepColumnsAreCutOffToZeroWithoutSamplingThem() {
    var samples = 0
    val out = FloatArray(top + 1) { 0.5f }
    SkyExposure.fillColumn(out, 0, top, 1f) { samples++; 2f } // solid all the way up: pure cover
    assertTrue("stops sampling after the cut-off ($samples samples)", samples < 10)
    for (gy in 0..top - 8) assertEquals("level $gy", 0f, out[gy], 0f)
  }

  @Test
  fun writesAtTheGivenOffsetOnly() {
    val out = FloatArray(100) { -7f }
    SkyExposure.fillColumn(out, 40, top, 1f) { -1f }
    assertEquals(-7f, out[39], 0f)
    assertEquals(1f, out[40], 0f)
    assertEquals(1f, out[40 + top], 0f)
    assertEquals(-7f, out[40 + top + 1], 0f)
  }
}

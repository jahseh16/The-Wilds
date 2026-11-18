package com.example

import com.badlogic.gdx.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SkyTest {

  private fun ch(px: Int, c: Int) = (px ushr (24 - 8 * c)) and 0xFF

  @Test
  fun cloudNoiseTilesSeamlesslyInEveryChannel() {
    val n = 64
    val tex = SkyTextures.cloudNoise(n)
    for (c in 0 until 4) {
      var edge = 0.0; var inner = 0.0
      for (i in 0 until n) {
        // wrap-around neighbours differ no more than ordinary neighbours
        edge += abs(ch(tex[i * n + n - 1], c) - ch(tex[i * n], c)) + abs(ch(tex[(n - 1) * n + i], c) - ch(tex[i], c))
        inner += abs(ch(tex[i * n + 10], c) - ch(tex[i * n + 11], c)) + abs(ch(tex[10 * n + i], c) - ch(tex[11 * n + i], c))
      }
      assertTrue("channel $c seam $edge vs $inner", edge < inner * 1.6 + 2 * n)
      val values = tex.map { ch(it, c) }
      assertTrue("channel $c uses the full range", values.minOrNull()!! < 20 && values.maxOrNull()!! > 235)
    }
    // octaves: higher channels vary faster
    fun roughness(c: Int) = (0 until n * n - 1).sumOf { abs(ch(tex[it], c) - ch(tex[it + 1], c)).toLong() }
    assertTrue(roughness(0) < roughness(1) && roughness(1) < roughness(2) && roughness(2) < roughness(3))
  }

  @Test
  fun moonMapHasNormalsMariaAndCraters() {
    val n = 128
    val m = SkyTextures.moonMap(n)
    var tilted = 0
    var dark = 0; var bright = 0
    for (px in m) {
      val nx = ch(px, 0) / 127.5 - 1; val ny = ch(px, 1) / 127.5 - 1; val nz = ch(px, 2) / 127.5 - 1
      assertEquals(1.0, sqrt(nx * nx + ny * ny + nz * nz), 0.05)
      assertTrue(nz > 0.2)
      if (nz < 0.97) tilted++
      val a = ch(px, 3)
      if (a < 150) dark++
      if (a > 205) bright++
    }
    assertTrue("crater slopes: $tilted", tilted > n * n / 20)
    assertTrue("maria: $dark", dark > n * n / 10)
    assertTrue("highlands: $bright", bright > n * n / 10)
  }

  private fun palette(timeOfDay: Float): Pair<SkyPalette, DayNightCycle> {
    val cycle = DayNightCycle(DayPhase.DAY)
    cycle.update((timeOfDay - cycle.timeOfDay + 1f) % 1f * cycle.cycleSeconds)
    return SkyPalette().also { it.update(cycle) } to cycle
  }

  @Test
  fun horizonIsTheFogColourSoTheWorldMeetsTheSkyWithoutASeam() {
    for (t in listOf(0.0f, 0.1f, 0.25f, 0.45f, 0.5f, 0.6f, 0.75f, 0.9f)) {
      val (p, cycle) = palette(t)
      assertEquals(cycle.fogColor.r, p.horizon.r, 1e-6f)
      // looking away from the light, the haze is exactly the fog colour
      val away = Vector3(p.lightDir).scl(-1f).also { it.y = 0f }.nor()
      val out = FloatArray(3); p.skyHorizon(away, out)
      assertEquals(cycle.fogColor.g, out[1], 1e-5f)
    }
  }

  @Test
  fun sunsetsBurnAmberUnderAVioletZenith() {
    val (dusk, dc) = palette(0.5f)
    val (noon, _) = palette(0.25f)
    val (night, _) = palette(0.75f)
    assertTrue("sun low at dusk ${dc.sunElevation}", dc.sunElevation in -0.05f..0.3f)
    // warm glow towards the sun
    assertTrue("glow is warm ${dusk.glow}", dusk.glow.r > dusk.glow.b * 3f && dusk.glow.r > 0.25f)
    assertTrue("much stronger at dusk than at noon", dusk.glow.r > noon.glow.r * 2.5f)
    val toSun = Vector3(dusk.sunDir.x, 0.02f, dusk.sunDir.z).nor()
    val out = FloatArray(3); dusk.skyHorizon(toSun, out)
    assertTrue("horizon under the sun is orange (${out.toList()})", out[0] > out[2] * 1.6f)
    // the dusk zenith is cool (violet-blue) and darker than the noon one
    assertTrue("violet zenith ${dusk.zenith}", dusk.zenith.b > dusk.zenith.r && dusk.zenith.b < noon.zenith.b)
    assertTrue("day zenith deeper blue than the haze", noon.zenith.b > noon.zenith.r * 2f)
    // clouds catch gold at dusk, grey-blue moonlight at night
    assertTrue(dusk.cloudLit.r > dusk.cloudLit.b * 1.6f)
    assertTrue(night.cloudLit.r < 0.12f && night.cloudLit.b > night.cloudLit.r)
    assertTrue(night.night > 0.95f && noon.night < 0.01f)
    // at night the moon is the light of the clouds and the haze
    assertEquals(night.moonDir.y, night.lightDir.y, 1e-6f)
    assertEquals(0f, night.sunDisc.r, 1e-3f)
  }
}

package com.example

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandTerrainTest {

  private val radius = IslandTerrain.REFERENCE_RADIUS
  private val centre = radius + 12f
  private val island = IslandTerrain(IslandTerrain.DEFAULT_SEED, centre, centre, radius, 0f)

  private fun along(angle: Float, distance: Float) =
    island.heightAt(centre + cos(angle) * distance, centre + sin(angle) * distance)

  @Test
  fun heightsStayInsideTheWorldColumn() {
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    var z = 0f
    while (z <= 2f * centre) {
      var x = 0f
      while (x <= 2f * centre) {
        val h = island.heightAt(x, z)
        min = minOf(min, h)
        max = maxOf(max, h)
        x += 8f
      }
      z += 8f
    }
    // The chunk column spans -12 .. 52 m; the mountains must leave air above them and the sea floor rock below
    assertTrue("highest point $max", max < IslandTerrain.MAX_HEIGHT_ABOVE_SEA + 0.01f)
    assertTrue("there are real mountains: $max", max > 25f)
    assertTrue("lowest point $min", min > -IslandTerrain.SEABED_DEPTH - 1f)
    assertTrue("sea floor reached: $min", min < -IslandTerrain.SEABED_DEPTH + 1f)
  }

  @Test
  fun theIslandIsAKilometreWide() {
    // Land (above sea level) well inland on every side, sea floor at the rim
    for (i in 0 until 8) {
      val angle = (2.0 * PI * i / 8).toFloat()
      assertTrue("land at 0.4 R, angle $i", along(angle, radius * 0.4f) > 2f)
      assertTrue("sea floor at the rim, angle $i: ${along(angle, radius)}", along(angle, radius) < -5f)
    }
  }

  private fun shoreDistance(angle: Float): Float {
    var d = radius
    while (d > 0f && along(angle, d) < 0f) d -= 1f
    return d
  }

  @Test
  fun coastsAlternateBetweenWalkableCovesAndAbruptCliffs() {
    var coves = 0
    var cliffs = 0
    for (i in 0 until 24) {
      val angle = (2.0 * PI * i / 24).toFloat()
      val shore = shoreDistance(angle)
      var maxSlope = 0f
      var width = 0f
      var p = shore
      while (p > shore - 150f && along(angle, p) < 3f) {
        maxSlope = maxOf(maxSlope, abs(along(angle, p - 1f) - along(angle, p)))
        width += 1f
        p -= 1f
      }
      if (width > 25f && maxSlope < 0.15f) coves++
      if (width < 20f && maxSlope > 0.25f) cliffs++
    }
    assertTrue("walkable cove beaches: $coves of 24", coves >= 5)
    assertTrue("abrupt cliff coasts: $cliffs of 24", cliffs >= 4)
  }

  @Test
  fun theReliefIsRuggedNotASmoothRamp() {
    // Land well inland: a good share of the ground is steep (cliffs, gullies, terraces) and some of it is gentle
    var steep = 0; var gentle = 0; var n = 0
    var z = 100f
    while (z < 900f) {
      var x = 100f
      while (x < 900f) {
        val h = island.heightAt(x, z)
        if (h > 3f) {
          val s = maxOf(abs(island.heightAt(x + 2f, z) - h), abs(island.heightAt(x, z + 2f) - h)) / 2f
          n++
          if (s > 0.5f) steep++
          if (s < 0.15f) gentle++
        }
        x += 6f
      }
      z += 6f
    }
    assertTrue("steep ground share ${steep.toFloat() / n}", steep.toFloat() / n > 0.05f)
    assertTrue("gentle ground share ${gentle.toFloat() / n}", gentle.toFloat() / n > 0.25f)
  }

  @Test
  fun biomesCoverForestGravelAndOpenGround() {
    val out = FloatArray(4)
    var forest = 0; var gravel = 0; var open = 0; var n = 0
    var z = 100f
    while (z < 900f) {
      var x = 100f
      while (x < 900f) {
        island.biomeAt(x, z, out)
        assertTrue(out[0] in 0f..1f && out[1] in 0f..1f)
        if (out[2] > 2f) {
          n++
          if (out[0] > 0.5f) forest++
          if (out[1] > 0.5f) gravel++
          if (out[0] < 0.1f && out[1] < 0.3f && out[3] < 0.25f) open++
        }
        x += 8f
      }
      z += 8f
    }
    assertTrue("forest ${forest.toFloat() / n}", forest.toFloat() / n in 0.10f..0.55f)
    assertTrue("gravel ${gravel.toFloat() / n}", gravel.toFloat() / n in 0.05f..0.45f)
    assertTrue("open ground ${open.toFloat() / n}", open.toFloat() / n > 0.10f)
  }

  @Test
  fun noForestInTheWaterOrOnTheBeach() {
    val out = FloatArray(4)
    for (i in 0 until 24) {
      val angle = (2.0 * PI * i / 24).toFloat()
      val shore = shoreDistance(angle)
      island.biomeAt(centre + cos(angle) * (shore + 10f), centre + sin(angle) * (shore + 10f), out)
      assertEquals("forest at sea", 0f, out[0], 0f)
      island.biomeAt(centre + cos(angle) * (shore - 2f), centre + sin(angle) * (shore - 2f), out)
      assertEquals("forest on the waterline", 0f, out[0], 0f)
    }
  }

  @Test
  fun aSmallIslandIsAScaledCopy() {
    val small = IslandTerrain(IslandTerrain.DEFAULT_SEED, 80f, 80f, 72f, 0f)
    assertTrue(small.heightAt(80f, 80f) > 2f)
    assertTrue(small.heightAt(80f + 72f, 80f) < -5f)
    assertEquals(small.heightAt(33f, 41f), small.heightAt(33f, 41f), 0f)
  }
}

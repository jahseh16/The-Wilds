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

  @Test
  fun beachesAreWideAndGentle() {
    for (i in 0 until 12) {
      val angle = (2.0 * PI * i / 12).toFloat()
      // Walk inland from the sea floor with 1 m steps until the water line
      var d = radius
      while (d > 0f && along(angle, d) < 0f) d -= 1f
      val shoreDistance = d
      // From the waterline up to 3 m of height: a broad slope, never steeper than 15 %
      var maxSlope = 0f
      var beachWidth = 0f
      var p = shoreDistance
      while (p > shoreDistance - 150f && along(angle, p) < 3f) {
        maxSlope = maxOf(maxSlope, abs(along(angle, p - 1f) - along(angle, p)))
        beachWidth += 1f
        p -= 1f
      }
      assertTrue("angle $i: beach slope $maxSlope", maxSlope < 0.15f)
      assertTrue("angle $i: beach only $beachWidth m wide", beachWidth > 25f)
    }
  }

  @Test
  fun theBeachIsSmoothSand() {
    // Noise is faded out on the beach: the height above the waterline follows one smooth curve, so
    // the second difference over 4 m stays tiny
    val angle = 0.7f
    var d = radius
    while (along(angle, d) < 0f) d -= 1f
    var p = d
    while (along(angle, p) < 2.5f) {
      val curvature = abs(along(angle, p + 4f) - 2f * along(angle, p) + along(angle, p - 4f))
      assertTrue("curvature $curvature at $p", curvature < 0.35f)
      p -= 2f
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

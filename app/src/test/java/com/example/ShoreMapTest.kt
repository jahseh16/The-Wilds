package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoreMapTest {

  @Test
  fun encodeDecodeRoundTripsWithinTheByteResolution() {
    for (depth in listOf(-5.9f, -1f, -0.05f, 0f, 0.05f, 0.8f, 3.3f, 5f, 5.9f)) {
      assertEquals(depth, ShoreMap.decode(ShoreMap.encode(depth)), ShoreMap.DEPTH_RANGE / 255f)
    }
  }

  @Test
  fun valuesOutsideTheRangeSaturate() {
    assertEquals(0, ShoreMap.encode(-100f))
    assertEquals(255, ShoreMap.encode(100f))
  }

  @Test
  fun seaLevelIsTheMidpointSoLandIsDarkerAndDeepWaterBrighter() {
    val mid = ShoreMap.encode(0f)
    assertTrue(mid in 127..128)
    assertTrue(ShoreMap.encode(-2f) < mid)
    assertTrue(ShoreMap.encode(2f) > mid)
    // The shader discards the sea where decoded depth < -0.04 m; sea level itself must survive
    assertTrue(ShoreMap.decode(mid) > -0.04f)
  }

  @Test
  fun bakeOfTheIslandHasLandInTheMiddleAndDeepSeaAtTheRim() {
    val island = IslandTerrain(IslandTerrain.DEFAULT_SEED, 80f, 80f, 72f, 0f)
    val size = 160
    val map = ShoreMap.bake(size, size, 0f, 0f, 160f, 160f, 0f, island::heightAt)
    assertEquals(size * size, map.size)

    fun depthAt(x: Int, z: Int) = ShoreMap.decode(map[z * size + x])
    assertTrue("island centre is dry land", depthAt(80, 80) < -1f)
    // World corners are open sea floor (about 5 m deep)
    for ((x, z) in listOf(0 to 0, 159 to 0, 0 to 159, 159 to 159)) {
      assertTrue("corner ($x,$z) is deep water: ${depthAt(x, z)}", depthAt(x, z) > 3f)
    }
    // Walking from the centre to the edge the map must cross the shoreline (depth 0) exactly once
    // in the sense of going from land to water and staying water
    var crossings = 0
    var wasLand = depthAt(80, 80) < 0f
    for (x in 80 until size) {
      val land = depthAt(x, 80) < 0f
      if (land != wasLand) crossings++
      wasLand = land
    }
    assertEquals("one land -> sea transition along the +X ray", 1, crossings)
  }

  @Test
  fun rowIsZAndColumnIsX() {
    // Height rises with x only: depth must vary along columns, never along rows
    val map = ShoreMap.bake(8, 4, 0f, 0f, 8f, 4f, 0f) { x, _ -> 3f - x }
    for (row in 1 until 4) for (col in 0 until 8) assertEquals(map[col], map[row * 8 + col])
    assertTrue(ShoreMap.decode(map[0]) < ShoreMap.decode(map[7]))
  }
}

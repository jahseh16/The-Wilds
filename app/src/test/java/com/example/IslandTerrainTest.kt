package com.example

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandTerrainTest {

  private val radius = IslandTerrain.REFERENCE_RADIUS
  private val centre = radius + 12f
  private val land = IslandTerrain(IslandTerrain.DEFAULT_SEED, centre, centre, radius, 0f)

  /** Normalised map coordinates: u east, v south, both -1..1. */
  private fun at(u: Float, v: Float) = land.heightAt(centre + u * radius, centre + v * radius)

  @Test
  fun heightsStayInsideTheWorldColumn() {
    var min = Float.MAX_VALUE
    var max = -Float.MAX_VALUE
    var z = 0f
    while (z <= 2f * centre) {
      var x = 0f
      while (x <= 2f * centre) {
        val h = land.heightAt(x, z)
        min = minOf(min, h); max = maxOf(max, h)
        x += 8f
      }
      z += 8f
    }
    // The chunk column spans -12 .. 52 m
    assertTrue("highest point $max", max < IslandTerrain.MAX_HEIGHT_ABOVE_SEA + 0.01f)
    assertTrue("there is a real mountain range: $max", max > 38f)
    assertTrue("lowest point $min", min > -IslandTerrain.SEABED_DEPTH - 1f)
    assertTrue("sea floor reached: $min", min < -IslandTerrain.SEABED_DEPTH + 1f)
  }

  @Test
  fun aPeninsulaOpenToTheSeaWestSouthAndEastRisingToTheNorth() {
    // Sea along the west, south and east edges of the map
    for (t in listOf(-0.6f, -0.2f, 0.2f, 0.6f)) {
      assertTrue("west edge at v=$t is sea", at(-0.97f, t) < -3f)
      assertTrue("east edge at v=$t is sea", at(0.97f, t) < -3f)
      assertTrue("south edge at u=$t is sea", at(t, 0.97f) < -3f)
    }
    // ...while the north edge is land: the range runs off the map to the mainland
    for (u in listOf(-0.4f, 0f, 0.4f)) assertTrue("north edge at u=$u is high land: ${at(u, -0.95f)}", at(u, -0.95f) > 20f)
    // Rising northwards: average height of east-west bands
    fun band(v: Float): Float = (-5..5).map { at(it * 0.08f, v) }.average().toFloat()
    val south = band(0.55f); val middle = band(0.0f); val north = band(-0.75f)
    println("bands: south $south, middle $middle, north $north")
    assertTrue("south lowland $south < middle $middle < northern range $north", south < middle && middle < north)
    assertTrue("the range is high ($north m)", north > 28f)
    // The range is rock, the south is not
    assertTrue(land.rockZoneAt(centre, centre - 0.85f * radius) > 0.8f)
    assertTrue(land.rockZoneAt(centre + 0.05f * radius, centre + 0.5f * radius) < 0.5f)
  }

  /** Walks north from the south edge along x = u until dry land; returns the beach (metres of sand below 3 m) and the steepest step. */
  private fun southCoast(u: Float): Pair<Float, Float> {
    val x = centre + u * radius
    var z = centre + radius * 0.98f
    while (land.heightAt(x, z) < 0f) z -= 1f
    var width = 0f; var maxStep = 0f
    while (land.heightAt(x, z) < 3f && width < 200f) {
      maxStep = maxOf(maxStep, abs(land.heightAt(x, z - 1f) - land.heightAt(x, z)))
      width += 1f; z -= 1f
    }
    return width to maxStep
  }

  @Test
  fun southCoastHasWideBeachesARockyCapeAndASeaCliff() {
    var beaches = 0
    for (u in listOf(-0.20f, -0.12f, -0.04f, 0.04f, 0.12f, 0.20f)) {
      val (width, step) = southCoast(u)
      if (width > 25f && step < 0.15f) beaches++
    }
    assertTrue("wide walkable beaches along the south coast: $beaches of 6", beaches >= 5)
    // SW cape: rocky (cliff-like) coast that sticks out south of its neighbours
    val capeU = -0.42f
    val (capeWidth, capeStep) = southCoast(capeU)
    assertTrue("the cape is an abrupt rocky coast (beach $capeWidth m, step $capeStep)", capeWidth < 20f && capeStep > 0.25f)
    fun shoreV(u: Float): Float { var v = 0.99f; while (at(u, v) < 0f) v -= 0.002f; return v }
    assertTrue("the cape juts out to sea", shoreV(capeU) > shoreV(-0.25f) + 0.02f && shoreV(capeU) > shoreV(-0.60f) + 0.02f)
    // SE cliff
    val (cliffWidth, cliffStep) = southCoast(0.45f)
    assertTrue("south-east sea cliff (beach $cliffWidth m, step $cliffStep)", cliffWidth < 20f && cliffStep > 0.25f)
  }

  @Test
  fun theSinkholeIsDeepSteepAndHasAWalkableLedgeDown() {
    val cx = land.craterX; val cz = land.craterZ; val r = land.craterRadius
    assertTrue("centre-west", cx < centre - 0.2f * radius && abs(cz - centre) < 0.2f * radius)
    assertTrue("radius ${r}", r in 65f..80f)
    val rim = land.heightAt(cx + r + 2f, cz)
    val floor = land.heightAt(cx + r * 0.6f, cz)
    println("sinkhole rim $rim floor $floor")
    assertTrue("deep: ${rim - floor} m", rim - floor > 15f)
    assertTrue("floor above the sea", floor > 0.5f)
    // Sheer walls somewhere around: > 2 m of drop per metre across the wall
    var steepest = 0f
    for (k in 0 until 36) {
      val a = k * 0.1745f
      var rr = r - 16f
      while (rr < r) {
        val h0 = land.heightAt(cx + cos(a) * rr, cz + sin(a) * rr)
        val h1 = land.heightAt(cx + cos(a) * (rr + 1f), cz + sin(a) * (rr + 1f))
        steepest = maxOf(steepest, h1 - h0)
        rr += 1f
      }
    }
    assertTrue("near-vertical walls (steepest $steepest m/m)", steepest > 2f)
    // The ledge: start at the rim and walk it clockwise (in plan) down to the floor, never a step > 0.35 m per metre
    val rLedge = r - 4.5f - 2.0f
    var a = land.craterPathAngle - 0.02f
    var prev = land.heightAt(cx + cos(a) * rLedge, cz + sin(a) * rLedge)
    val start = prev
    var walked = 0f; var worst = 0f
    while (walked < 400f) {
      a -= 1f / rLedge
      val h = land.heightAt(cx + cos(a) * rLedge, cz + sin(a) * rLedge)
      worst = maxOf(worst, abs(h - prev))
      prev = h; walked += 1f
      if (h < floor + 0.5f) break
    }
    println("ledge: from $start down to $prev in $walked m, worst step $worst")
    assertTrue("the ledge starts at the rim (${start} vs rim $rim)", start > rim - 2.5f)
    assertTrue("...and reaches the floor", prev < floor + 0.6f)
    assertTrue("walkable grade all the way (worst ${worst} m per metre)", worst < 0.35f)
    // The lake on the floor
    val lake = land.lakes[0]
    assertEquals(cx, lake.x, 0.01f)
    assertTrue("water in the middle of the sinkhole", land.heightAt(cx, cz) < lake.level - 1f)
  }

  @Test
  fun inlandLakesSitAboveTheSeaAndHoldTheirWater() {
    val lakes = land.lakes.drop(1)
    assertTrue("2-3 inland lakes", lakes.size in 2..3)
    var east = 0
    for (l in lakes) {
      if (l.x > centre) east++
      assertTrue("lake at ${l.level} m is above the sea", l.level > 2f)
      assertTrue("deep in the middle", land.heightAt(l.x, l.z) < l.level - 1.5f)
      // the water plane spans waterExtent: every point of that square beyond the shore is dry land
      val e = l.waterExtent
      var leaks = 0
      for (i in 0..40) for (j in 0..40) {
        val x = l.x - e + 2f * e * i / 40f; val z = l.z - e + 2f * e * j / 40f
        val d = sqrt((x - l.x) * (x - l.x) + (z - l.z) * (z - l.z))
        if (d > l.radius * 1.4f && land.heightAt(x, z) < l.level) leaks++
      }
      assertEquals("lake at (${l.x}, ${l.z}) never shows over land outside its banks", 0, leaks)
    }
    assertTrue("they are in the east half", east == lakes.size)
    // A shallow creek bed links them: lower than the ground beside it, but drier than the water
    val a = lakes[0]; val b = lakes[1]
    val mx = (a.x + b.x) * 0.5f; val mz = (a.z + b.z) * 0.5f
    val nx = -(b.z - a.z); val nz = b.x - a.x; val nl = sqrt(nx * nx + nz * nz)
    val side = (land.heightAt(mx + nx / nl * 15f, mz + nz / nl * 15f) + land.heightAt(mx - nx / nl * 15f, mz - nz / nl * 15f)) * 0.5f
    assertTrue("creek bed between the lakes is a depression", land.heightAt(mx, mz) < side + 0.5f)
  }

  @Test
  fun spawnIsAnOpenSouthCentralMeadowNearALakeSlopingToTheCoast() {
    val sx = land.spawnX; val sz = land.spawnZ
    assertTrue("south-central", abs(sx - centre) < 0.15f * radius && sz > centre + 0.3f * radius)
    // distance to the south shoreline, straight south
    var z = sz
    while (land.heightAt(sx, z) > 0f) z += 1f
    val toShore = z - sz
    assertTrue("$toShore m from the beach", toShore in 120f..170f)
    val nearestLake = land.lakes.drop(1).minOf { sqrt((it.x - sx) * (it.x - sx) + (it.z - sz) * (it.z - sz)) - it.radius }
    assertTrue("a lake shore $nearestLake m away", nearestLake < 120f)
    val out = FloatArray(4)
    land.biomeAt(sx, sz, out)
    assertTrue("open ground (forest ${out[0]})", out[0] < 0.05f)
    assertTrue("gentle (slope ${out[3]})", out[3] < 0.12f)
    assertTrue("slopes down towards the coast", land.heightAt(sx, sz + 15f) < land.heightAt(sx, sz - 15f))
    // the forest starts a short walk away: pines around the clearing
    var forested = 0
    for (k in 0 until 16) {
      val a = k * 0.3927f
      land.biomeAt(sx + cos(a) * 45f, sz + sin(a) * 45f, out)
      if (out[0] > 0.4f) forested++
    }
    assertTrue("forest around the meadow ($forested of 16 directions at 45 m)", forested >= 4)
  }

  @Test
  fun trailsAreOpenGravelAndLeadFromTheSpawn() {
    val out = FloatArray(4)
    assertTrue("a trail leaves the spawn", land.trailAt(land.spawnX, land.spawnZ) > 0.9f)
    var onTrail = 0
    var z = 0f
    while (z < 2f * centre) {
      var x = 0f
      while (x < 2f * centre) {
        if (land.trailAt(x, z) > 0.95f) {
          land.biomeAt(x, z, out)
          if (out[2] > 0.5f) {
            onTrail++
            assertEquals("no trees on a trail", 0f, out[0], 0.02f)
            assertTrue("trail is gravel/dirt", out[1] > 0.8f)
          }
        }
        x += 3f
      }
      z += 3f
    }
    assertTrue("trails exist ($onTrail samples)", onTrail > 150)
  }

  @Test
  fun biomesMixDenseForestMeadowsAndRock() {
    val out = FloatArray(4)
    var forest = 0; var gravel = 0; var open = 0; var n = 0
    var z = centre - 0.9f * radius
    while (z < centre + 0.9f * radius) {
      var x = centre - 0.9f * radius
      while (x < centre + 0.9f * radius) {
        land.biomeAt(x, z, out)
        assertTrue(out[0] in 0f..1f && out[1] in 0f..1f)
        if (out[2] > 2f) {
          n++
          if (out[0] > 0.5f) forest++
          if (out[1] > 0.5f) gravel++
          if (out[0] < 0.1f && out[1] < 0.3f && out[3] < 0.25f) open++
        }
        x += 9f
      }
      z += 9f
    }
    println("forest ${forest.toFloat() / n} gravel ${gravel.toFloat() / n} open ${open.toFloat() / n}")
    assertTrue("forest ${forest.toFloat() / n}", forest.toFloat() / n in 0.25f..0.70f)
    assertTrue("gravel and rock ${gravel.toFloat() / n}", gravel.toFloat() / n in 0.10f..0.50f)
    assertTrue("open meadows ${open.toFloat() / n}", open.toFloat() / n > 0.06f)
  }

  @Test
  fun noForestInTheWaterOrOnTheBeach() {
    val out = FloatArray(4)
    for (u in listOf(-0.3f, -0.1f, 0.1f, 0.3f)) {
      land.biomeAt(centre + u * radius, centre + 0.97f * radius, out)
      assertEquals("forest at sea", 0f, out[0], 0f)
      var z = centre + radius * 0.98f
      while (land.heightAt(centre + u * radius, z) < 0f) z -= 1f
      land.biomeAt(centre + u * radius, z - 2f, out)
      assertEquals("forest on the waterline", 0f, out[0], 0f)
    }
  }

  @Test
  fun deterministicAndCheapToEvaluate() {
    val other = IslandTerrain(IslandTerrain.DEFAULT_SEED, centre, centre, radius, 0f)
    for (i in 0 until 500) {
      val x = (i * 37 % 1400).toFloat(); val z = (i * 91 % 1400).toFloat()
      assertEquals(land.heightAt(x, z), other.heightAt(x, z), 0f)
    }
    assertEquals(land.spawnX, other.spawnX, 0f)
    // warm up, then time (generous bound: catches accidental O(n) work per sample)
    var acc = 0f
    repeat(50_000) { acc += land.heightAt((it % 700) * 2f, (it / 700) * 2f) }
    val t0 = System.nanoTime()
    repeat(200_000) { acc += land.heightAt((it % 700) * 2f, (it / 700 % 700) * 2f) }
    val ns = (System.nanoTime() - t0) / 200_000.0
    println("heightAt: %.0f ns per sample ($acc)".format(ns))
    assertTrue("heightAt costs $ns ns", ns < 6000)
  }

  @Test
  fun aSmallWorldIsAScaledCopy() {
    val small = IslandTerrain(IslandTerrain.DEFAULT_SEED, 80f, 80f, 72f, 0f)
    assertTrue(small.heightAt(80f, 80f) > 1f)
    assertTrue(small.heightAt(80f, 80f + 72f) < -3f)
    assertTrue(small.craterRadius < 10f)
    assertEquals(small.heightAt(33f, 41f), small.heightAt(33f, 41f), 0f)
  }
}

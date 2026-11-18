package com.example

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldScatterTest {

  private val radius = IslandTerrain.REFERENCE_RADIUS
  private val centre = radius + 12f
  private val terrain = IslandTerrain(IslandTerrain.DEFAULT_SEED, centre, centre, radius, 0f)
  private val none = emptyList<Clearing>()

  private fun far(cx: Int, cz: Int, clearings: List<Clearing> = none) =
    ArrayList<Placement>().also { WorldScatter.planFar(terrain, cx, cz, 16f, clearings, it) }

  private fun near(cx: Int, cz: Int, clearings: List<Clearing> = none) =
    ArrayList<Placement>().also { WorldScatter.planNear(terrain, cx, cz, 16f, clearings, it) }

  /** Chunks of the island interior and coast, sampled across the whole map. */
  private val chunks = (10..54 step 3).flatMap { cx -> (10..54 step 3).map { cz -> cx to cz } }

  @Test
  fun planningIsDeterministicAndOrderIndependent() {
    val a = far(30, 31); val b = far(30, 31)
    assertEquals(a.size, b.size)
    for (i in a.indices) {
      assertEquals(a[i].kind, b[i].kind); assertEquals(a[i].x, b[i].x, 0f); assertEquals(a[i].scale, b[i].scale, 0f)
    }
    far(5, 5); far(40, 40)
    assertEquals(a.size, far(30, 31).size)
    assertEquals(near(30, 31).size, near(30, 31).size)
  }

  @Test
  fun everyObjectStaysInsideItsChunkOnTheGroundAndOutOfTheWater() {
    var total = 0
    for ((cx, cz) in chunks) {
      for (p in far(cx, cz) + near(cx, cz)) {
        total++
        assertTrue("x ${p.x} inside chunk $cx", p.x >= cx * 16f && p.x < (cx + 1) * 16f)
        assertTrue("z ${p.z} inside chunk $cz", p.z >= cz * 16f && p.z < (cz + 1) * 16f)
        assertEquals("sits on the terrain", terrain.heightAt(p.x, p.z), p.y, 1e-3f)
        assertTrue("${p.kind} at height ${p.y} would be in the water", p.y > 0.1f)
      }
    }
    assertTrue("something was planted ($total)", total > 2000)
  }

  @Test
  fun scaleAndKindFollowTheRules() {
    val kinds = HashMap<Kind, Int>()
    val b = FloatArray(4)
    for ((cx, cz) in chunks) for (p in far(cx, cz) + near(cx, cz)) {
      kinds.merge(p.kind, 1, Int::plus)
      terrain.biomeAt(p.x, p.z, b)
      when (p.kind) {
        Kind.PINE_1, Kind.PINE_2, Kind.PINE_3 -> {
          assertTrue("pine scale ${p.scale}", p.scale in PineTreeGenerator.MIN_SCALE..PineTreeGenerator.MAX_SCALE)
          val h = PineTreeGenerator.SPECS[p.kind.pine].height * p.scale
          assertTrue("pine height $h", h in 8f..14f)
          assertTrue("pine in forest ${b[0]}", b[0] >= 0.40f)
          assertTrue("pine on gentle ground ${b[3]}", b[3] < 0.65f)
          assertTrue("pine not on a trail", !WorldScatter.onTrail(p.x, p.z, p.y))
        }
        Kind.ROCK -> assertTrue("boulder height ${p.y}", p.y > 0.8f)
        Kind.PEBBLE_1, Kind.PEBBLE_2, Kind.PEBBLE_3, Kind.PEBBLE_PATCH -> assertTrue("pebbles on the shore, h=${p.y}", p.y < 3.3f)
        Kind.STICK_1, Kind.STICK_2, Kind.STICK_3 -> assertTrue("sticks under trees ${b[0]}", b[0] >= 0.35f)
        Kind.GRASS_1, Kind.GRASS_2, Kind.GRASS_3 -> assertTrue("grass not on the beach h=${p.y}", p.y >= 3.2f || terrain.lakeShoreAt(p.x, p.z) > 0.2f)
        else -> Unit
      }
    }
    // rare kinds (beach pebble patches) may miss the sparse sample: then look at every chunk
    val missing = Kind.ALL.filter { !it.isLod && (kinds[it] ?: 0) == 0 }.toMutableSet()
    if (missing.isNotEmpty()) {
      for (cx in 8..56) for (cz in 8..56) {
        for (p in far(cx, cz) + near(cx, cz)) missing.remove(p.kind)
        if (missing.isEmpty()) break
      }
    }
    assertTrue("kinds never placed: $missing (sample: $kinds)", missing.isEmpty())
    // Dense forest, but bounded per chunk (triangle budget)
    val densest = chunks.maxOf { (cx, cz) -> far(cx, cz).count { it.kind.isTree } }
    assertTrue("densest chunk has $densest pines", densest in 6..14)
  }

  @Test
  fun theSpawnClearingStaysFree() {
    val sx = 316.4f; val sz = 453.8f
    val clearing = listOf(Clearing(sx, sz, ChunkManager.SPAWN_CLEARING_RADIUS))
    var removed = 0
    for (cz in 26..30) for (cx in 18..21) {
      val without = far(cx, cz) + near(cx, cz).filter { !it.kind.name.startsWith("GRASS") && it.kind != Kind.FERN && it.kind != Kind.WEED && it.kind != Kind.DANDELION }
      val with = far(cx, cz, clearing) + near(cx, cz, clearing).filter { !it.kind.name.startsWith("GRASS") && it.kind != Kind.FERN && it.kind != Kind.WEED && it.kind != Kind.DANDELION }
      for (p in with) {
        val d = sqrt((p.x - sx) * (p.x - sx) + (p.z - sz) * (p.z - sz))
        assertTrue("${p.kind} at ${d} m from the spawn", d >= ChunkManager.SPAWN_CLEARING_RADIUS)
      }
      removed += without.size - with.size
    }
    assertTrue(removed >= 0)
  }

  @Test
  fun nearContentIsDenseButCheap() {
    var best = 0
    for ((cx, cz) in chunks) best = maxOf(best, near(cx, cz).size)
    assertTrue("densest near chunk: $best instances", best in 60..450)
  }
}

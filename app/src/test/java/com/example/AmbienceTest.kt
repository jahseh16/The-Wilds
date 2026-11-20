package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbienceTest {

  private val radius = 700f
  private val terrain = IslandTerrain(IslandTerrain.DEFAULT_SEED, radius * 1.02f, radius * 1.02f, radius, 0f)

  private fun state(coast: Float = 0f, lake: Float = 0f, sun: Float = 0.8f, underground: Float = 0f) =
    AmbienceState().also { it.coast = coast; it.lake = lake; it.underground = underground; AmbienceMix.setTimeOfDay(it, sun) }

  @Test
  fun wavesFollowTheCoastAndLakeFollowsTheLake() {
    assertTrue(AmbienceMix.waves(state(coast = 1f)) > AmbienceMix.waves(state(coast = 0.5f)))
    assertEquals(0f, AmbienceMix.waves(state(coast = 0f)), 1e-6f)
    assertEquals(0f, AmbienceMix.lake(state(lake = 0f)), 1e-6f)
    assertTrue(AmbienceMix.lake(state(lake = 1f)) > 0.2f)
  }

  @Test
  fun birdsByDayCricketsByNightWithACrossfadeAtDusk() {
    val noon = state(sun = 0.9f); val midnight = state(sun = -0.9f); val dusk = state(sun = 0.1f)
    assertTrue(AmbienceMix.birds(noon) > 0.3f && AmbienceMix.crickets(noon) < 0.01f)
    assertTrue(AmbienceMix.crickets(midnight) > 0.25f && AmbienceMix.birds(midnight) < 0.01f)
    assertTrue("both audible at dusk", AmbienceMix.birds(dusk) > 0.01f && AmbienceMix.crickets(dusk) > 0.02f)
    // no silent gap: the combined bed never collapses while the sun goes down
    var e = 0.9f
    while (e > -0.9f) {
      val s = state(sun = e)
      assertTrue("bed at sun=$e", AmbienceMix.birds(s) + AmbienceMix.crickets(s) > 0.05f)
      e -= 0.05f
    }
  }

  @Test
  fun theCoastMasksTheBirdsAndTunnelsMuffleEverything() {
    assertTrue(AmbienceMix.birds(state(coast = 1f)) < AmbienceMix.birds(state(coast = 0f)) * 0.3f)
    val open = state(coast = 1f, lake = 1f); val tunnel = state(coast = 1f, lake = 1f, underground = 1f)
    assertTrue(AmbienceMix.waves(tunnel) < AmbienceMix.waves(open) * 0.3f)
    assertTrue(AmbienceMix.wind(tunnel) < AmbienceMix.wind(open) * 0.5f)
  }

  @Test
  fun samplerSeesTheSeaFromTheBeachButNotFromTheInterior() {
    val spawnShore = terrain.seaDistanceAt(terrain.spawnX, terrain.spawnZ)
    assertTrue("spawn is 120..240 m from the sea (was $spawnShore)", spawnShore in 100f..240f)
    val amb = Ambience(terrain)
    // far inland (north range): no waves
    amb.update(1f, radius * 1.02f, radius * 0.45f, terrain.heightAt(radius * 1.02f, radius * 0.45f), 0.9f)
    assertTrue("inland coast factor ${amb.state.coast}", amb.state.coast < 0.05f)
    // standing on the south beach: waves loud
    var z = terrain.spawnZ
    while (terrain.heightAt(terrain.spawnX, z) > 1.5f) z += 2f
    amb.update(1f, terrain.spawnX, z, terrain.heightAt(terrain.spawnX, z), 0.9f)
    assertTrue("beach coast factor ${amb.state.coast}", amb.state.coast > 0.8f)
  }

  @Test
  fun lakeFactorPeaksAtTheLakesAndWaterLevelsAreConsistent() {
    val amb = Ambience(terrain)
    val l = terrain.lakes[1]
    amb.update(1f, l.x, l.z, terrain.heightAt(l.x, l.z), 0.9f)
    assertEquals(1f, amb.state.lake, 1e-3f)
    assertEquals("lake water level", l.level, terrain.waterLevelAt(l.x, l.z), 1e-4f)
    assertTrue("dry at the spawn", terrain.waterLevelAt(terrain.spawnX, terrain.spawnZ).isNaN())
    assertEquals("the open sea", 0f, terrain.waterLevelAt(radius * 1.02f - radius * 1.1f, radius * 1.02f), 1e-4f)
    assertEquals(0f, terrain.seaDistanceAt(radius * 1.02f - radius * 1.1f, radius * 1.02f), 1e-4f)
    amb.update(1f, terrain.spawnX, terrain.spawnZ, terrain.heightAt(terrain.spawnX, terrain.spawnZ) - 3f, 0.9f)
    assertEquals(1f, amb.state.underground, 0f)
  }

  @Test
  fun seaDistanceIsFastEnoughToRunFourTimesASecond() {
    var acc = 0f
    repeat(50) { acc += terrain.seaDistanceAt(terrain.spawnX + it, terrain.spawnZ) }
    val t0 = System.nanoTime()
    repeat(200) { acc += terrain.seaDistanceAt(terrain.spawnX + it, terrain.spawnZ - it) }
    val micros = (System.nanoTime() - t0) / 200 / 1000
    println("seaDistanceAt: $micros us/call ($acc)")
    assertTrue("seaDistanceAt $micros us", micros < 2000)
  }
}

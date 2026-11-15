package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DayNightCycleTest {

  private fun luminance(c: Color): Float = 0.299f * c.r + 0.587f * c.g + 0.114f * c.b

  @Test
  fun skippingWalksThroughAllFourPhasesAndWraps() {
    val cycle = DayNightCycle(startPhase = DayPhase.DAY)
    val seen = ArrayList<DayPhase>()
    repeat(4) {
      cycle.skipToNextPhase()
      seen.add(cycle.phase)
      assertEquals(cycle.phase.keyTime, cycle.timeOfDay, 1e-6f)
    }
    assertEquals(listOf(DayPhase.DUSK, DayPhase.NIGHT, DayPhase.DAWN, DayPhase.DAY), seen)
  }

  @Test
  fun phaseLabelsMatchTheHud() {
    assertEquals(
      listOf("AMANECER", "DIA", "ATARDECER", "NOCHE"),
      DayPhase.ALL.map { it.label }
    )
  }

  @Test
  fun fullCycleTakesCycleSecondsAndVisitsEveryPhaseInOrder() {
    val cycle = DayNightCycle(startPhase = DayPhase.DAY)
    val order = ArrayList<DayPhase>().apply { add(cycle.phase) }
    var seconds = 0f
    while (seconds < cycle.cycleSeconds - 0.01f) {
      cycle.update(0.5f)
      seconds += 0.5f
      if (cycle.phase != order.last()) order.add(cycle.phase)
    }
    assertEquals(DayPhase.DAY.keyTime, cycle.timeOfDay, 1e-3f)
    assertEquals(
      listOf(DayPhase.DAY, DayPhase.DUSK, DayPhase.NIGHT, DayPhase.DAWN, DayPhase.DAY),
      order
    )
    assertEquals(720f, DayNightCycle.DEFAULT_CYCLE_SECONDS, 0f)
  }

  @Test
  fun nightIsMuchDarkerThanDayAndFogDarkensWithIt() {
    val day = DayNightCycle(startPhase = DayPhase.DAY)
    val night = DayNightCycle(startPhase = DayPhase.NIGHT)
    assertTrue(luminance(night.ambientColor) < luminance(day.ambientColor) * 0.2f)
    assertTrue(luminance(night.skyColor) < 0.1f)
    assertTrue(luminance(night.fogColor) < 0.1f)
    assertTrue(luminance(night.lightColor) < luminance(day.lightColor) * 0.4f)
    assertTrue(night.fogDensity > day.fogDensity)
  }

  @Test
  fun lightAlwaysPointsDownAndNothingJumpsBetweenFrames() {
    val cycle = DayNightCycle(startPhase = DayPhase.DAWN)
    val prevSky = Color(cycle.skyColor)
    val prevFog = Color(cycle.fogColor)
    val prevAmbient = Color(cycle.ambientColor)
    val prevLight = Color(cycle.lightColor)
    var prevElevation = cycle.sunElevation

    var maxStep = 0f
    var steps = 0
    while (steps < 1440) { // two full cycles in 1 s steps
      cycle.update(1f)
      assertTrue("light must never point upwards", cycle.lightDirection.y <= 1e-5f)
      assertEquals("direction is unit length", 1f, cycle.lightDirection.len(), 1e-3f)
      for ((now, before) in listOf(
        cycle.skyColor to prevSky, cycle.fogColor to prevFog,
        cycle.ambientColor to prevAmbient, cycle.lightColor to prevLight
      )) {
        maxStep = maxOf(maxStep, abs(now.r - before.r), abs(now.g - before.g), abs(now.b - before.b))
        before.set(now)
      }
      // The sun/moon swap flips the light direction; it must happen while the light is ~off
      if ((prevElevation >= 0f) != (cycle.sunElevation >= 0f)) {
        assertTrue("light must be ~0 at the horizon swap", luminance(cycle.lightColor) < 0.02f)
      }
      prevElevation = cycle.sunElevation
      steps++
    }
    // The sun fading in is the steepest change (~0.034 per second, i.e. ~0.0006 per frame at 60 fps).
    // A real pop would be >= 0.2 in a single step.
    assertTrue("colours change smoothly (max per-second step $maxStep)", maxStep < 0.05f)
  }

  @Test
  fun applyToWritesAmbientFogAndSunIntoTheEnvironment() {
    val cycle = DayNightCycle(startPhase = DayPhase.DAY)
    val env = Environment().apply {
      set(ColorAttribute.createAmbientLight(0f, 0f, 0f, 1f))
      set(ColorAttribute.createFog(0f, 0f, 0f, 1f))
    }
    val sun = DirectionalLight()
    cycle.applyTo(env, sun)

    val ambient = (env.get(ColorAttribute.AmbientLight) as ColorAttribute).color
    val fog = (env.get(ColorAttribute.Fog) as ColorAttribute).color
    assertEquals(cycle.ambientColor.r, ambient.r, 1e-6f)
    assertEquals(cycle.fogColor.b, fog.b, 1e-6f)
    assertEquals(cycle.lightColor.g, sun.color.g, 1e-6f)
    assertEquals(cycle.lightDirection.y, sun.direction.y, 1e-6f)
  }
}

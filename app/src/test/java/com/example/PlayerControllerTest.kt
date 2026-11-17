package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerControllerTest {

  private fun walk(pc: PlayerController, speed: Float, seconds: Float, strafe: Float = 0f): Pair<Float, Float> {
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
    var t = 0f
    while (t < seconds) {
      pc.update(1f / 60f, speed, strafe, grounded = true, crawling = false)
      if (t > 1f) { lo = minOf(lo, pc.bobVertical); hi = maxOf(hi, pc.bobVertical) }
      t += 1f / 60f
    }
    return lo to hi
  }

  @Test
  fun bobGrowsWithSpeedAndFootstepsFollowTheGait() {
    val walker = PlayerController(); var walkSteps = 0
    walker.stepListener = PlayerController.StepListener { _, _ -> walkSteps++ }
    val (wl, wh) = walk(walker, 2.5f, 6f)
    val runner = PlayerController(); var runSteps = 0
    runner.stepListener = PlayerController.StepListener { _, _ -> runSteps++ }
    val (rl, rh) = walk(runner, 5.5f, 6f)
    println("walk bob ${wh - wl} m, $walkSteps steps; run bob ${rh - rl} m, $runSteps steps")
    assertTrue("walk bob ${wh - wl}", (wh - wl) in 0.025f..0.05f)
    assertTrue("run bob ${rh - rl}", (rh - rl) in 0.05f..0.08f)
    // cadence: ~2.2 steps/s walking, ~3.4 running
    assertTrue("walk steps $walkSteps", walkSteps in 11..16)
    assertTrue("run steps $runSteps", runSteps in 18..24)
  }

  @Test
  fun bobFadesOutWhenStopping() {
    val pc = PlayerController()
    walk(pc, 4f, 3f)
    var steps = 0
    pc.stepListener = PlayerController.StepListener { _, _ -> steps++ }
    repeat(90) { pc.update(1f / 60f, 0f, 0f, grounded = true, crawling = false) }
    assertTrue("envelope ${pc.envelope}", pc.envelope < 0.01f)
    assertTrue(abs(pc.bobVertical) < 0.001f && abs(pc.bobLateral) < 0.001f)
    assertTrue("no footsteps standing still ($steps)", steps <= 1)
  }

  @Test
  fun landingDipsAboutTwelveCentimetresAndRecoversElastically() {
    val pc = PlayerController()
    var landings = 0
    pc.stepListener = PlayerController.StepListener { _, landing -> if (landing) landings++ }
    pc.update(1f / 60f, 0f, 0f, grounded = true, crawling = false, landingSpeed = 8.2f)
    var minOffset = 0f; var maxAfter = 0f; var t = 0f
    while (t < 1.2f) {
      pc.update(1f / 60f, 0f, 0f, grounded = true, crawling = false)
      minOffset = minOf(minOffset, pc.landingOffset)
      if (t > 0.15f) maxAfter = maxOf(maxAfter, pc.landingOffset)
      t += 1f / 60f
    }
    println("landing dip $minOffset m, overshoot $maxAfter m")
    assertEquals(1, landings)
    assertTrue("dip $minOffset", -minOffset in 0.09f..0.16f)
    assertTrue("elastic overshoot $maxAfter", maxAfter > 0.002f && maxAfter < 0.05f)
    assertTrue("settles ${pc.landingOffset}", abs(pc.landingOffset) < 0.003f)
  }

  @Test
  fun strafingLeansTowardsTheMovement() {
    val pc = PlayerController()
    walk(pc, 3f, 2f, strafe = 1f)
    // average roll over a gait cycle
    var sum = 0f
    repeat(120) { pc.update(1f / 60f, 3f, 1f, grounded = true, crawling = false); sum += pc.rollDegrees }
    val avg = sum / 120f
    assertTrue("leans right ($avg deg)", avg in 1.0f..2.2f)
    repeat(120) { pc.update(1f / 60f, 0f, 0f, grounded = true, crawling = false) }
    assertTrue(abs(pc.rollDegrees) < 0.1f)
  }
}

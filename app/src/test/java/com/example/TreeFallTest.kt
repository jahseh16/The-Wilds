package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFallTest {

  private fun fall(ground: (Float, Float) -> Float = { _, _ -> 0f }, length: Float = 11f) =
    TreeFall(0f, 0.45f, 0f, 1f, 0f, length, 0.3f, 0.3f, ground)

  @Test
  fun aFelledTrunkStartsSlowAcceleratesAndLandsAwayFromThePlayer() {
    val f = fall()
    var t = 0f
    var impactAt = -1f
    var angleAt1s = 0f
    while (!f.settled && t < 10f) {
      val e = f.step(1f / 60f)
      t += 1f / 60f
      if (t in 0.99f..1.01f) angleAt1s = f.angle
      if (e == TreeFall.Event.IMPACT && impactAt < 0f) impactAt = t
    }
    println("impact at %.2f s, settled=${f.settled} angle=%.2f".format(impactAt, f.angle))
    assertTrue("it creaks over slowly first (angle after 1 s = $angleAt1s)", angleAt1s < 0.35f)
    assertTrue("falls in a realistic time ($impactAt s)", impactAt in 1.5f..5f)
    assertTrue("it bounced", f.impacts >= 1)
    assertTrue(f.settled)
    assertTrue("lies down on flat ground (angle ${f.angle})", f.angle in 1.3f..1.62f)
    val tip = f.pointAt(f.length, com.badlogic.gdx.math.Vector3())
    assertTrue("tip lands on the far side (+x)", tip.x > 8f)
  }

  @Test
  fun aSlopeStopsItEarlier() {
    val flat = fall()
    val uphill = fall({ x, _ -> x * 0.7f })
    repeat(1200) { flat.step(1f / 120f); uphill.step(1f / 120f) }
    assertTrue(uphill.settled && flat.settled)
    assertTrue("uphill ${uphill.angle} < flat ${flat.angle}", uphill.angle < flat.angle - 0.2f)
  }

  @Test
  fun logsLieOnTheGroundAlongTheTrunk() {
    val f = fall()
    repeat(1200) { f.step(1f / 120f) }
    val spec = PineTreeGenerator.SPECS[1]
    val logs = LogLayout.layout(f, spec, 1f) { _, _ -> 0f }
    assertEquals(3, logs.size)
    assertEquals(2, LogLayout.count(8f))
    for (l in logs) {
      assertTrue("on the ground", l.cy in l.radius * 0.5f..l.radius * 1.2f)
      assertTrue("along +x", l.ax > 0.95f)
      assertTrue("log length ${l.length}", l.length in 1.2f..3.5f)
    }
    assertTrue(logs.zipWithNext().all { (a, b) -> b.cx > a.cx })
  }
}

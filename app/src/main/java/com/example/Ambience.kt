package com.example

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the ambient sound layers should be doing right now. Everything is a 0..1 "how much of this
 * place / time am I in" factor; [AmbienceMix] turns them into loop volumes.
 */
class AmbienceState {
  /** 1 on the shore, falling to 0 at [Ambience.COAST_RANGE] metres from the sea. */
  var coast = 0f
  /** 1 at a lake's edge, falling to 0 at [Ambience.LAKE_RANGE] metres from its water. */
  var lake = 0f
  /** 1 in full daylight. */
  var day = 1f
  /** 1 at night. Overlaps [day] around dusk and dawn so the birds and crickets crossfade. */
  var night = 0f
  /** 0 outdoors, 1 inside a dug tunnel: the outdoor layers are muffled. */
  var underground = 0f
}

/** Pure mapping from [AmbienceState] to loop volumes (unit tested). */
object AmbienceMix {
  const val WIND = 0.30f
  const val WAVES = 0.60f
  const val LAKE = 0.42f
  const val BIRDS = 0.45f
  const val CRICKETS = 0.34f

  private fun outdoor(s: AmbienceState) = 1f - 0.8f * s.underground

  fun wind(s: AmbienceState) = WIND * (1f - 0.7f * s.underground)
  fun waves(s: AmbienceState) = WAVES * s.coast * s.coast * outdoor(s)
  fun lake(s: AmbienceState) = LAKE * s.lake * outdoor(s) * (1f - 0.5f * s.coast)
  fun birds(s: AmbienceState) = BIRDS * s.day * (1f - 0.85f * s.coast) * outdoor(s)
  fun crickets(s: AmbienceState) = CRICKETS * s.night * (1f - 0.9f * s.coast) * outdoor(s)

  /** Daylight and night factors from the sun elevation (sine of the angle above the horizon, -1..1). */
  fun setTimeOfDay(s: AmbienceState, sunElevation: Float) {
    s.day = smooth((sunElevation + 0.02f) / 0.35f)
    s.night = 1f - smooth((sunElevation + 0.10f) / 0.30f)
  }

  fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
}

/**
 * Samples the world a few times a second (the ray march for the coast is the only non-trivial cost)
 * and keeps an [AmbienceState] up to date.
 */
class Ambience(private val terrain: IslandTerrain) {
  val state = AmbienceState()
  private var timer = 0f

  fun update(dt: Float, x: Float, z: Float, feetY: Float, sunElevation: Float) {
    AmbienceMix.setTimeOfDay(state, sunElevation)
    timer -= dt
    if (timer > 0f) return
    timer = SAMPLE_INTERVAL
    state.coast = 1f - AmbienceMix.smooth(terrain.seaDistanceAt(x, z, COAST_RANGE) / COAST_RANGE)
    state.lake = 1f - AmbienceMix.smooth(lakeDistance(x, z) / LAKE_RANGE)
    state.underground = if (feetY < terrain.heightAt(x, z) - 1.2f) 1f else 0f
  }

  /** Metres from (x, z) to the nearest lake's water edge (0 inside it). */
  fun lakeDistance(x: Float, z: Float): Float {
    var best = Float.MAX_VALUE
    for (l in terrain.lakes) {
      val dx = x - l.x; val dz = z - l.z
      best = min(best, max(0f, sqrt(dx * dx + dz * dz) - l.radius))
    }
    return best
  }

  companion object {
    const val COAST_RANGE = 220f
    const val LAKE_RANGE = 70f
    const val SAMPLE_INTERVAL = 0.25f
  }
}

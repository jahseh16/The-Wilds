package com.example

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministic procedural island. Seen from the sea towards the centre it has four zones, each
 * driven by the distance to the (irregular) shoreline:
 *
 *  1. a long, gentle sea shelf that dives from the beach down to the sea floor,
 *  2. a wide, smooth beach up to about [BEACH_TOP_HEIGHT] metres (noise is faded out here on purpose:
 *     flat sand with no bumps, so the beach reads as one broad slope),
 *  3. rolling hills that slowly lift the ground and add low-frequency relief,
 *  4. a ridged mountain massif in the interior, up to ~50 m.
 *
 * Every size is expressed as a fraction of [radius] (profile) or in metres tuned for
 * [REFERENCE_RADIUS] (noise wavelengths, relief), and a smaller island is a scaled copy of it, so
 * the same code serves the 1 km island and the tiny islands the tests use.
 *
 * Pure function of (x, z) and the seed, so any chunk can be (re)generated independently and the
 * analytic height can also be sampled outside the loaded chunk grid.
 */
class IslandTerrain(
  private val seed: Int,
  val centerX: Float,
  val centerZ: Float,
  val radius: Float,
  val seaLevel: Float = 0f
) {

  /** Noise wavelengths grow with the island: 1.0 at the reference radius. */
  private val freqScale = REFERENCE_RADIUS / radius

  /** Vertical relief (rise, hills, mountains) shrinks for islands smaller than the reference. */
  private val relief = (radius / REFERENCE_RADIUS).coerceIn(MIN_RELIEF, 1f)

  private val shoreRadius = radius * SHORE_FRACTION
  private val waterSpan = radius * (1f - SHORE_FRACTION)
  private val beachSpan = radius * BEACH_SPAN_FRACTION

  /** Terrain surface height (world Y) at (x, z). */
  fun heightAt(x: Float, z: Float): Float {
    val dx = x - centerX
    val dz = z - centerZ
    val dist = sqrt(dx * dx + dz * dz)

    // Slow warp of the distance gives bays and headlands instead of a perfect circle
    val coastWarp = fbm(x * COAST_FREQUENCY * freqScale + 91.7f, z * COAST_FREQUENCY * freqScale - 33.1f, 3, 0)
    // Metres inland from the shoreline circle (negative = out at sea)
    val inland = shoreRadius - (dist + coastWarp * radius * COAST_WARP_STRENGTH)

    // Shelf + beach as ONE smoothstep from the sea floor to the top of the beach: no crease at the
    // waterline and a gentle slope (~7 %) through it, which makes the beach wide
    val shelf = ((inland + waterSpan) / (waterSpan + beachSpan)).coerceIn(0f, 1f)
    var height = seaLevel - SEABED_DEPTH + (BEACH_TOP_HEIGHT + SEABED_DEPTH) * smoothstep(shelf)

    if (inland < beachSpan) {
      // Sea floor: a little roughness, fading to nothing at the shoreline so the waterline is clean
      val seaFade = 1f - smoothstep(((inland + waterSpan * 0.4f) / (waterSpan * 0.4f)).coerceIn(0f, 1f))
      if (seaFade > 0f) {
        height += fbm(x * HILL_FREQUENCY * freqScale, z * HILL_FREQUENCY * freqScale, 2, 3) *
          SEABED_ROUGHNESS * seaFade
      }
      return height
    }

    // Interior fraction: 0 at the top of the beach, 1 at the centre of the island
    val f = ((inland - beachSpan) / (shoreRadius - beachSpan)).coerceIn(0f, 1f)
    height += RISE * relief * smoothstep(f)

    val hillMask = smoothstep((f / 0.35f).coerceIn(0f, 1f))
    height += fbm(x * HILL_FREQUENCY * freqScale + 5.2f, z * HILL_FREQUENCY * freqScale + 1.3f, 4, 1) *
      HILL_AMPLITUDE * relief * hillMask

    val mountainMask = smoothstep(((f - 0.20f) / 0.50f).coerceIn(0f, 1f))
    if (mountainMask > 0f) {
      // Ridged noise: 1 - |n| folds the noise into crests, cubed to make them sharper than the valleys
      val ridge = 1f - abs(fbm(x * MOUNTAIN_FREQUENCY * freqScale - 40.7f, z * MOUNTAIN_FREQUENCY * freqScale + 17.9f, 5, 2))
      height += ridge * ridge * ridge * MOUNTAIN_AMPLITUDE * relief * mountainMask
    }

    return softCap(height)
  }

  /** Eases heights above [CAP_START] towards [CAP_START] + [CAP_SOFT_RANGE] so no peak can reach the top of the world. */
  private fun softCap(h: Float): Float {
    val over = h - (seaLevel + CAP_START)
    return if (over <= 0f) h else seaLevel + CAP_START + CAP_SOFT_RANGE * (1f - exp(-over / CAP_SOFT_RANGE))
  }

  private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

  /**
   * Fractal Brownian motion of [octaves] value-noise layers, normalised to roughly [-1, 1].
   * [layer] decorrelates the different uses of the noise.
   */
  private fun fbm(x: Float, z: Float, octaves: Int, layer: Int): Float {
    var sum = 0f
    var amplitude = 1f
    var frequency = 1f
    var norm = 0f
    for (octave in 0 until octaves) {
      sum += valueNoise(x * frequency, z * frequency, octave + layer * 8) * amplitude
      norm += amplitude
      amplitude *= GAIN
      frequency *= LACUNARITY
    }
    return sum / norm
  }

  private fun valueNoise(x: Float, z: Float, octave: Int): Float {
    val x0 = floor(x).toInt()
    val z0 = floor(z).toInt()
    val fx = x - x0
    val fz = z - z0
    val u = fx * fx * (3f - 2f * fx)
    val v = fz * fz * (3f - 2f * fz)

    val h00 = hash(x0, z0, octave)
    val h10 = hash(x0 + 1, z0, octave)
    val h01 = hash(x0, z0 + 1, octave)
    val h11 = hash(x0 + 1, z0 + 1, octave)

    val a = h00 + u * (h10 - h00)
    val b = h01 + u * (h11 - h01)
    return a + v * (b - a)
  }

  /** Integer lattice hash -> [-1, 1]. */
  private fun hash(ix: Int, iz: Int, octave: Int): Float {
    var h = ix * 374761393 + iz * 668265263 + (seed + octave * 7919) * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
  }

  companion object {
    const val DEFAULT_SEED = 1337

    /** The island the constants below are tuned for: 500 m radius = 1 km across. */
    const val REFERENCE_RADIUS = 500f

    /** Sea floor depth under the sea level, in metres. */
    const val SEABED_DEPTH = 7f

    /** Height of the top of the beach above sea level; the hills start from here. */
    const val BEACH_TOP_HEIGHT = 3.5f

    /** Hard ceiling of the terrain above sea level (the soft cap approaches it asymptotically). */
    const val MAX_HEIGHT_ABOVE_SEA = 50f

    // Profile, as fractions of the radius. The shoreline circle sits at 80 % of the radius; the sea
    // floor is reached at 100 % (100 m further out on the reference island), and the beach rises
    // over the 16 % (80 m) inland of the shoreline.
    private const val SHORE_FRACTION = 0.80f
    private const val BEACH_SPAN_FRACTION = 0.16f

    // Noise, in 1/metre for the reference island: coast bays ~220 m, hills ~150 m, mountains ~260 m
    private const val COAST_FREQUENCY = 1f / 220f
    private const val COAST_WARP_STRENGTH = 0.10f // x radius
    private const val HILL_FREQUENCY = 1f / 150f
    private const val MOUNTAIN_FREQUENCY = 1f / 260f
    private const val GAIN = 0.5f
    private const val LACUNARITY = 2.0f

    // Relief, metres (scaled by 'relief' on smaller islands)
    private const val RISE = 6f
    private const val HILL_AMPLITUDE = 7f
    private const val MOUNTAIN_AMPLITUDE = 31f
    private const val SEABED_ROUGHNESS = 0.7f
    private const val MIN_RELIEF = 0.15f

    private const val CAP_START = 44f
    private const val CAP_SOFT_RANGE = MAX_HEIGHT_ABOVE_SEA - CAP_START
  }
}

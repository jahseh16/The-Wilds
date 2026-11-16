package com.example

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministic procedural island. Seen from the sea towards the centre it has four zones, each
 * driven by the distance to the (irregular) shoreline:
 *
 *  1. a long, gentle sea shelf that dives from the beach down to the sea floor,
 *  2. alternating coves (wide, gentle, walkable beaches up to about [BEACH_TOP_HEIGHT] metres) and
 *     cliff coasts (a short abrupt rise with craggy ridged noise),
 *  3. rolling hills with hummocks, ridged spurs, dry gullies and rocky terraced outcrops,
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

  /**
   * Cove factor along the shore: 1 = sheltered cove with a wide, gentle, walkable beach, 0 = cliff
   * coast (short abrupt rise out of deep water). Varies slowly with the angle around the island, so
   * the two alternate every few hundred metres, plus a ragged local component.
   */
  fun coveAt(x: Float, z: Float): Float {
    val a = atan2(z - centerZ, x - centerX)
    // Noise sampled on a circle is periodic in the angle: no seam at +-pi
    val ring = fbm(cos(a) * COVE_LOBES + 12.3f, sin(a) * COVE_LOBES - 4.1f, 2, 5)
    val local = fbm(x * 0.008f * freqScale + 3.3f, z * 0.008f * freqScale - 8.8f, 2, 6) * 0.5f
    return smoothstep(((ring + local) * 1.7f + 0.5f).coerceIn(0f, 1f))
  }

  /** 0..1 outcrop zones: rocky ground with terraced ledges, gravel and no trees. */
  fun rockZoneAt(x: Float, z: Float): Float {
    val n = 0.5f + 0.5f * fbm(x * 0.007f * freqScale + 71.3f, z * 0.007f * freqScale - 12.9f, 2, 8)
    return smoothstep(((n - 0.60f) / 0.14f).coerceIn(0f, 1f))
  }

  /** 0..1 along the thin dry-gully lines (ridged noise valleys). */
  private fun gullyAt(x: Float, z: Float): Float {
    val v = 1f - abs(fbm(x * GULLY_FREQUENCY * freqScale + 8.4f, z * GULLY_FREQUENCY * freqScale - 21.7f, 3, 7))
    return smoothstep(((v - 0.90f) / 0.085f).coerceIn(0f, 1f))
  }

  /** Terrain surface height (world Y) at (x, z). */
  fun heightAt(x: Float, z: Float): Float {
    val dx = x - centerX
    val dz = z - centerZ
    val dist = sqrt(dx * dx + dz * dz)

    // Slow warp of the distance gives bays and headlands instead of a perfect circle
    val coastWarp = fbm(x * COAST_FREQUENCY * freqScale + 91.7f, z * COAST_FREQUENCY * freqScale - 33.1f, 3, 0)
    // Metres inland from the shoreline circle (negative = out at sea)
    val inland = shoreRadius - (dist + coastWarp * radius * COAST_WARP_STRENGTH)

    // Coves have a long shelf and a wide beach; cliffs a short shelf and a beach that is already ~10 m up
    val cove = coveAt(x, z)
    val w = waterSpan * (0.45f + 0.55f * cove)
    val b = beachSpan * (0.20f + 0.80f * cove)
    val top = BEACH_TOP_HEIGHT + (1f - cove) * CLIFF_LIFT * relief.coerceAtLeast(0.4f)

    // Shelf + beach as ONE smoothstep from the sea floor to the top of the beach: no crease at the
    // waterline. In a cove that is a ~7 % slope (wide beach), on a cliff coast it is ~40 %
    val shelf = ((inland + w) / (w + b)).coerceIn(0f, 1f)
    var height = seaLevel - SEABED_DEPTH + (top + SEABED_DEPTH) * smoothstep(shelf)

    // Craggy ridged noise on the cliff sections, eased in from the waterline
    if (inland > 0f && cove < 0.95f) {
      val crag = 1f - abs(fbm(x * 0.055f * freqScale + 2.2f, z * 0.055f * freqScale + 9.1f, 3, 4))
      height += (1f - cove) * crag * crag * CRAG_AMPLITUDE * relief.coerceAtLeast(0.4f) * smoothstep((inland / 12f).coerceIn(0f, 1f))
    }

    if (inland < b) {
      // Sea floor: a little roughness, fading to nothing at the shoreline so the waterline is clean
      val seaFade = 1f - smoothstep(((inland + w * 0.4f) / (w * 0.4f)).coerceIn(0f, 1f))
      if (seaFade > 0f) {
        height += fbm(x * HILL_FREQUENCY * freqScale, z * HILL_FREQUENCY * freqScale, 2, 3) *
          SEABED_ROUGHNESS * seaFade
      }
      return height
    }

    // Interior fraction: 0 at the top of the beach, 1 at the centre of the island
    val f = ((inland - b) / (shoreRadius - b)).coerceIn(0f, 1f)
    height += RISE * relief * smoothstep(f)

    val hillMask = smoothstep((f / 0.35f).coerceIn(0f, 1f))
    height += fbm(x * HILL_FREQUENCY * freqScale + 5.2f, z * HILL_FREQUENCY * freqScale + 1.3f, 4, 1) *
      HILL_AMPLITUDE * relief * hillMask

    // Hummocks: small irregular bumps right behind the beach, so the ground is never a smooth ramp
    val nearMask = smoothstep(((inland - b) / 30f).coerceIn(0f, 1f))
    height += fbm(x * 0.09f * freqScale + 31.1f, z * 0.09f * freqScale - 5.6f, 2, 4) * HUMMOCK_AMPLITUDE * relief.coerceAtLeast(0.4f) * nearMask

    // Ridges and spurs: ridged noise crests that cut across the slopes
    val spurMask = smoothstep(((f - 0.05f) / 0.35f).coerceIn(0f, 1f))
    val spur = 1f - abs(fbm(x * 0.021f * freqScale + 14.7f, z * 0.021f * freqScale - 40.2f, 3, 6))
    height += spur * spur * spur * SPUR_AMPLITUDE * relief * spurMask

    val mountainMask = smoothstep(((f - 0.20f) / 0.50f).coerceIn(0f, 1f))
    if (mountainMask > 0f) {
      // Ridged noise: 1 - |n| folds the noise into crests, cubed to make them sharper than the valleys
      val ridge = 1f - abs(fbm(x * MOUNTAIN_FREQUENCY * freqScale - 40.7f, z * MOUNTAIN_FREQUENCY * freqScale + 17.9f, 5, 2))
      height += ridge * ridge * ridge * MOUNTAIN_AMPLITUDE * relief * mountainMask
    }

    // Gullies: narrow ravines carved down the slopes, never below the waterline
    val gully = gullyAt(x, z)
    if (gully > 0f) {
      val carve = GULLY_DEPTH * relief * gully * smoothstep(((f - 0.06f) / 0.30f).coerceIn(0f, 1f))
      height -= carve.coerceAtMost((height - seaLevel - 1.5f).coerceAtLeast(0f))
    }

    // Rocky outcrop zones: the ground steps into ledges (terraces) with short abrupt faces
    val rock = rockZoneAt(x, z) * smoothstep(((f - 0.05f) / 0.25f).coerceIn(0f, 1f))
    if (rock > 0f) {
      val t = (height - seaLevel) / TERRACE_STEP
      val fr = t - floor(t)
      val stepped = (floor(t) + smoothstep(((fr - 0.45f) / 0.45f).coerceIn(0f, 1f))) * TERRACE_STEP
      height += (seaLevel + stepped - height) * rock * 0.8f
    }

    return softCap(height)
  }

  /**
   * Ground-cover weights at (x, z), written to [out]: out[0] = forest (0..1: trees and forest floor),
   * out[1] = gravel (0..1: shingle shores, rocky ground, gully beds). Pure functions of the terrain
   * shape, so the mesher (vertex attribute for the shader) and the world populator (where trees and
   * rocks go) always agree. When [out] has 4 slots it also receives the height (2) and the slope, rise
   * over run (3).
   */
  fun biomeAt(x: Float, z: Float, out: FloatArray) {
    val h = heightAt(x, z)
    val sx = (heightAt(x + 1.5f, z) - h) / 1.5f
    val sz = (heightAt(x, z + 1.5f) - h) / 1.5f
    val slope = sqrt(sx * sx + sz * sz)
    val above = h - seaLevel

    val rz = rockZoneAt(x, z)
    val gully = if (above > 3f) gullyAt(x, z) else 0f
    val cove = coveAt(x, z)

    val heightBand = smoothstep(((above - 4f) / 3f).coerceIn(0f, 1f)) * (1f - smoothstep(((above - 26f) / 8f).coerceIn(0f, 1f)))
    val gentle = 1f - smoothstep(((slope - 0.36f) / 0.34f).coerceIn(0f, 1f))
    val patch = smoothstep((((0.5f + 0.5f * fbm(x * 0.011f * freqScale + 55.5f, z * 0.011f * freqScale - 17.7f, 3, 9)) - 0.36f) / 0.14f).coerceIn(0f, 1f))
    out[0] = heightBand * gentle * patch * (1f - rz) * (1f - gully)

    // Shingle: a strip just above the waterline (mostly on cliff coasts), rocky zones, gullies, steep scree
    val shore = smoothstep(((above + 0.6f) / 1.1f).coerceIn(0f, 1f)) * (1f - smoothstep(((above - 1.6f) / 1.6f).coerceIn(0f, 1f)))
    val scree = smoothstep(((slope - 0.40f) / 0.30f).coerceIn(0f, 1f)) * 0.45f
    out[1] = maxOf(shore * (0.30f + 0.70f * (1f - cove)), rz * 0.85f, gully * 0.9f, scree).coerceIn(0f, 1f)
    if (out.size >= 4) {
      out[2] = h
      out[3] = slope
    }
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
    private const val COVE_LOBES = 1.6f
    private const val CLIFF_LIFT = 7f
    private const val CRAG_AMPLITUDE = 4.5f
    private const val HUMMOCK_AMPLITUDE = 1.5f
    private const val SPUR_AMPLITUDE = 5f
    private const val GULLY_FREQUENCY = 1f / 65f
    private const val GULLY_DEPTH = 3.5f
    private const val TERRACE_STEP = 2.2f
    private const val RISE = 6f
    private const val HILL_AMPLITUDE = 7f
    private const val MOUNTAIN_AMPLITUDE = 31f
    private const val SEABED_ROUGHNESS = 0.7f
    private const val MIN_RELIEF = 0.15f

    private const val CAP_START = 44f
    private const val CAP_SOFT_RANGE = MAX_HEIGHT_ABOVE_SEA - CAP_START
  }
}

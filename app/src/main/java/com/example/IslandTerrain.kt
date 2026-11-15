package com.example

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deterministic procedural island: fBm value noise shaped by a radial falloff so that the land
 * rises from the sea floor, peaks near the centre and slides smoothly below [seaLevel] at the rim.
 *
 * Pure functions of (x, z) and the seed, so any chunk can be (re)generated independently and the
 * analytic height can also be sampled outside the loaded chunk grid.
 */
class IslandTerrain(
  private val seed: Int,
  val centerX: Float,
  val centerZ: Float,
  val radius: Float,
  val seaLevel: Float = 0f
) {

  /** Terrain surface height (world Y) at (x, z). */
  fun heightAt(x: Float, z: Float): Float {
    val dx = x - centerX
    val dz = z - centerZ
    val dist = sqrt(dx * dx + dz * dz)

    // Low-frequency warp of the radius gives an irregular coastline instead of a perfect circle
    val coastWarp = fbm(x * COAST_FREQUENCY + 91.7f, z * COAST_FREQUENCY - 33.1f, 3)
    val r = ((dist + coastWarp * radius * COAST_WARP_STRENGTH) / radius).coerceIn(0f, 1f)
    // Full land height out to FALLOFF_START * radius, then a smooth radial drop (1 - smoothstep)
    // that reaches the sea floor exactly at the island radius
    val falloff = ((r - FALLOFF_START) / (1f - FALLOFF_START)).coerceIn(0f, 1f)
    val mask = 1f - smoothstep(falloff)

    val n = fbm(x * TERRAIN_FREQUENCY, z * TERRAIN_FREQUENCY, TERRAIN_OCTAVES)
    val landHeight = seaLevel + LAND_BASE_HEIGHT + n * LAND_AMPLITUDE
    val seabedHeight = seaLevel - SEABED_DEPTH + n * SEABED_ROUGHNESS
    return seabedHeight + (landHeight - seabedHeight) * mask
  }

  private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

  /** Fractal Brownian motion of [octaves] value-noise layers, normalised to roughly [-1, 1]. */
  private fun fbm(x: Float, z: Float, octaves: Int): Float {
    var sum = 0f
    var amplitude = 1f
    var frequency = 1f
    var norm = 0f
    for (octave in 0 until octaves) {
      sum += valueNoise(x * frequency, z * frequency, octave) * amplitude
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

    private const val TERRAIN_FREQUENCY = 0.035f
    private const val TERRAIN_OCTAVES = 4
    private const val COAST_FREQUENCY = 0.02f
    private const val COAST_WARP_STRENGTH = 0.14f
    private const val FALLOFF_START = 0.55f // fraction of the radius where the land starts to drop
    private const val GAIN = 0.5f
    private const val LACUNARITY = 2.0f

    // Heights relative to sea level. Centre sits roughly 3..13 m above the sea, the sea floor 5 m below.
    private const val LAND_BASE_HEIGHT = 8f
    private const val LAND_AMPLITUDE = 7f
    private const val SEABED_DEPTH = 5f
    private const val SEABED_ROUGHNESS = 0.8f
  }
}

package com.example

import kotlin.math.exp

/**
 * Sky exposure ("skyOcclusion" in the shader): 1.0 = open sky above, 0.0 = buried under rock.
 *
 * It is a vertical column test: the amount of solid material between a point and the top of the
 * world absorbs sunlight exponentially (like fog, but made of earth). Fractional density counts
 * as fractional cover, so the value changes smoothly while the player digs and a tunnel gets
 * darker gradually from its mouth inwards instead of switching off at a hard line.
 *
 * Only the sky above is considered (no sideways light bleeding from a cave mouth); that keeps the
 * cost to one pass over each grid column and lets chunk meshing stay independent.
 */
object SkyExposure {

  /** Absorption per metre of solid cover; ~1 m of rock leaves 45 %, 3 m leaves 9 %. */
  const val ABSORPTION = 0.8f

  /**
   * Below this much cover the exposure is < 1 %, so the rest of the column is filled with 0
   * instead of sampling every deeper grid point (most of a column is solid ground).
   */
  const val COVER_CUTOFF = 6f

  fun exposureForCover(coverMetres: Float): Float = exp(-coverMetres * ABSORPTION)

  /**
   * Fills `out[offset + gy]` for gy in 0..[topIndex] with the exposure of grid point gy, sweeping
   * downwards from the sky. [densityAt] gives the density (> 0 solid) of grid level gy; the cover a
   * point sees is the solid *strictly above* it, so open ground is exactly 1.0.
   */
  inline fun fillColumn(
    out: FloatArray,
    offset: Int,
    topIndex: Int,
    voxelSize: Float,
    densityAt: (Int) -> Float
  ) {
    var cover = 0f
    var gy = topIndex
    while (gy >= 0) {
      if (cover >= COVER_CUTOFF) {
        while (gy >= 0) {
          out[offset + gy] = 0f
          gy--
        }
        return
      }
      out[offset + gy] = exposureForCover(cover)
      cover += densityAt(gy).coerceIn(0f, 1f) * voxelSize
      gy--
    }
  }
}

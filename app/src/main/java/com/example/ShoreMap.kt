package com.example

/**
 * Baked water-depth map of the original terrain, sampled by [WaterShader] for the shore foam, the
 * transparency ramp and to skip water over dry land.
 *
 * One byte per texel, row-major with row = Z (row 0 at [originZ]) and column = X. The value encodes
 * the depth of the water column at that point: `0.5` is exactly sea level, brighter is deeper,
 * darker is land above the sea. [DEPTH_RANGE] metres span the whole 0..1 range (+-6 m around sea
 * level), which is plenty for the 5 m sea floor and gives ~5 cm of precision.
 *
 * It describes the terrain as generated, not as dug: player excavation does not flood.
 */
object ShoreMap {

  /** Metres covered by the full 0..1 byte range (depth -6 .. +6). */
  const val DEPTH_RANGE = 12f

  fun encode(depthMetres: Float): Int =
    ((depthMetres / DEPTH_RANGE + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()

  fun decode(encoded: Int): Float = (encoded / 255f - 0.5f) * DEPTH_RANGE

  /**
   * Bakes [width] x [height] texels over the rectangle starting at ([originX], [originZ]) with the
   * given world size. Each texel samples the terrain at its centre. Returns values 0..255.
   */
  fun bake(
    width: Int,
    height: Int,
    originX: Float,
    originZ: Float,
    spanX: Float,
    spanZ: Float,
    seaLevel: Float,
    heightAt: (Float, Float) -> Float
  ): IntArray {
    val out = IntArray(width * height)
    for (row in 0 until height) {
      val z = originZ + (row + 0.5f) / height * spanZ
      for (col in 0 until width) {
        val x = originX + (col + 0.5f) / width * spanX
        out[row * width + col] = encode(seaLevel - heightAt(x, z))
      }
    }
    return out
  }
}

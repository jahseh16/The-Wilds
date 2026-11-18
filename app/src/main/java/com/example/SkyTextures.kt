package com.example

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The two small textures of the sky, generated at start-up (pure CPU code, unit tested):
 *
 *  - [cloudNoise]: a seamlessly tiling RGBA noise. Each channel is one octave of smooth value noise
 *    with a different period (R 4 cells, G 8, B 16, A 32 across the texture), so ONE texture read
 *    gives the sky shader three or four FBM octaves at once instead of ~60 ALU of hash noise per octave.
 *  - [moonMap]: the visible face of the moon. RGB = surface normal (tangent space of the disc: x
 *    right, y up, z towards the viewer) from a height field of craters (bowl + raised rim, power-law
 *    sizes) and A = albedo (dark basaltic "maria", bright highlands, bright young crater rims).
 *
 * Pixels are RGBA8888 ints (0xRRGGBBAA).
 */
object SkyTextures {

  fun cloudNoise(size: Int = 128, seed: Int = 7): IntArray {
    val periods = intArrayOf(4, 8, 16, 32)
    val ch = Array(4) { FloatArray(size * size) }
    for (c in 0 until 4) {
      val p = periods[c]
      for (y in 0 until size) for (x in 0 until size) {
        ch[c][y * size + x] = tiledValueNoise(x * p / size.toFloat(), y * p / size.toFloat(), p, seed + c * 101)
      }
      normalize(ch[c])
    }
    return IntArray(size * size) { i ->
      (byte(ch[0][i]) shl 24) or (byte(ch[1][i]) shl 16) or (byte(ch[2][i]) shl 8) or byte(ch[3][i])
    }
  }

  fun moonMap(size: Int = 128, seed: Int = 1969): IntArray {
    val rnd = Rnd(seed)
    val height = FloatArray(size * size)
    val albedo = FloatArray(size * size)
    // Maria: big, soft, dark low-frequency blobs (value noise thresholded)
    for (y in 0 until size) for (x in 0 until size) {
      val u = x / size.toFloat(); val v = y / size.toFloat()
      val m = tiledValueNoise(u * 3f + 0.37f, v * 3f + 0.81f, 64, seed + 5) * 0.65f +
        tiledValueNoise(u * 7f, v * 7f, 64, seed + 9) * 0.35f
      val maria = smooth((m - 0.50f) / 0.16f)
      val grain = tiledValueNoise(u * 40f, v * 40f, 64, seed + 13) * 0.5f + tiledValueNoise(u * 90f, v * 90f, 128, seed + 17) * 0.5f
      albedo[y * size + x] = 0.80f - 0.36f * maria + (grain - 0.5f) * 0.10f
      // maria are flat lava plains; highlands rougher
      height[y * size + x] = (grain - 0.5f) * 0.012f * (1f - 0.7f * maria) - maria * 0.01f
    }
    // Craters: many small, few big (radius in texels)
    val craters = 95
    for (k in 0 until craters) {
      val r = (1.6f + 14f * rnd.next() * rnd.next() * rnd.next()) * size / 128f
      val cx = rnd.next() * size; val cy = rnd.next() * size
      val depth = r * 0.055f / size * 32f
      val young = rnd.next() < 0.12f
      val x0 = max(0, (cx - r * 2.2f).toInt()); val x1 = min(size - 1, (cx + r * 2.2f).toInt())
      val y0 = max(0, (cy - r * 2.2f).toInt()); val y1 = min(size - 1, (cy + r * 2.2f).toInt())
      for (y in y0..y1) for (x in x0..x1) {
        val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / r
        val i = y * size + x
        if (d < 1f) height[i] -= depth * (1f - d * d)
        height[i] += depth * 0.35f * exp(-((d - 1f) / 0.22f) * ((d - 1f) / 0.22f))
        // fresh craters: bright rim and ejecta
        if (young) albedo[i] += 0.16f * exp(-((d - 1.05f) / 0.45f) * ((d - 1.05f) / 0.45f))
        else if (d < 1f) albedo[i] -= 0.03f * (1f - d)
      }
    }
    val out = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
      val i = y * size + x
      val hl = height[y * size + max(0, x - 1)]; val hr = height[y * size + min(size - 1, x + 1)]
      val hd = height[max(0, y - 1) * size + x]; val hu = height[min(size - 1, y + 1) * size + x]
      val k = 2.2f
      var nx = (hl - hr) * k * size / 8f; var ny = (hd - hu) * k * size / 8f; var nz = 1f
      val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
      // texture rows go downwards, the disc's y goes up
      out[i] = (byte(nx * 0.5f + 0.5f) shl 24) or (byte(-ny * 0.5f + 0.5f) shl 16) or (byte(nz * 0.5f + 0.5f) shl 8) or byte(albedo[i].coerceIn(0.15f, 1f))
    }
    return out
  }

  // --- helpers ------------------------------------------------------------------------------

  /** Smooth value noise on a lattice that wraps every [period] cells (so the texture tiles). */
  fun tiledValueNoise(x: Float, y: Float, period: Int, seed: Int): Float {
    val xi = floor(x).toInt(); val yi = floor(y).toInt()
    val fx = x - xi; val fy = y - yi
    val u = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f); val v = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
    fun h(ix: Int, iy: Int): Float {
      val a = Math.floorMod(ix, period); val b = Math.floorMod(iy, period)
      var s = a * 374761393 + b * 668265263 + seed * 1442695041
      s = (s xor (s ushr 13)) * 1274126177
      s = s xor (s ushr 16)
      return (s ushr 8) / 16777216f
    }
    val a = h(xi, yi) + u * (h(xi + 1, yi) - h(xi, yi))
    val b = h(xi, yi + 1) + u * (h(xi + 1, yi + 1) - h(xi, yi + 1))
    return a + v * (b - a)
  }

  private fun normalize(a: FloatArray) {
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
    for (v in a) { lo = min(lo, v); hi = max(hi, v) }
    val s = 1f / max(hi - lo, 1e-6f)
    for (i in a.indices) a[i] = (a[i] - lo) * s
  }

  private fun byte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

  private fun smooth(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

  private class Rnd(seed: Int) {
    private var s = seed * 0x2545F491 + 0x1234567
    fun next(): Float { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); return abs(s % 1_000_000) / 1_000_000f }
  }
}

package com.example

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Shape numbers of one pine variant (metres, unscaled; the placement scale multiplies all of them). */
class PineSpec(
  val height: Float,
  val baseRadius: Float,
  val crownBase: Float,
  val tiers: Int,
  val branchesPerTier: Int,
  val crownRadius: Float,
  val droop: Float,
  val seed: Int
) {
  /** Trunk radius at height [y] above the ground (linear taper from the stump to a point at the top). */
  fun radiusAt(y: Float): Float {
    if (y <= PineTreeGenerator.STUMP_TOP) return baseRadius * 1.04f
    val t = ((y - PineTreeGenerator.STUMP_TOP) / (height - PineTreeGenerator.STUMP_TOP)).coerceIn(0f, 1f)
    return baseRadius * (1f - t) + 0.03f * t
  }
}

/**
 * Procedural tall conifers in the style of The Forest: straight tapered trunk on a buried stump,
 * and a dense crown of 5-7 whorls of down-swept branches. Pure geometry (no GL), so it is unit
 * tested and the game builds everything at start-up in a few milliseconds.
 *
 * Each pine is split into three [PartMesh]es so felling works on the batched chunk meshes:
 *  - **stump** (bark layer): from 0.35 m below the ground to [STUMP_TOP], with a flared base so it
 *    never floats on a slope, and a cut face on top. Stays after the tree is felled.
 *  - **trunk** (bark layer, fellable): the straight cone from the stump to the tip, cut face below.
 *  - **crown** (cutout layer, fellable): branch "gables" (an inverted V of needle card along a
 *    drooping, 3-segment branch axis) rooted *inside* the trunk, so no needle card ever floats; a
 *    dark opaque core cone under every whorl fills the interior; a leader cone on top.
 *
 * Ambient occlusion is baked per vertex (dark near the trunk and in the lower whorls) and the wind
 * sway weight grows towards the branch tips and the top of the tree.
 *
 * UV conventions: bark UVs are in bark-texture space (repeat wrap); cut faces (stump top, trunk
 * bottom, log ends) carry u = [CUT_U] + local x, v = local z on the unit disc, which the shader turns
 * into procedural growth rings. Crown UVs land in the "needles" cell of the cutout atlas: branch
 * cards use the upper 3/4 of the cell, the core cones its fully opaque bottom band.
 */
object PineTreeGenerator {

  const val STUMP_TOP = 0.45f
  const val STUMP_BURY = 0.35f
  const val CUT_U = 5f

  val SPECS: Array<PineSpec> = arrayOf(
    PineSpec(height = 9.2f, baseRadius = 0.27f, crownBase = 2.8f, tiers = 5, branchesPerTier = 17, crownRadius = 2.3f, droop = 0.42f, seed = 11),
    PineSpec(height = 11.6f, baseRadius = 0.31f, crownBase = 3.6f, tiers = 6, branchesPerTier = 16, crownRadius = 2.7f, droop = 0.48f, seed = 23),
    PineSpec(height = 13.5f, baseRadius = 0.35f, crownBase = 4.6f, tiers = 7, branchesPerTier = 16, crownRadius = 3.0f, droop = 0.52f, seed = 37)
  )

  /** Placement scales keep every variant between 8 and 14 m tall. */
  const val MIN_SCALE = 0.90f
  const val MAX_SCALE = 1.03f

  enum class Piece { STUMP, TRUNK, CROWN }

  /** The three parts of [variant] at full detail or as the far-away stand-in. */
  fun parts(variant: Int, lod: Boolean): Map<Piece, PartMesh> {
    val spec = SPECS[variant]
    val sides = if (lod) 5 else 7
    return mapOf(
      Piece.STUMP to stump(spec, sides),
      Piece.TRUNK to trunk(spec, sides, if (lod) 2 else 4),
      Piece.CROWN to crown(spec, lod)
    )
  }

  // --- Bark -------------------------------------------------------------------------------------

  private fun stump(spec: PineSpec, sides: Int): PartMesh {
    val g = Geo()
    val r = spec.baseRadius
    // root flare: wide under the ground, nearly trunk-wide at the cut
    val rings = floatArrayOf(-STUMP_BURY, 0.06f, STUMP_TOP)
    val radii = floatArrayOf(r * 1.38f, r * 1.13f, r * 1.04f)
    tube(g, spec, sides, rings, radii, aoBottom = 0.55f, aoTop = 0.85f)
    cap(g, sides, STUMP_TOP, radii[2], up = true)
    return g.part(Layer.BARK, wind = 0f, fellable = false)
  }

  private fun trunk(spec: PineSpec, sides: Int, segments: Int): PartMesh {
    val g = Geo()
    val ys = FloatArray(segments + 1) { STUMP_TOP + (spec.height - STUMP_TOP) * (it.toFloat() / segments).pow(0.85f) }
    val radii = FloatArray(segments + 1) { spec.radiusAt(ys[it]).coerceAtLeast(0.03f) }
    ys[segments] = spec.height
    tube(g, spec, sides, ys, radii, aoBottom = 0.85f, aoTop = 0.6f)
    cap(g, sides, STUMP_TOP, radii[0], up = false)
    return g.part(Layer.BARK, wind = 0f, fellable = true)
  }

  /** Stack of rings along +Y; bark UVs repeat twice around and keep square texels up the trunk. */
  private fun tube(g: Geo, spec: PineSpec, sides: Int, ys: FloatArray, radii: FloatArray, aoBottom: Float, aoTop: Float) {
    val vScale = 1f / (PI.toFloat() * spec.baseRadius * 1.6f)
    val start = g.count
    for ((ri, y) in ys.withIndex()) {
      val r = radii[ri]
      val slope = if (ri + 1 < ys.size) (radii[ri] - radii[ri + 1]) / (ys[ri + 1] - y) else (radii[ri - 1] - radii[ri]) / (y - ys[ri - 1])
      val ao = aoBottom + (aoTop - aoBottom) * ri / (ys.size - 1f)
      for (s in 0..sides) {
        val a = 2f * PI.toFloat() * s / sides
        val nx = cos(a); val nz = sin(a)
        val ny = slope
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        g.vertex(nx * r, y, nz * r, nx / l, ny / l, nz / l, 2f * s / sides, y * vScale, ao, 0f)
      }
    }
    val row = sides + 1
    for (ri in 0 until ys.size - 1) for (s in 0 until sides) {
      val a = start + ri * row + s; val b = a + 1; val c = a + row; val d = c + 1
      g.tri(a, c, b); g.tri(b, c, d)
    }
  }

  /** Flat cut face (procedural rings in the shader) facing up or down. */
  private fun cap(g: Geo, sides: Int, y: Float, r: Float, up: Boolean) {
    val ny = if (up) 1f else -1f
    val c = g.vertex(0f, y, 0f, 0f, ny, 0f, CUT_U, 0f, 0.9f, 0f)
    for (s in 0..sides) {
      val a = 2f * PI.toFloat() * s / sides
      g.vertex(cos(a) * r, y, sin(a) * r, 0f, ny, 0f, CUT_U + cos(a), sin(a), 0.9f, 0f)
    }
    for (s in 0 until sides) if (up) g.tri(c, c + 2 + s, c + 1 + s) else g.tri(c, c + 1 + s, c + 2 + s)
  }

  // --- Crown ------------------------------------------------------------------------------------

  private fun crown(spec: PineSpec, lod: Boolean): PartMesh {
    val g = Geo()
    val cell = AtlasLayout.CUTOUT.getValue("needles")
    val rnd = Rand(spec.seed)
    val top = spec.height * 0.95f
    for (i in 0 until spec.tiers) {
      val t = i / (spec.tiers - 1f)
      val y = spec.crownBase + (top - spec.crownBase) * t.pow(0.92f)
      val radius = spec.crownRadius * (1f - t).pow(0.85f) * (0.92f + 0.16f * rnd.next()) + 0.45f
      val tierAo = 0.55f + 0.45f * t
      val count = if (lod) 7 else max(6, (spec.branchesPerTier * (1f - 0.40f * t)).toInt())
      val offset = rnd.next() * 6.283f
      // Core: an opaque, dark cone under the whorl so the crown never looks hollow
      core(g, spec, cell, y + 0.35f, radius * 0.58f, y - spec.droop * radius * 0.55f, if (lod) 5 else 6, tierAo * 0.45f)
      for (j in 0 until count) {
        val a = offset + 6.283f * j / count + (rnd.next() - 0.5f) * (3.0f / count)
        val len = radius * (0.85f + 0.25f * rnd.next())
        val droop = spec.droop * (0.85f + 0.3f * rnd.next())
        // lower whorls have longer, droopier and slightly lower-hanging branches
        val yRoot = y + 0.18f + (rnd.next() - 0.5f) * 0.25f
        branch(g, spec, cell, a, yRoot, len, droop, width = 0.55f * len.coerceAtMost(2.4f) + 0.35f, segments = if (lod) 1 else 3, tierAo = tierAo)
      }
    }
    // Leader: a slim needle cone at the very top
    core(g, spec, cell, spec.height + 0.05f, 0.42f, spec.height * 0.86f, 5, 0.9f)
    return g.part(Layer.CUTOUT, wind = 0.11f, fellable = true)
  }

  /**
   * One branch: an inverted-V card (two halves tilted 32 deg down from the axis) along a drooping axis
   * from inside the trunk to the tip. u runs root -> tip, v across the card (0 / 1 the two edges, 0.5 the axis).
   */
  private fun branch(
    g: Geo, spec: PineSpec, cell: AtlasLayout.Cell, angle: Float, yRoot: Float, length: Float,
    droop: Float, width: Float, segments: Int, tierAo: Float
  ) {
    val dx = cos(angle); val dz = sin(angle)
    // side vector (horizontal, perpendicular to the branch)
    val sx = -dz; val sz = dx
    val tilt = 0.56f // ~32 degrees
    val ct = cos(tilt); val st = sin(tilt)
    val rootR = spec.radiusAt(yRoot) * 0.5f // rooted inside the trunk: never floating
    val start = g.count
    for (k in 0..segments) {
      val f = k.toFloat() / segments
      val reach = rootR + (length - rootR) * f
      // arching droop: almost level near the trunk, falling off towards the tip
      val y = yRoot - droop * length * (0.15f * f + 0.85f * f * f)
      val ax = dx * reach; val az = dz * reach
      val w = 0.5f * width * when { f < 0.5f -> 0.45f + 1.1f * f; else -> 1f - 0.9f * (f - 0.5f) }
      val ao = (tierAo * (0.22f + 0.78f * f)).coerceIn(0.12f, 1f)
      val sway = f * (0.65f + 0.35f * (yRoot / spec.height))
      // outward / upward leaning normal: soft, volumetric shading on alpha cards
      val nxo = dx * 0.55f; val nzo = dz * 0.55f
      val u = cellU(cell, 0.02f + 0.96f * f)
      for (side in 0..2) {
        val sgn = (side - 1).toFloat() // -1, 0, +1
        val px = ax + sx * w * ct * sgn
        val pz = az + sz * w * ct * sgn
        val py = y - w * st * abs(sgn)
        val nx = nxo + sx * sgn * 0.25f; val ny = 0.8f; val nz = nzo + sz * sgn * 0.25f
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        g.vertex(px, py, pz, nx / l, ny / l, nz / l, u, cellV(cell, 0.04f + 0.33f * (sgn + 1f)), ao, sway)
      }
    }
    for (k in 0 until segments) {
      val a = start + k * 3; val b = a + 3
      g.tri(a, b, a + 1); g.tri(a + 1, b, b + 1)
      g.tri(a + 1, b + 1, a + 2); g.tri(a + 2, b + 1, b + 2)
    }
  }

  /** Opaque cone (apex up) textured with the dense needle mat band of the cell. */
  private fun core(g: Geo, spec: PineSpec, cell: AtlasLayout.Cell, apexY: Float, radius: Float, baseY: Float, sides: Int, ao: Float) {
    val apex = g.count
    for (s in 0..sides) {
      val a = 2f * PI.toFloat() * s / sides
      val u = cellU(cell, 0.05f + 0.9f * s / sides)
      g.vertex(0f, apexY, 0f, cos(a) * 0.3f, 0.95f, sin(a) * 0.3f, u, cellV(cell, 0.80f), ao * 0.8f, 0.15f * apexY / spec.height)
    }
    val rim = g.count
    for (s in 0..sides) {
      val a = 2f * PI.toFloat() * s / sides
      val n = sqrt(0.6f * 0.6f + 0.8f * 0.8f)
      g.vertex(cos(a) * radius, baseY, sin(a) * radius, cos(a) * 0.8f / n, 0.6f / n, sin(a) * 0.8f / n,
        cellU(cell, 0.05f + 0.9f * s / sides), cellV(cell, 0.97f), ao, 0.35f)
    }
    for (s in 0 until sides) g.tri(apex + s, rim + s + 1, rim + s)
  }

  private fun cellU(cell: AtlasLayout.Cell, u: Float) = (cell.x + u * cell.size) / AtlasLayout.SIZE
  private fun cellV(cell: AtlasLayout.Cell, v: Float) = (cell.y + v * cell.size) / AtlasLayout.SIZE

  // --- Logs ---------------------------------------------------------------------------------

  /** A felled log along local +Y, centred on the origin, bark sides and two cut faces. */
  fun log(length: Float, radius: Float): PartMesh {
    val g = Geo()
    val spec = PineSpec(length, radius, 0f, 1, 1, 1f, 0f, 1)
    val h = length * 0.5f
    tube(g, spec, 7, floatArrayOf(-h, h), floatArrayOf(radius, radius * 0.94f), aoBottom = 0.9f, aoTop = 0.9f)
    cap(g, 7, h, radius * 0.94f, up = true)
    cap(g, 7, -h, radius, up = false)
    return g.part(Layer.BARK, wind = 0f, fellable = false)
  }

  // --- Needle texture -----------------------------------------------------------------------

  /**
   * The needle texture of the crown, RGBA8888 (0xRRGGBBAA) [size] x [size], drawn procedurally:
   * the upper 3/4 is a pine twig seen from above (a brown stem along u with dense, dark-green needles
   * angled forward, alpha-cut at the edges and tapering at the tip), the bottom band an opaque needle
   * mat for the crown cores. Transparent texels carry the colour of the needles (colour bleeding), so
   * mipmaps never fringe in black.
   */
  fun needleTexture(size: Int): IntArray {
    val out = IntArray(size * size)
    val rnd = Rand(4242)
    val cardH = (size * 0.74f).toInt()
    val r = FloatArray(size * size); val gg = FloatArray(size * size); val b = FloatArray(size * size); val a = FloatArray(size * size)
    // base: dark needle green everywhere (bleed colour)
    for (i in 0 until size * size) { r[i] = 0.10f; gg[i] = 0.17f; b[i] = 0.08f; a[i] = 0f }
    fun plot(x: Int, y: Int, cr: Float, cg: Float, cb: Float, alpha: Float) {
      if (x < 0 || y < 0 || x >= size || y >= size) return
      val i = y * size + x
      r[i] = cr; gg[i] = cg; b[i] = cb; a[i] = max(a[i], alpha)
    }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, cr: Float, cg: Float, cb: Float, thick: Float) {
      val steps = (max(abs(x1 - x0), abs(y1 - y0)) * 1.5f).toInt() + 1
      for (s in 0..steps) {
        val f = s.toFloat() / steps
        val x = x0 + (x1 - x0) * f; val y = y0 + (y1 - y0) * f
        val shade = 0.85f + 0.3f * f
        val t = thick.toInt().coerceAtLeast(1)
        for (oy in 0 until t) plot(x.toInt(), (y + oy).toInt(), cr * shade, cg * shade, cb * shade, 1f)
      }
    }
    val mid = cardH * 0.5f
    val thick = size / 512f
    // A fir branch seen from above: a main twig along u, secondary twigs swept forward on both sides
    // (shorter towards the tip), every twig clothed in short dark needles. Clumpy, not a fern frond.
    fun needles(x0: Float, y0: Float, dirX: Float, dirY: Float, len: Float, density: Float) {
      val steps = (len / (2.2f * thick)).toInt().coerceAtLeast(2)
      for (k in 0 until steps) {
        val f = k.toFloat() / steps
        val px = x0 + dirX * len * f; val py = y0 + dirY * len * f
        val nl = (len * 0.16f + 6f * thick) * (1f - 0.5f * f) * (0.7f + 0.6f * rnd.next())
        for (side in 0..1) {
          if (rnd.next() > density) continue
          val sgn = if (side == 0) 1f else -1f
          // needles leave at ~50-70 degrees, angled towards the twig tip
          val a = (0.9f + 0.35f * rnd.next()) * sgn
          val ca = cos(a); val sa = sin(a)
          val nx = dirX * ca - dirY * sa; val ny = dirX * sa + dirY * ca
          val tone = 0.65f + 0.55f * rnd.next()
          line(px, py, px + nx * nl, py + ny * nl, 0.085f * tone, 0.16f * tone, 0.075f * tone, thick * 1.6f + 0.6f)
        }
      }
    }
    val tipX = size * 0.96f
    var u = size * 0.03f
    var side = 1f
    while (u < tipX * 0.93f) {
      val f = u / tipX
      val reach = mid * 0.92f * (1f - 0.65f * f * f) * (0.75f + 0.25f * rnd.next())
      val ang = 0.70f + 0.30f * rnd.next() // forward sweep
      val dx = cos(ang); val dy = sin(ang) * side
      needles(u, mid, dx, dy, reach / sin(ang), 0.92f)
      line(u, mid, u + dx * reach / sin(ang) * 0.85f, mid + dy * reach / sin(ang) * 0.85f, 0.24f, 0.18f, 0.11f, thick * 2.2f)
      u += size * (0.035f + 0.03f * rnd.next())
      side = -side
    }
    // main twig with its own needles, and a dense tip tuft
    needles(0f, mid, 1f, 0f, tipX, 0.95f)
    line(0f, mid - thick * 2f, tipX, mid, 0.30f, 0.21f, 0.13f, thick * 4.5f)
    // opaque mat band
    for (y in (size * 0.76f).toInt() until size) for (x in 0 until size) {
      val n = rnd.next()
      plot(x, y, 0.07f + 0.05f * n, 0.12f + 0.07f * n, 0.06f + 0.03f * n, 1f)
    }
    for (i in 0 until size * size) {
      val ri = (r[i].coerceIn(0f, 1f) * 255f).toInt(); val gi = (gg[i].coerceIn(0f, 1f) * 255f).toInt()
      val bi = (b[i].coerceIn(0f, 1f) * 255f).toInt(); val ai = (a[i] * 255f).toInt()
      out[i] = (ri shl 24) or (gi shl 16) or (bi shl 8) or ai
    }
    return out
  }

  // --- helpers -----------------------------------------------------------------------------

  private class Rand(seed: Int) {
    private var s = seed * 0x9E3779B1.toInt() + 0x632BE5AB
    fun next(): Float {
      s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
      return (s ushr 8) / 16777216f
    }
  }

  /** Growable vertex / index buffers in the [PartMesh] layout (+ AO and sway weight per vertex). */
  private class Geo {
    val v = ArrayList<Float>(); val ao = ArrayList<Float>(); val sway = ArrayList<Float>(); val hf = ArrayList<Float>()
    val idx = ArrayList<Int>()
    var count = 0
    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, vv: Float, a: Float, w: Float): Int {
      v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(u); v.add(vv)
      ao.add(a); sway.add(w); hf.add(0f)
      return count++
    }
    fun tri(a: Int, b: Int, c: Int) { idx.add(a); idx.add(b); idx.add(c) }
    fun part(layer: Layer, wind: Float, fellable: Boolean): PartMesh {
      var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
      for (i in 0 until count) { minY = min(minY, v[i * 8 + 1]); maxY = max(maxY, v[i * 8 + 1]) }
      val h = max(maxY - minY, 1e-3f)
      return PartMesh(
        layer, wind, count, v.toFloatArray(), FloatArray(count) { (v[it * 8 + 1] - minY) / h }, idx.toIntArray(),
        ao = ao.toFloatArray(), swayWeight = sway.toFloatArray(), fellable = fellable
      )
    }
  }
}

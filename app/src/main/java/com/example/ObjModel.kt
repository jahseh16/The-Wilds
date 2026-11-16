package com.example

/**
 * Minimal Wavefront OBJ reader for the static props, foliage cards and trees in `assets/models/`
 * (triangles with `v/vt/vn` indices, `usemtl` groups). It keeps the geometry on the CPU as plain
 * arrays because [ChunkScatterMesh] merges many instances of it into one mesh per chunk.
 *
 * Pure Kotlin (no GL, no libGDX), so it is unit-testable and cheap to load.
 *
 * UVs are converted to image space (v down, like a Pixmap), so atlas remapping is a plain affine map.
 */
class ObjModel(
  /** x, y, z per vertex. */
  val positions: FloatArray,
  /** nx, ny, nz per vertex. */
  val normals: FloatArray,
  /** u, v per vertex, v measured from the TOP of the image. */
  val uvs: FloatArray,
  /** Triangle indices of each `usemtl` group, in file order. */
  val groups: Map<String, IntArray>
) {
  val vertexCount: Int get() = positions.size / 3
  val triangleCount: Int get() = groups.values.sumOf { it.size } / 3

  val minX: Float; val maxX: Float
  val minY: Float; val maxY: Float
  val minZ: Float; val maxZ: Float

  init {
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
    var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
    for (i in 0 until vertexCount) {
      val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
      if (x < x0) x0 = x; if (x > x1) x1 = x
      if (y < y0) y0 = y; if (y > y1) y1 = y
      if (z < z0) z0 = z; if (z > z1) z1 = z
    }
    minX = x0; maxX = x1; minY = y0; maxY = y1; minZ = z0; maxZ = z1
  }

  val height: Float get() = maxY - minY

  /** Largest horizontal distance from the origin (the models are centred on it) of any vertex below [maxHeight]. */
  fun baseRadius(maxHeight: Float): Float {
    var r2 = 0f
    for (i in 0 until vertexCount) {
      if (positions[i * 3 + 1] - minY > maxHeight) continue
      val x = positions[i * 3]; val z = positions[i * 3 + 2]
      val d = x * x + z * z
      if (d > r2) r2 = d
    }
    return kotlin.math.sqrt(r2)
  }

  companion object {
    fun parse(text: String): ObjModel {
      val v = ArrayList<FloatArray>(); val vt = ArrayList<FloatArray>(); val vn = ArrayList<FloatArray>()
      val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>()
      val lookup = HashMap<Long, Int>()
      val groups = LinkedHashMap<String, ArrayList<Int>>()
      var current = groups.getOrPut("default") { ArrayList() }

      for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#') continue
        val t = line.split(' ').filter { it.isNotEmpty() }
        when (t[0]) {
          "v" -> v.add(floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()))
          "vt" -> vt.add(floatArrayOf(t[1].toFloat(), t[2].toFloat()))
          "vn" -> vn.add(floatArrayOf(t[1].toFloat(), t[2].toFloat(), t[3].toFloat()))
          "usemtl" -> current = groups.getOrPut(t[1]) { ArrayList() }
          "f" -> {
            val corners = IntArray(t.size - 1)
            for (c in 1 until t.size) {
              val p = t[c].split('/')
              val vi = resolve(p[0].toInt(), v.size)
              val ti = if (p.size > 1 && p[1].isNotEmpty()) resolve(p[1].toInt(), vt.size) else -1
              val ni = if (p.size > 2 && p[2].isNotEmpty()) resolve(p[2].toInt(), vn.size) else -1
              val key = (vi.toLong() shl 42) or ((ti + 1).toLong() shl 21) or (ni + 1).toLong()
              corners[c - 1] = lookup.getOrPut(key) {
                val s = v[vi]
                pos.add(s[0]); pos.add(s[1]); pos.add(s[2])
                if (ni >= 0) { val n = vn[ni]; nor.add(n[0]); nor.add(n[1]); nor.add(n[2]) } else { nor.add(0f); nor.add(1f); nor.add(0f) }
                if (ti >= 0) { val u = vt[ti]; uv.add(u[0]); uv.add(1f - u[1]) } else { uv.add(0f); uv.add(0f) }
                pos.size / 3 - 1
              }
            }
            // Fan triangulation (the assets are triangles; quads would also work)
            for (k in 1 until corners.size - 1) {
              current.add(corners[0]); current.add(corners[k]); current.add(corners[k + 1])
            }
          }
        }
      }
      return ObjModel(
        pos.toFloatArray(), nor.toFloatArray(), uv.toFloatArray(),
        groups.filterValues { it.isNotEmpty() }.mapValues { it.value.toIntArray() }
      )
    }

    private fun resolve(index: Int, size: Int): Int = if (index > 0) index - 1 else size + index
  }
}

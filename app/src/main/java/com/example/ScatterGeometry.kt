package com.example

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where each texture lives inside the two runtime atlases. Merging the textures lets the whole
 * ecosystem of a chunk be drawn with just two draw calls (one per [Layer]) instead of one per object.
 */
object AtlasLayout {
  class Cell(val x: Int, val y: Int, val size: Int, val file: String)

  const val SIZE = 1024

  /** Opaque things: tree bark, boulders, pebbles, sticks (512 px cells). */
  val SOLID: Map<String, Cell> = mapOf(
    "bark" to Cell(0, 0, 512, "models/trees/tree_01_bark_diff.jpg"),
    "rock" to Cell(512, 0, 512, "models/props/rock_boulder_01_diff.jpg"),
    "pebbles" to Cell(0, 512, 512, "models/props/pebbles_diff.jpg"),
    "sticks" to Cell(512, 512, 512, "models/props/sticks_diff.jpg")
  )

  /** Alpha-tested leaves and foliage cards: the tree leaves in 512 px, the small cards in 256 px. */
  val CUTOUT: Map<String, Cell> = mapOf(
    "leaves" to Cell(0, 0, 512, "models/trees/tree_01_leaves_diff.png"),
    "fern" to Cell(512, 0, 256, "models/foliage/fern_01.png"),
    "shrub" to Cell(768, 0, 256, "models/foliage/shrub_01.png"),
    "grass_01" to Cell(512, 256, 256, "models/foliage/grass_01.png"),
    "grass_02" to Cell(768, 256, 256, "models/foliage/grass_02.png"),
    "grass_03" to Cell(0, 512, 256, "models/foliage/grass_03.png"),
    "dandelion" to Cell(256, 512, 256, "models/foliage/dandelion_01.png"),
    "weed" to Cell(512, 512, 256, "models/foliage/weed_01.png")
  )

  fun cells(layer: Layer): Map<String, Cell> = if (layer == Layer.SOLID) SOLID else CUTOUT
}

/** One material group of one model, ready to be instanced: atlas UVs, height fractions, triangle indices. */
class PartMesh(
  val layer: Layer,
  val wind: Float,
  val vertexCount: Int,
  /** x, y, z, nx, ny, nz, u, v per vertex (u, v already in atlas space). */
  val data: FloatArray,
  /** 0 at the base of the model, 1 at its top (drives the wind sway). */
  val heightFraction: FloatArray,
  val indices: IntArray
)

/**
 * The CPU copy of every placeable model, split into [PartMesh]es with atlas-remapped UVs, plus the
 * numbers the collision needs. [read] returns the text of an asset path, so the same code loads from
 * the Android assets in the game and from plain files in tests.
 */
class ScatterModels(read: (String) -> String) {

  private val parts = HashMap<Kind, List<PartMesh>>()

  /** Where the tree trunk meets the ground, relative to the model origin, and its radius there (metres, unscaled). */
  val trunkCenterX: Float
  val trunkCenterZ: Float
  val trunkRadius: Float

  /** Boulder footprint radius and height (metres, unscaled). */
  val rockRadius: Float
  val rockHeight: Float

  /** Largest triangle count of one placed model, for budgeting. */
  val triangleCounts = HashMap<Kind, Int>()

  init {
    for (kind in Kind.ALL) {
      val model = ObjModel.parse(read(kind.file))
      triangleCounts[kind] = model.triangleCount
      parts[kind] = kind.parts.map { buildPart(model, it) }
    }
    val tree = ObjModel.parse(read(Kind.TREE.file))
    var sx = 0f; var sz = 0f; var n = 0
    for (i in 0 until tree.vertexCount) {
      if (tree.positions[i * 3 + 1] - tree.minY < TRUNK_BASE_HEIGHT) { sx += tree.positions[i * 3]; sz += tree.positions[i * 3 + 2]; n++ }
    }
    trunkCenterX = if (n > 0) sx / n else 0f
    trunkCenterZ = if (n > 0) sz / n else 0f
    var r2 = 0f
    for (i in 0 until tree.vertexCount) {
      if (tree.positions[i * 3 + 1] - tree.minY < TRUNK_BASE_HEIGHT) {
        val dx = tree.positions[i * 3] - trunkCenterX; val dz = tree.positions[i * 3 + 2] - trunkCenterZ
        if (dx * dx + dz * dz > r2) r2 = dx * dx + dz * dz
      }
    }
    trunkRadius = sqrt(r2)
    val rock = ObjModel.parse(read(Kind.ROCK.file))
    rockRadius = 0.25f * ((rock.maxX - rock.minX) + (rock.maxZ - rock.minZ)) * 0.9f
    rockHeight = rock.height
  }

  fun partsOf(kind: Kind): List<PartMesh> = parts.getValue(kind)

  private fun buildPart(model: ObjModel, part: Part): PartMesh {
    val tris = model.groups[part.group] ?: error("model has no group '${part.group}'")
    val cell = AtlasLayout.cells(part.layer).getValue(part.cell)
    val remap = HashMap<Int, Int>()
    val used = ArrayList<Int>()
    val idx = IntArray(tris.size)
    for (i in tris.indices) {
      idx[i] = remap.getOrPut(tris[i]) { used.add(tris[i]); used.size - 1 }
    }
    val data = FloatArray(used.size * 8)
    val frac = FloatArray(used.size)
    val h = maxOf(model.height, 0.001f)
    val inset = 0.5f / cell.size
    for ((k, v) in used.withIndex()) {
      data[k * 8] = model.positions[v * 3]; data[k * 8 + 1] = model.positions[v * 3 + 1]; data[k * 8 + 2] = model.positions[v * 3 + 2]
      data[k * 8 + 3] = model.normals[v * 3]; data[k * 8 + 4] = model.normals[v * 3 + 1]; data[k * 8 + 5] = model.normals[v * 3 + 2]
      val u = model.uvs[v * 2].coerceIn(inset, 1f - inset)
      val vv = model.uvs[v * 2 + 1].coerceIn(inset, 1f - inset)
      data[k * 8 + 6] = (cell.x + u * cell.size) / AtlasLayout.SIZE
      data[k * 8 + 7] = (cell.y + vv * cell.size) / AtlasLayout.SIZE
      frac[k] = ((model.positions[v * 3 + 1] - model.minY) / h).coerceIn(0f, 1f)
    }
    return PartMesh(part.layer, part.wind, used.size, data, frac, idx)
  }

  private companion object {
    const val TRUNK_BASE_HEIGHT = 0.4f
  }
}

/** Merged geometry of one layer of one chunk. */
class LayerData(
  val vertices: FloatArray, val vertexCount: Int,
  val indices: ShortArray, val indexCount: Int,
  val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float
)

/**
 * Merges many placements into one vertex/index array per [Layer] (static batching), so a chunk's
 * entire ecosystem costs two draw calls. Vertex layout: position(3) normal(3) uv(2) sway(1) near(1).
 * [sway] is the wind amplitude in metres at that vertex (the shader multiplies it by a travelling
 * wave); [near] is 1 for the small ground cover that fades out with distance.
 */
object ChunkScatterMesh {
  const val FLOATS = 10
  const val MAX_VERTICES = 60000

  private class Builder {
    var v = FloatArray(4096 * FLOATS); var vc = 0
    var i = ShortArray(8192); var ic = 0
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
    var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
    fun ensure(addVerts: Int, addIdx: Int) {
      if ((vc + addVerts) * FLOATS > v.size) v = v.copyOf(maxOf(v.size * 2, (vc + addVerts) * FLOATS))
      if (ic + addIdx > i.size) i = i.copyOf(maxOf(i.size * 2, ic + addIdx))
    }
    fun finish(): LayerData? = if (ic == 0) null else LayerData(v, vc, i, ic, x0, y0, z0, x1, y1, z1)
  }

  /** [lod] swaps trees and boulders for their low-triangle stand-ins (chunks far from the player). */
  fun build(placements: List<Placement>, models: ScatterModels, lod: Boolean = false): Pair<LayerData?, LayerData?> {
    val solid = Builder(); val cutout = Builder()
    for (p in placements) {
      val cy = cos(p.yaw); val sy = sin(p.yaw)
      for (part in models.partsOf(if (lod) p.kind.lodStandIn else p.kind)) {
        val b = if (part.layer == Layer.SOLID) solid else cutout
        if (b.vc + part.vertexCount > MAX_VERTICES) continue
        b.ensure(part.vertexCount, part.indices.size)
        val base = b.vc
        val sink = if (p.kind == Kind.ROCK) 0.12f * models.rockHeight * p.scale else 0f
        for (k in 0 until part.vertexCount) {
          val o = k * 8
          val lx = part.data[o] * p.scale; val ly = part.data[o + 1] * p.scale; val lz = part.data[o + 2] * p.scale
          val wx = p.x + cy * lx + sy * lz
          val wz = p.z - sy * lx + cy * lz
          val wy = p.y + ly - sink
          val nx = part.data[o + 3]; val ny = part.data[o + 4]; val nz = part.data[o + 5]
          val w = (b.vc) * FLOATS
          b.v[w] = wx; b.v[w + 1] = wy; b.v[w + 2] = wz
          b.v[w + 3] = cy * nx + sy * nz; b.v[w + 4] = ny; b.v[w + 5] = -sy * nx + cy * nz
          b.v[w + 6] = part.data[o + 6]; b.v[w + 7] = part.data[o + 7]
          val f = part.heightFraction[k]
          b.v[w + 8] = part.wind * p.scale * f * sqrt(f)
          b.v[w + 9] = if (p.kind.near) 1f else 0f
          if (wx < b.x0) b.x0 = wx; if (wx > b.x1) b.x1 = wx
          if (wy < b.y0) b.y0 = wy; if (wy > b.y1) b.y1 = wy
          if (wz < b.z0) b.z0 = wz; if (wz > b.z1) b.z1 = wz
          b.vc++
        }
        for (k in part.indices.indices) b.i[b.ic++] = (base + part.indices[k]).toShort()
      }
    }
    return Pair(solid.finish(), cutout.finish())
  }
}

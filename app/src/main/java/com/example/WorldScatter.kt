package com.example

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Which batched mesh (and texture atlas) a part of a model is drawn with. */
enum class Layer { SOLID, CUTOUT }

/** One material group of an OBJ model: its atlas cell and how much wind sways its tip (metres, unscaled). */
class Part(val group: String, val layer: Layer, val cell: String, val wind: Float)

/**
 * Everything the world populator can place. [near] kinds are the small ground cover and litter that
 * are only generated close to the player (and fade out with distance); the rest is seeded for the
 * whole streamed area.
 */
enum class Kind(val file: String, val near: Boolean, vararg val parts: Part) {
  TREE("models/trees/tree_01.obj", false, Part("bark", Layer.SOLID, "bark", 0f), Part("leaves", Layer.CUTOUT, "leaves", 0.12f)),
  /** ~530-triangle stand-in for [TREE] used by chunks far from the player. */
  TREE_LOD("models/trees/tree_01_lod1.obj", false, Part("bark", Layer.SOLID, "bark", 0f), Part("leaves", Layer.CUTOUT, "leaves", 0.12f)),
  SHRUB("models/foliage/shrub_01.obj", false, Part("card", Layer.CUTOUT, "shrub", 0.05f)),
  ROCK("models/props/rock_boulder_01.obj", false, Part("rock", Layer.SOLID, "rock", 0f)),
  /** 300-triangle stand-in for [ROCK] used by chunks far from the player. */
  ROCK_LOD("models/props/rock_boulder_01_lod1.obj", false, Part("rock", Layer.SOLID, "rock", 0f)),
  PEBBLE_PATCH("models/props/pebble_patch_01.obj", true, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_1("models/props/pebble_01.obj", true, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_2("models/props/pebble_02.obj", true, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_3("models/props/pebble_03.obj", true, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  STICK_1("models/props/stick_01.obj", true, Part("sticks", Layer.SOLID, "sticks", 0f)),
  STICK_2("models/props/stick_02.obj", true, Part("sticks", Layer.SOLID, "sticks", 0f)),
  STICK_3("models/props/stick_03.obj", true, Part("sticks", Layer.SOLID, "sticks", 0f)),
  GRASS_1("models/foliage/grass_01.obj", true, Part("card", Layer.CUTOUT, "grass_01", 0.05f)),
  GRASS_2("models/foliage/grass_02.obj", true, Part("card", Layer.CUTOUT, "grass_02", 0.06f)),
  GRASS_3("models/foliage/grass_03.obj", true, Part("card", Layer.CUTOUT, "grass_03", 0.06f)),
  FERN("models/foliage/fern_01.obj", true, Part("card", Layer.CUTOUT, "fern", 0.04f)),
  DANDELION("models/foliage/dandelion_01.obj", true, Part("card", Layer.CUTOUT, "dandelion", 0.05f)),
  WEED("models/foliage/weed_01.obj", true, Part("card", Layer.CUTOUT, "weed", 0.01f));

  /** Level-of-detail stand-ins are never placed by the scatter, only substituted when meshing far chunks. */
  val isLod: Boolean get() = name.endsWith("_LOD")

  /** The [TREE_LOD] / [ROCK_LOD] stand-in of this kind, or the kind itself when it has none. */
  val lodStandIn: Kind get() = when (this) { TREE -> TREE_LOD; ROCK -> ROCK_LOD; else -> this }

  companion object {
    val ALL: Array<Kind> = values()
  }
}

/** One placed object: [y] is the ground height at ([x], [z]); [yaw] in radians. */
class Placement(val kind: Kind, val x: Float, val y: Float, val z: Float, val yaw: Float, val scale: Float)

/** A circular area kept free of trees, rocks, shrubs and litter (e.g. around the spawn point). */
class Clearing(val x: Float, val z: Float, val radius: Float)

/**
 * Deterministic, per-chunk seeding of the ecosystem. Every kind lives on its own global jittered
 * grid ([cell] metres): a grid cell belongs to the chunk that contains its jittered point, so the
 * result for a chunk never depends on its neighbours or on the order chunks are loaded in, objects
 * of the same kind never get closer than ~30 % of a cell, and there are no duplicates at chunk
 * borders. What goes where follows [IslandTerrain.biomeAt]: forest -> trees, shrubs, ferns, sticks;
 * steep ground and rocky zones -> boulders; shores -> pebbles; meadows -> grass; never in the water.
 *
 * Pure functions of (seed, terrain, chunk), no GL: unit-testable.
 */
object WorldScatter {

  private const val SEA_MARGIN = 0.15f
  private val biome = ThreadLocal.withInitial { FloatArray(4) }

  /** The trees, shrubs and boulders: everything that is visible from far away. */
  fun planFar(terrain: IslandTerrain, chunkX: Int, chunkZ: Int, chunkSize: Float, clearings: List<Clearing>, out: MutableList<Placement>) {
    val x0 = chunkX * chunkSize
    val z0 = chunkZ * chunkSize
    val b = biome.get()!!

    // Trees: dense forest zones, gentle ground only
    cells(x0, z0, chunkSize, 7.5f, 1) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val forest = b[0]; val h = b[2]
      if (h < 3.5f || forest < 0.45f) return@cells
      val p = ((forest - 0.30f) * 1.5f).coerceIn(0f, 0.85f)
      if (r.next() > p) return@cells
      out.add(Placement(Kind.TREE, x, h, z, r.next() * TWO_PI, 0.85f + 0.5f * r.next()))
    }

    // Shrubs: dry bushes in and around the forest, also on rocky dry ground
    cells(x0, z0, chunkSize, 4.5f, 2) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val h = b[2]; val slope = b[3]
      if (h < 3.0f || h > 30f || slope > 0.75f) return@cells
      val p = 0.04f + 0.30f * b[0] + 0.12f * b[1]
      if (r.next() > p) return@cells
      out.add(Placement(Kind.SHRUB, x, h, z, r.next() * TWO_PI, 0.75f + 0.5f * r.next()))
    }

    // Boulders: steep slopes, rocky outcrop zones and the foot of the cliff coasts
    cells(x0, z0, chunkSize, 13f, 3) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val h = b[2]; val slope = b[3]; val gravel = b[1]
      if (h < 0.8f) return@cells
      val steep = smooth((slope - 0.30f) / 0.40f) * 0.75f
      val rocky = max(0f, gravel - 0.45f) * 0.9f
      val shoreRock = if (h < 3.5f && gravel > 0.1f) 0.28f * gravel else 0f
      if (r.next() > max(steep, max(rocky, shoreRock))) return@cells
      out.add(Placement(Kind.ROCK, x, h, z, r.next() * TWO_PI, 0.7f + 1.0f * r.next()))
    }
  }

  /** Small things that only exist near the player: pebbles, sticks, grass, ferns, flowers, weeds. */
  fun planNear(terrain: IslandTerrain, chunkX: Int, chunkZ: Int, chunkSize: Float, clearings: List<Clearing>, out: MutableList<Placement>) {
    val x0 = chunkX * chunkSize
    val z0 = chunkZ * chunkSize
    val b = biome.get()!!

    // Big beach pebble patches
    cells(x0, z0, chunkSize, 26f, 4) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val h = b[2]; val slope = b[3]
      if (h < 0.35f || h > 2.8f || slope > 0.22f) return@cells
      if (r.next() > 0.5f) return@cells
      out.add(Placement(Kind.PEBBLE_PATCH, x, h, z, r.next() * TWO_PI, 0.9f + 0.5f * r.next()))
    }

    // Pebble clusters on the beach and shingle
    cells(x0, z0, chunkSize, 5f, 5) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val h = b[2]; val slope = b[3]
      if (h < 0.15f || h > 3.2f || slope > 0.55f) return@cells
      if (r.next() > 0.30f + 0.35f * b[1]) return@cells
      val kind = when ((r.next() * 3f).toInt()) { 0 -> Kind.PEBBLE_1; 1 -> Kind.PEBBLE_2; else -> Kind.PEBBLE_3 }
      out.add(Placement(kind, x, h, z, r.next() * TWO_PI, 0.8f + 0.6f * r.next()))
    }

    // Dry sticks lying around under the trees
    cells(x0, z0, chunkSize, 7f, 6) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val h = b[2]
      if (h < 3.5f || b[0] < 0.35f) return@cells
      if (r.next() > 0.15f + 0.55f * b[0]) return@cells
      val kind = when ((r.next() * 3f).toInt()) { 0 -> Kind.STICK_1; 1 -> Kind.STICK_2; else -> Kind.STICK_3 }
      out.add(Placement(kind, x, h, z, r.next() * TWO_PI, 0.8f + 0.5f * r.next()))
    }

    // Grass tufts: meadows mostly, sparse in the forest, none on sand, gravel or steep rock
    cells(x0, z0, chunkSize, 1.05f, 7) { x, z, r ->
      terrain.biomeAt(x, z, b)
      val h = b[2]; val slope = b[3]
      if (h < 3.2f || h > 26f || slope > 0.55f) return@cells
      val density = (0.78f * (1f - b[1] * 0.95f)) * (1f - 0.65f * b[0])
      if (r.next() > density) return@cells
      val kind = when ((r.next() * 3f).toInt()) { 0 -> Kind.GRASS_1; 1 -> Kind.GRASS_2; else -> Kind.GRASS_3 }
      out.add(Placement(kind, x, h, z, r.next() * TWO_PI, 1.4f + 1.0f * r.next()))
    }

    // Ferns under the canopy
    cells(x0, z0, chunkSize, 2.6f, 8) { x, z, r ->
      terrain.biomeAt(x, z, b)
      val h = b[2]
      if (h < 3.5f || b[0] < 0.25f) return@cells
      if (r.next() > 0.55f * b[0]) return@cells
      out.add(Placement(Kind.FERN, x, h, z, r.next() * TWO_PI, 0.9f + 0.6f * r.next()))
    }

    // Ground-hugging weeds anywhere that is not sand
    cells(x0, z0, chunkSize, 3f, 9) { x, z, r ->
      terrain.biomeAt(x, z, b)
      val h = b[2]
      if (h < 2.8f || b[3] > 0.6f) return@cells
      if (r.next() > 0.32f) return@cells
      out.add(Placement(Kind.WEED, x, h, z, r.next() * TWO_PI, 1.5f + 1.0f * r.next()))
    }

    // A few dandelions in the open
    cells(x0, z0, chunkSize, 3.3f, 10) { x, z, r ->
      terrain.biomeAt(x, z, b)
      val h = b[2]
      if (h < 3.4f || b[0] > 0.4f || b[1] > 0.3f || b[3] > 0.45f) return@cells
      if (r.next() > 0.20f) return@cells
      out.add(Placement(Kind.DANDELION, x, h, z, r.next() * TWO_PI, 1.5f + 1.0f * r.next()))
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Tiny deterministic generator: a stream of floats in [0, 1) seeded by (cell, salt). */
  class Stream(private var state: Int) {
    fun next(): Float {
      state = mix(state + 0x9E3779B9.toInt())
      return (state ushr 8) / 16777216f
    }
  }

  private inline fun cells(
    x0: Float, z0: Float, size: Float, cell: Float, salt: Int,
    body: (x: Float, z: Float, r: Stream) -> Unit
  ) {
    val gx0 = floor(x0 / cell).toInt() - 1
    val gx1 = floor((x0 + size) / cell).toInt() + 1
    val gz0 = floor(z0 / cell).toInt() - 1
    val gz1 = floor((z0 + size) / cell).toInt() + 1
    for (gz in gz0..gz1) {
      for (gx in gx0..gx1) {
        val r = Stream(mix(mix(gx * 73856093 xor salt * 0x1B873593) + gz * 19349663))
        // Jittered inside the middle 70 % of the cell: neighbours keep a minimum spacing
        val x = (gx + 0.15f + 0.70f * r.next()) * cell
        val z = (gz + 0.15f + 0.70f * r.next()) * cell
        if (x < x0 || x >= x0 + size || z < z0 || z >= z0 + size) continue
        body(x, z, r)
      }
    }
  }

  private fun blocked(x: Float, z: Float, clearings: List<Clearing>): Boolean {
    for (c in clearings) {
      val dx = x - c.x; val dz = z - c.z
      if (dx * dx + dz * dz < c.radius * c.radius) return true
    }
    return false
  }

  private fun mix(v: Int): Int {
    var h = v
    h = (h xor (h ushr 16)) * 0x45D9F3B
    h = (h xor (h ushr 16)) * 0x45D9F3B
    return h xor (h ushr 16)
  }

  private fun smooth(t: Float): Float { val c = min(1f, max(0f, t)); return c * c * (3f - 2f * c) }

  private const val TWO_PI = 6.2831855f
}

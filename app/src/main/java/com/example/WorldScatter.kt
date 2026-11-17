package com.example

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Which batched mesh (and texture) a part of a model is drawn with: atlas opaque, atlas alpha-tested, or pine bark (repeating, normal-mapped). */
enum class Layer { SOLID, CUTOUT, BARK }

/** One material group of an OBJ model: its atlas cell and how much wind sways its tip (metres, unscaled). */
class Part(val group: String, val layer: Layer, val cell: String, val wind: Float)

/**
 * Everything the world populator can place. [near] kinds are the small ground cover and litter that
 * are only generated close to the player (and fade out with distance); the rest is seeded for the
 * whole streamed area. Pines ([pine] >= 0) have no file: [PineTreeGenerator] builds them.
 */
enum class Kind(val file: String?, val near: Boolean, val pine: Int, vararg val parts: Part) {
  PINE_1(null, false, 0),
  PINE_2(null, false, 1),
  PINE_3(null, false, 2),
  /** ~250-triangle stand-ins for the pines used by chunks far from the player. */
  PINE_1_LOD(null, false, 0),
  PINE_2_LOD(null, false, 1),
  PINE_3_LOD(null, false, 2),
  SHRUB("models/foliage/shrub_01.obj", false, -1, Part("card", Layer.CUTOUT, "shrub", 0.05f)),
  ROCK("models/props/rock_boulder_01.obj", false, -1, Part("rock", Layer.SOLID, "rock", 0f)),
  /** 300-triangle stand-in for [ROCK] used by chunks far from the player. */
  ROCK_LOD("models/props/rock_boulder_01_lod1.obj", false, -1, Part("rock", Layer.SOLID, "rock", 0f)),
  PEBBLE_PATCH("models/props/pebble_patch_01.obj", true, -1, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_1("models/props/pebble_01.obj", true, -1, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_2("models/props/pebble_02.obj", true, -1, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  PEBBLE_3("models/props/pebble_03.obj", true, -1, Part("pebbles", Layer.SOLID, "pebbles", 0f)),
  STICK_1("models/props/stick_01.obj", true, -1, Part("sticks", Layer.SOLID, "sticks", 0f)),
  STICK_2("models/props/stick_02.obj", true, -1, Part("sticks", Layer.SOLID, "sticks", 0f)),
  STICK_3("models/props/stick_03.obj", true, -1, Part("sticks", Layer.SOLID, "sticks", 0f)),
  GRASS_1("models/foliage/grass_01.obj", true, -1, Part("card", Layer.CUTOUT, "grass_01", 0.05f)),
  GRASS_2("models/foliage/grass_02.obj", true, -1, Part("card", Layer.CUTOUT, "grass_02", 0.06f)),
  GRASS_3("models/foliage/grass_03.obj", true, -1, Part("card", Layer.CUTOUT, "grass_03", 0.06f)),
  FERN("models/foliage/fern_01.obj", true, -1, Part("card", Layer.CUTOUT, "fern", 0.04f)),
  DANDELION("models/foliage/dandelion_01.obj", true, -1, Part("card", Layer.CUTOUT, "dandelion", 0.05f)),
  WEED("models/foliage/weed_01.obj", true, -1, Part("card", Layer.CUTOUT, "weed", 0.01f));

  /** Level-of-detail stand-ins are never placed by the scatter, only substituted when meshing far chunks. */
  val isLod: Boolean get() = name.endsWith("_LOD")

  /** A pine (full detail, not a stand-in): can be chopped down. */
  val isTree: Boolean get() = pine >= 0 && !isLod

  /** The low-triangle stand-in of this kind, or the kind itself when it has none. */
  val lodStandIn: Kind get() = when (this) {
    PINE_1 -> PINE_1_LOD; PINE_2 -> PINE_2_LOD; PINE_3 -> PINE_3_LOD; ROCK -> ROCK_LOD; else -> this
  }

  companion object {
    val ALL: Array<Kind> = values()
    val PINES = arrayOf(PINE_1, PINE_2, PINE_3)
  }
}

/** One placed object: [y] is the ground height at ([x], [z]); [yaw] in radians. */
class Placement(val kind: Kind, val x: Float, val y: Float, val z: Float, val yaw: Float, val scale: Float) {
  /** Chopped down: only the stump is left (see [WorldPopulator.fell]). */
  var felled: Boolean = false

  /** Stable identity of a tree (its position quantised to 1/16 m), shared by every rebuild of its chunk. */
  val id: Long get() = (Math.round(x * 16f).toLong() shl 32) or (Math.round(z * 16f).toLong() and 0xFFFFFFFFL)
}

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

    // Pines: a dense forest inland (the forest biome covers most of the interior, its own noise
    // leaves natural clearings); towards the coast it thins out and winding trails stay free
    cells(x0, z0, chunkSize, TREE_CELL, 1) { x, z, r ->
      if (blocked(x, z, clearings)) return@cells
      terrain.biomeAt(x, z, b)
      val forest = b[0]; val h = b[2]; val slope = b[3]
      if (h < 3.5f || forest < 0.40f || slope > 0.65f) return@cells
      if (onTrail(x, z, h)) return@cells
      val p = (smooth((forest - 0.40f) / 0.35f) * 0.92f).coerceIn(0f, 0.92f)
      if (r.next() > p) return@cells
      val variant = Kind.PINES[(r.next() * 3f).toInt().coerceAtMost(2)]
      val scale = PineTreeGenerator.MIN_SCALE + (PineTreeGenerator.MAX_SCALE - PineTreeGenerator.MIN_SCALE) * r.next()
      out.add(Placement(variant, x, h, z, r.next() * TWO_PI, scale))
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

  /** Grid spacing of the pine seeding (metres): up to ~10 trees per 16 m chunk in the thick of the forest. */
  const val TREE_CELL = 5.2f

  /**
   * Winding footpaths through the coastal woods: the 0.5 iso-line of a smooth noise field, ~4 m wide,
   * only below [TRAIL_MAX_HEIGHT] (inland the forest closes up).
   */
  fun onTrail(x: Float, z: Float, height: Float): Boolean {
    if (height > TRAIL_MAX_HEIGHT) return false
    val n = smoothNoise(x / 68f + 13.7f, z / 68f - 5.1f) * 0.65f + smoothNoise(x / 31f - 2.9f, z / 31f + 8.3f) * 0.35f
    val width = 0.030f * (1f - height / TRAIL_MAX_HEIGHT * 0.5f)
    return kotlin.math.abs(n - 0.5f) < width
  }

  private const val TRAIL_MAX_HEIGHT = 16f

  private fun smoothNoise(x: Float, z: Float): Float {
    val x0 = floor(x).toInt(); val z0 = floor(z).toInt()
    val fx = x - x0; val fz = z - z0
    val u = fx * fx * (3f - 2f * fx); val v = fz * fz * (3f - 2f * fz)
    fun h(ix: Int, iz: Int) = (mix(mix(ix * 0x27D4EB2D) + iz * 0x165667B1) ushr 8) / 16777216f
    val a = h(x0, z0) + u * (h(x0 + 1, z0) - h(x0, z0))
    val c = h(x0, z0 + 1) + u * (h(x0 + 1, z0 + 1) - h(x0, z0 + 1))
    return a + v * (c - a)
  }

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

package com.example

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A freshwater lake: water surface at [level], shore at ~[radius] metres from ([x], [z]). */
class Lake(
  val x: Float, val z: Float, val radius: Float, val level: Float, val shapeSeed: Int,
  /** Radii from the centre where the banks have blended back into the natural ground. */
  val bankEnd: Float = IslandTerrain.LAKE_BANK_END
) {
  /** The lake's water plane never needs to reach further than this from its centre (the terrain holds it in). */
  val waterExtent: Float get() = radius * IslandTerrain.LAKE_WATER_EXTENT
}

/**
 * Deterministic procedural peninsula in the spirit of The Forest's map, scaled to be walkable on a
 * phone (~1.4 x 1.4 km at the reference [radius] of 700 m: [radius] is HALF the side of the square
 * world, and every feature is laid out in coordinates normalised by it, so a smaller world is a
 * scaled copy). North is -Z, south +Z, east +X (the sun rises over +X).
 *
 *  - **Shape**: ocean to the West, South and East; the land widens and climbs towards the North,
 *    where a high rocky range runs off the edge of the map (the world's natural northern wall).
 *  - **Coasts**: wide sandy beaches and coves along the south, a rocky cape in the south-west and a
 *    sea cliff in the south-east; mixed coves and cliffs up the west and east coasts.
 *  - **Landmarks**: the great sinkhole (centre-west, ~75 m radius, near-vertical rock walls, a lake on
 *    the floor and a narrow ledge spiralling down its wall so it can be walked in and out), three
 *    freshwater lakes above sea level (centre-east and south-east) linked by a shallow dry creek bed,
 *    dense pine forest around them with meadows and dirt/gravel trails for orientation.
 *  - **Spawn**: a meadow clearing in the south-central forest, ~145 m from the south beach, near the
 *    south-east lake, sloping gently down towards the coast ([spawnX], [spawnZ]).
 *
 * Everything is a pure function of (x, z) and the seed (the lakes, crater and trails are laid out
 * once in the constructor from the same functions), so any chunk can be (re)generated independently,
 * digging works as before, and the cost of [heightAt] stays a few dozen value-noise lookups: the
 * landmark terms are skipped by cheap distance checks everywhere except right next to them.
 */
class IslandTerrain(
  private val seed: Int,
  val centerX: Float,
  val centerZ: Float,
  val radius: Float,
  val seaLevel: Float = 0f
) {

  /** Noise wavelengths grow with the world: 1.0 at the reference radius. */
  private val freqScale = REFERENCE_RADIUS / radius

  /** Metres per reference metre (feature sizes scale with the world). */
  private val s = radius / REFERENCE_RADIUS

  /** Vertical relief shrinks for worlds smaller than the reference. */
  private val relief = s.coerceIn(MIN_RELIEF, 1f)

  // --- Landmarks, laid out once ---------------------------------------------------------------

  /** The great sinkhole. */
  val craterX = centerX + CRATER_U * radius
  val craterZ = centerZ + CRATER_V * radius
  val craterRadius = CRATER_RADIUS * s
  /** Height of the plateau rim around the sinkhole and of its floor. */
  val craterRim: Float
  val craterFloor: Float
  /** Angle (radians, atan2(dz, dx)) where the ledge path leaves the rim; it spirals clockwise in plan view from there. */
  val craterPathAngle: Float = CRATER_PATH_ANGLE

  /** Player start: a meadow in the south-central forest. */
  val spawnX: Float
  val spawnZ: Float
  private val spawnHeight: Float

  /** Freshwater lakes: [0] the sinkhole lake, then centre-east, middle, south-east. */
  val lakes: List<Lake>

  /** Trail polyline segments (x0, z0, x1, z1) and their bounds. */
  private val trails: FloatArray
  private val trailMinX: Float; private val trailMaxX: Float
  private val trailMinZ: Float; private val trailMaxZ: Float

  /** Coarse grid of trail segment indices (so [trailAt] only measures the nearby ones). */
  private val trailCols: Int
  private val trailRows: Int
  private val trailBuckets: Array<IntArray>

  /** Creek bed between the lakes (x0, z0, x1, z1 per segment). */
  private val creek: FloatArray

  init {
    // Sinkhole on a little plateau (lifted local ground) so it is deep: ~18-20 m of sheer wall
    craterRim = baseHeightAt(craterX, craterZ) + CRATER_PLATEAU_LIFT * relief
    // floor high enough that even the lake bed stays above the sea (no sea water inside the sinkhole)
    craterFloor = max(seaLevel + 3.8f * relief, craterRim - CRATER_DEPTH * relief)

    // Spawn: walk north from the south edge at the spawn longitude until the shoreline, then inland
    val sx = centerX + SPAWN_U * radius
    var z = centerZ + radius * 0.98f
    while (z > centerZ && baseHeightAt(sx, z) < seaLevel) z -= 1f
    spawnX = sx
    spawnZ = z - SPAWN_FROM_SHORE * s
    spawnHeight = baseHeightAt(spawnX, spawnZ)

    // Lakes: the south-east one sits right next to the spawn meadow, the other two up the creek
    val defs = arrayOf(
      floatArrayOf(LAKE_SE_DX * s + spawnX, LAKE_SE_DZ * s + spawnZ, 42f),
      floatArrayOf(centerX + 0.40f * radius, centerZ + 0.20f * radius, 30f),
      floatArrayOf(centerX + 0.29f * radius, centerZ - 0.02f * radius, 58f)
    )
    val list = ArrayList<Lake>()
    val cr = craterRadius * CRATER_LAKE_FRACTION
    // the sinkhole lake's banks must stay on the floor (inside the inner wall)
    val floorRadius = craterRadius - (CRATER_OUTER_WALL + CRATER_LEDGE_WIDTH + CRATER_INNER_WALL) * s
    list.add(Lake(craterX, craterZ, cr, craterFloor - 0.5f * relief, 101, bankEnd = (floorRadius * 0.96f / cr).coerceIn(LAKE_BANK_HOLD + 0.1f, LAKE_BANK_END)))
    for ((i, d) in defs.withIndex()) {
      val r = d[2] * s
      val level = baseHeightAt(d[0], d[1]) - 1.3f * relief
      list.add(Lake(d[0], d[1], r, max(level, seaLevel + 1.2f * relief), 200 + i))
    }
    lakes = list

    creek = floatArrayOf(
      list[1].x, list[1].z, list[2].x, list[2].z,
      list[2].x, list[2].z, list[3].x, list[3].z
    )

    // Trails: beach -> spawn -> SE lake -> middle lake -> east lake -> sinkhole ledge -> foothills
    val pathTop = floatArrayOf(
      craterX + cos(CRATER_PATH_ANGLE) * craterRadius * 1.25f,
      craterZ + sin(CRATER_PATH_ANGLE) * craterRadius * 1.25f
    )
    val nodes = ArrayList<FloatArray>()
    nodes.add(floatArrayOf(spawnX - 6f * s, spawnZ + SPAWN_FROM_SHORE * s - 10f * s))
    nodes.add(floatArrayOf(spawnX, spawnZ))
    aroundLake(list[1], spawnX, spawnZ, list[2].x, list[2].z, 1.45f, nodes)
    aroundLake(list[2], list[1].x, list[1].z, list[3].x, list[3].z, 1.5f, nodes)
    aroundLake(list[3], list[2].x, list[2].z, pathTop[0], pathTop[1], 1.4f, nodes)
    nodes.add(pathTop)
    nodes.add(floatArrayOf(craterX + 0.12f * radius, craterZ - 0.30f * radius))
    nodes.add(floatArrayOf(centerX - 0.05f * radius, centerZ - 0.58f * radius))
    val seg = ArrayList<Float>()
    for (i in 0 until nodes.size - 1) wobblySegment(nodes[i], nodes[i + 1], i, seg)
    // A spur from the spawn west along the forest, towards the sinkhole's south side
    wobblySegment(floatArrayOf(spawnX, spawnZ), floatArrayOf(craterX + 0.05f * radius, craterZ + craterRadius * 1.4f), 40, seg)
    trails = seg.toFloatArray()
    var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
    for (i in trails.indices step 2) { x0 = min(x0, trails[i]); x1 = max(x1, trails[i]); z0 = min(z0, trails[i + 1]); z1 = max(z1, trails[i + 1]) }
    trailMinX = x0 - TRAIL_HALF_WIDTH * 3f; trailMaxX = x1 + TRAIL_HALF_WIDTH * 3f
    trailMinZ = z0 - TRAIL_HALF_WIDTH * 3f; trailMaxZ = z1 + TRAIL_HALF_WIDTH * 3f
    // Bucket the segments on a coarse grid so a query only measures the few nearby ones
    trailCols = ((trailMaxX - trailMinX) / TRAIL_CELL).toInt() + 1
    trailRows = ((trailMaxZ - trailMinZ) / TRAIL_CELL).toInt() + 1
    val buckets = Array(trailCols * trailRows) { ArrayList<Int>() }
    val pad = TRAIL_HALF_WIDTH * 3f
    for (si in 0 until trails.size / 4) {
      val ax = trails[si * 4]; val az = trails[si * 4 + 1]; val bx = trails[si * 4 + 2]; val bz = trails[si * 4 + 3]
      val c0 = ((min(ax, bx) - pad - trailMinX) / TRAIL_CELL).toInt().coerceIn(0, trailCols - 1)
      val c1 = ((max(ax, bx) + pad - trailMinX) / TRAIL_CELL).toInt().coerceIn(0, trailCols - 1)
      val r0 = ((min(az, bz) - pad - trailMinZ) / TRAIL_CELL).toInt().coerceIn(0, trailRows - 1)
      val r1 = ((max(az, bz) + pad - trailMinZ) / TRAIL_CELL).toInt().coerceIn(0, trailRows - 1)
      for (r in r0..r1) for (c in c0..c1) buckets[r * trailCols + c].add(si)
    }
    trailBuckets = Array(buckets.size) { buckets[it].toIntArray() }
  }


  /** Trail points along the shore of [l] (at [k] radii) on the short arc from the side facing (ax, az) to the side facing (bx, bz). */
  private fun aroundLake(l: Lake, ax: Float, az: Float, bx: Float, bz: Float, k: Float, out: MutableList<FloatArray>) {
    val a0 = atan2(az - l.z, ax - l.x)
    var a1 = atan2(bz - l.z, bx - l.x)
    while (a1 - a0 > 3.1416f) a1 -= TWO_PI
    while (a1 - a0 < -3.1416f) a1 += TWO_PI
    val steps = 4
    for (i in 0..steps) {
      val a = a0 + (a1 - a0) * i / steps
      out.add(floatArrayOf(l.x + cos(a) * l.radius * k, l.z + sin(a) * l.radius * k))
    }
  }

  /** Splits a→b into 8 pieces with a deterministic sideways wander, appending (x0, z0, x1, z1) quads. */
  private fun wobblySegment(a: FloatArray, b: FloatArray, salt: Int, out: ArrayList<Float>) {
    val dx = b[0] - a[0]; val dz = b[1] - a[1]
    val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-3f)
    val nx = -dz / len; val nz = dx / len
    var px = a[0]; var pz = a[1]
    for (k in 1..8) {
      val t = k / 8f
      val wander = if (k == 8) 0f else (hash(salt * 31 + k, salt, 77) * 0.5f + 0.5f * sin(t * 3.1416f)) * min(len * 0.08f, 14f * s) *
        sin(t * 3.1416f)
      val qx = a[0] + dx * t + nx * wander; val qz = a[1] + dz * t + nz * wander
      out.add(px); out.add(pz); out.add(qx); out.add(qz)
      px = qx; pz = qz
    }
  }

  // --- Coast ------------------------------------------------------------------------------

  /**
   * Metres inland from the coastline (negative = out at sea). The peninsula: a body that widens to the
   * north (open to the mainland at the map's north edge), a rounded south coast with a cape in the
   * south-west, all ragged by a slow coastal noise (bays and headlands).
   */
  fun inlandAt(x: Float, z: Float): Float {
    val u = (x - centerX) / radius
    val v = (z - centerZ) / radius
    val halfWidth = 0.76f + 0.12f * smoothstep(((-v - 0.15f) / 0.85f).coerceIn(0f, 1f))
    val side = (halfWidth - abs(u)) * radius
    val capeD = (u + 0.42f) / 0.085f
    val southV = 0.80f - 0.50f * u * u + 0.115f * exp(-capeD * capeD)
    val south = (southV - v) * radius
    // smooth minimum: rounded corners where the side coasts meet the south coast
    val k = 70f * s
    val h = (0.5f + 0.5f * (south - side) / k).coerceIn(0f, 1f)
    val inland = south + (side - south) * h - k * h * (1f - h)
    val warp = fbm(x * COAST_FREQUENCY * freqScale + 91.7f, z * COAST_FREQUENCY * freqScale - 33.1f, 3, 0)
    return inland + warp * COAST_WARP * s
  }

  /**
   * Cove factor along the shore: 1 = sheltered cove with a wide, gentle sandy beach, 0 = cliff coast
   * (short abrupt rise out of deep water). The south coast is beaches and coves, the south-west cape
   * rocky, the south-east a sea cliff, the long west and east coasts alternate.
   */
  fun coveAt(x: Float, z: Float): Float {
    val u = (x - centerX) / radius
    val v = (z - centerZ) / radius
    val mixed = smoothstep(((0.5f + 0.9f * fbm(x * 0.006f * freqScale + 3.3f, z * 0.006f * freqScale - 8.8f, 2, 5)).coerceIn(0f, 1f)))
    val southBeach = smoothstep(((v - 0.30f) / 0.25f).coerceIn(0f, 1f)) * (1f - smoothstep(((abs(u + 0.02f) - 0.26f) / 0.14f).coerceIn(0f, 1f)))
    var cove = mixed + (1f - mixed) * southBeach
    val cape = smoothstep(((-u - 0.30f) / 0.10f).coerceIn(0f, 1f)) * smoothstep(((v - 0.45f) / 0.15f).coerceIn(0f, 1f))
    val cliff = smoothstep(((u - 0.30f) / 0.10f).coerceIn(0f, 1f)) * smoothstep(((v - 0.28f) / 0.15f).coerceIn(0f, 1f))
    cove *= (1f - 0.92f * cape) * (1f - 0.95f * cliff)
    return cove.coerceIn(0f, 1f)
  }

  /** 0..1 outcrop zones: rocky ground with terraced ledges, gravel and no trees; the whole northern range is rock. */
  fun rockZoneAt(x: Float, z: Float): Float {
    val n = 0.5f + 0.5f * fbm(x * 0.007f * freqScale + 71.3f, z * 0.007f * freqScale - 12.9f, 2, 8)
    val patches = smoothstep(((n - 0.62f) / 0.14f).coerceIn(0f, 1f))
    val v = (z - centerZ) / radius
    val range = smoothstep(((-v - 0.52f) / 0.22f).coerceIn(0f, 1f)) * 0.9f
    return max(patches, range)
  }

  /** 0..1 along the thin dry-gully lines (ridged noise valleys). */
  private fun gullyAt(x: Float, z: Float): Float {
    val g = 1f - abs(fbm(x * GULLY_FREQUENCY * freqScale + 8.4f, z * GULLY_FREQUENCY * freqScale - 21.7f, 3, 7))
    return smoothstep(((g - 0.90f) / 0.085f).coerceIn(0f, 1f))
  }

  // --- Height -----------------------------------------------------------------------------

  /** Terrain surface height (world Y) at (x, z). */
  fun heightAt(x: Float, z: Float): Float {
    var h = baseHeightAt(x, z)
    // Spawn meadow: an even, gently south-sloping clearing
    val sdx = x - spawnX; val sdz = z - spawnZ
    val sd2 = sdx * sdx + sdz * sdz
    val meadowR = SPAWN_MEADOW * s
    if (sd2 < (meadowR * 2.2f) * (meadowR * 2.2f)) {
      val m = 1f - smoothstep(((sqrt(sd2) - meadowR) / (meadowR * 1.2f)).coerceIn(0f, 1f))
      val target = spawnHeight - SPAWN_SLOPE * sdz
      h += (target - h) * m
    }
    // Sinkhole
    val cdx = x - craterX; val cdz = z - craterZ
    val cd2 = cdx * cdx + cdz * cdz
    val plateau = craterRadius + CRATER_PLATEAU * s
    if (cd2 < plateau * plateau) h = crater(cdx, cdz, sqrt(cd2), h)
    // Creek bed between the lakes
    h = creekBed(x, z, h)
    // Lakes (last: their banks hold the water in)
    for (i in lakes.indices) {
      val l = lakes[i]
      val dx = x - l.x; val dz = z - l.z
      val r2 = dx * dx + dz * dz
      val reach = l.radius * l.bankEnd
      if (r2 < reach * reach) h = lake(l, dx, dz, sqrt(r2), h)
    }
    return h
  }

  /**
   * The terrain before the landmarks: shelf, beaches and cliffs from [inlandAt], a lowland rising
   * north into hills and a ridged rocky range, with hummocks, spurs, gullies and terraced outcrops.
   */
  private fun baseHeightAt(x: Float, z: Float): Float {
    val inland = inlandAt(x, z)
    val cove = coveAt(x, z)
    val w = WATER_SPAN * s * (0.45f + 0.55f * cove)
    val b = BEACH_SPAN * s * (0.20f + 0.80f * cove)
    val top = BEACH_TOP_HEIGHT + (1f - cove) * CLIFF_LIFT * relief.coerceAtLeast(0.4f)

    // Shelf + beach as ONE smoothstep from the sea floor to the top of the beach: no crease at the waterline
    val shelf = ((inland + w) / (w + b)).coerceIn(0f, 1f)
    var height = seaLevel - SEABED_DEPTH + (top + SEABED_DEPTH) * smoothstep(shelf)

    if (inland > 0f && cove < 0.95f) {
      val crag = 1f - abs(fbm(x * 0.055f * freqScale + 2.2f, z * 0.055f * freqScale + 9.1f, 3, 4))
      height += (1f - cove) * crag * crag * CRAG_AMPLITUDE * relief.coerceAtLeast(0.4f) * smoothstep((inland / 12f).coerceIn(0f, 1f))
    }

    if (inland < b) {
      val seaFade = 1f - smoothstep(((inland + w * 0.4f) / (w * 0.4f)).coerceIn(0f, 1f))
      if (seaFade > 0f) {
        height += fbm(x * HILL_FREQUENCY * freqScale, z * HILL_FREQUENCY * freqScale, 2, 3) * SEABED_ROUGHNESS * seaFade
      }
      return height
    }

    val v = (z - centerZ) / radius
    // Interior fraction: 0 at the top of the beach, 1 ~250 m inland
    val f = ((inland - b) / (INTERIOR_SPAN * s)).coerceIn(0f, 1f)
    // North: 0 over the southern half, 1 at the northern edge (the range)
    val north = smoothstep(((0.12f - v) / 0.95f).coerceIn(0f, 1f))
    // (the northern rise also eases in from the top of the beach: no step along the north coasts)
    height += RISE * relief * smoothstep(f) + NORTH_RISE * relief * north * north * smoothstep((f / 0.45f).coerceIn(0f, 1f))

    val hillMask = smoothstep((f / 0.35f).coerceIn(0f, 1f))
    height += fbm(x * HILL_FREQUENCY * freqScale + 5.2f, z * HILL_FREQUENCY * freqScale + 1.3f, 4, 1) *
      HILL_AMPLITUDE * relief * hillMask * (0.65f + 0.6f * north)

    val nearMask = smoothstep(((inland - b) / (30f * s)).coerceIn(0f, 1f))
    height += fbm(x * 0.09f * freqScale + 31.1f, z * 0.09f * freqScale - 5.6f, 2, 4) * HUMMOCK_AMPLITUDE * relief.coerceAtLeast(0.4f) * nearMask

    val spurMask = smoothstep(((f - 0.05f) / 0.35f).coerceIn(0f, 1f)) * (0.45f + 0.8f * north)
    val spur = 1f - abs(fbm(x * 0.021f * freqScale + 14.7f, z * 0.021f * freqScale - 40.2f, 3, 6))
    height += spur * spur * spur * SPUR_AMPLITUDE * relief * spurMask

    // The northern range: ridged massif plus a crest running east-west near the map edge
    val mountainMask = smoothstep(((north - 0.30f) / 0.55f).coerceIn(0f, 1f)) * hillMask
    if (mountainMask > 0f) {
      val ridge = 1f - abs(fbm(x * MOUNTAIN_FREQUENCY * freqScale - 40.7f, z * MOUNTAIN_FREQUENCY * freqScale + 17.9f, 4, 2))
      val crestD = (v + 0.80f) / 0.20f
      height += (ridge * ridge * ridge * MOUNTAIN_AMPLITUDE + MOUNTAIN_CREST * exp(-crestD * crestD)) * relief * mountainMask
    }

    val gully = gullyAt(x, z)
    if (gully > 0f) {
      val carve = GULLY_DEPTH * relief * gully * smoothstep(((f - 0.06f) / 0.30f).coerceIn(0f, 1f))
      height -= carve.coerceAtMost((height - seaLevel - 1.5f).coerceAtLeast(0f))
    }

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
   * The sinkhole at plan offset ([dx], [dz]) from its centre, distance [r]. Outside: a plateau that
   * lifts the ground to [craterRim]. Inside, from the rim inwards: a near-vertical outer wall, a
   * narrow ledge, a second sheer wall and the flat floor. The ledge is a ramp: it starts flush with
   * the rim at [craterPathAngle] and spirals down ~315 deg to the floor at a walkable ~9 % grade.
   */
  private fun crater(dx: Float, dz: Float, r: Float, ground: Float): Float {
    val rc = craterRadius
    if (r >= rc) {
      // plateau: blend the natural ground to the rim height, with a small lip at the edge
      val t = 1f - smoothstep(((r - rc) / (CRATER_PLATEAU * s)).coerceIn(0f, 1f))
      val xl = (r - rc) / (5f * s)
      val lip = 0.8f * relief * xl * exp(1f - xl)
      return ground + (craterRim - ground) * t + lip
    }
    var a = craterPathAngle - atan2(dz, dx)
    while (a < 0f) a += TWO_PI
    while (a >= TWO_PI) a -= TWO_PI
    val ledge = if (a <= CRATER_PATH_SPAN) craterRim - (craterRim - craterFloor) * (a / CRATER_PATH_SPAN) else craterFloor
    val rOut = rc - CRATER_OUTER_WALL * s
    val rLedge = rOut - CRATER_LEDGE_WIDTH * s
    val rIn = rLedge - CRATER_INNER_WALL * s
    return when {
      r >= rOut -> ledge + (craterRim - ledge) * steep((r - rOut) / (rc - rOut))
      r >= rLedge -> ledge
      r >= rIn -> craterFloor + (ledge - craterFloor) * steep((r - rIn) / (rLedge - rIn))
      else -> craterFloor + 0.5f * relief * fbm(dx * 0.06f + 3f, dz * 0.06f, 2, 11) * smoothstep(((rIn - r) / (8f * s)).coerceIn(0f, 1f))
    }
  }

  /** Wall profile: most of the drop in the middle of the band (near-vertical face, rounded top and foot). */
  private fun steep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    val q = c * c * (3f - 2f * c)
    return q * q * (3f - 2f * q)
  }

  /** A gentle, dry creek bed along the chain of lakes (never deep enough to hold their water). */
  private fun creekBed(x: Float, z: Float, h: Float): Float {
    var best = Float.MAX_VALUE
    var i = 0
    while (i < creek.size) {
      best = min(best, segmentDistance(x, z, creek[i], creek[i + 1], creek[i + 2], creek[i + 3]))
      i += 4
    }
    val width = CREEK_WIDTH * s
    if (best >= width) return h
    val t = best / width
    return h - CREEK_DEPTH * relief * (1f - t * t)
  }

  /**
   * Lake basin and banks at distance [r] from its centre. The shoreline is warped by a periodic
   * noise of the angle. Inside: a bowl ~3 m deep. From the shore to [LAKE_BANK_HOLD] radii the
   * ground is kept above the water level (so the water plane, which spans [Lake.waterExtent], can
   * never show over lower land), then it blends back to the natural terrain.
   */
  private fun lake(l: Lake, dx: Float, dz: Float, r: Float, ground: Float): Float {
    val a = atan2(dz, dx)
    val warp = 1f + 0.20f * fbm(cos(a) * 1.4f + l.shapeSeed * 3.1f, sin(a) * 1.4f - l.shapeSeed * 1.7f, 2, 12)
    val d = r / (l.radius * warp)
    val bank = l.level + LAKE_BANK_HEIGHT * relief
    val shaped = if (d < 1f) {
      l.level - LAKE_DEPTH * relief * (1f - d * d) + 0.35f * relief * d
    } else {
      // gentle grassy bank rising from the waterline
      l.level + 0.35f * relief + (bank - l.level - 0.35f * relief) * smoothstep(((d - 1f) / 0.35f).coerceIn(0f, 1f))
    }
    // From the shore outwards the natural ground comes back, but (up to the hold radius) never below
    // the bank: the water can't show over lower land, and slopes above are eased to a few metres
    val kept = max(shaped, min(ground, bank + 2.5f * relief))
    val rise = smoothstep(((d - 1.05f) / 0.7f).coerceIn(0f, 1f))
    val held = shaped + (kept - shaped) * rise
    val back = smoothstep(((d - LAKE_BANK_HOLD) / (l.bankEnd - LAKE_BANK_HOLD)).coerceIn(0f, 1f))
    return held + (ground - held) * back
  }

  // --- Biomes -----------------------------------------------------------------------------

  /** 0..1: 1 on a trail (dirt and gravel, no trees), fading over a metre and a half. */
  fun trailAt(x: Float, z: Float): Float {
    if (x < trailMinX || x > trailMaxX || z < trailMinZ || z > trailMaxZ) return 0f
    val c = ((x - trailMinX) / TRAIL_CELL).toInt().coerceIn(0, trailCols - 1)
    val r = ((z - trailMinZ) / TRAIL_CELL).toInt().coerceIn(0, trailRows - 1)
    val bucket = trailBuckets[r * trailCols + c]
    if (bucket.isEmpty()) return 0f
    var best = Float.MAX_VALUE
    for (si in bucket) {
      val i = si * 4
      best = min(best, segmentDistance(x, z, trails[i], trails[i + 1], trails[i + 2], trails[i + 3]))
    }
    val hw = TRAIL_HALF_WIDTH * s.coerceAtLeast(0.3f)
    return 1f - smoothstep(((best - hw) / (1.5f * s.coerceAtLeast(0.3f))).coerceIn(0f, 1f))
  }

  /**
   * Where the lakes are: 0 far away, 1 on their open shores (grass, reeds and ferns, no trees). Also
   * returns 1 in the water itself.
   */
  fun lakeShoreAt(x: Float, z: Float): Float {
    var best = 0f
    for (l in lakes) {
      val dx = x - l.x; val dz = z - l.z
      val d = sqrt(dx * dx + dz * dz) / l.radius
      best = max(best, 1f - smoothstep(((d - 1.25f) / 0.45f).coerceIn(0f, 1f)))
    }
    return best
  }

  /** True if (x, z) at ground height [h] is under the water of a lake (nothing grows or lies there). */
  fun inLakeWater(x: Float, z: Float, h: Float): Boolean {
    for (l in lakes) {
      val dx = x - l.x; val dz = z - l.z
      val e = l.waterExtent
      if (dx * dx + dz * dz < e * e && h < l.level + 0.12f) return true
    }
    return false
  }

  /**
   * Height as the SEA sees it: the lake basins (and the sinkhole) are inland land, so even a bed that
   * dips below sea level never fills with sea water. Used to bake the sea's shore map.
   */
  fun seaFloorAt(x: Float, z: Float): Float {
    val h = heightAt(x, z)
    if (h >= seaLevel) return h
    for (l in lakes) {
      val dx = x - l.x; val dz = z - l.z
      val e = l.radius * l.bankEnd
      if (dx * dx + dz * dz < e * e) return seaLevel + 1f
    }
    return h
  }

  /** 0..1: the sinkhole's walls and ledge (bare rock). */
  fun craterWallAt(x: Float, z: Float): Float {
    val dx = x - craterX; val dz = z - craterZ
    val r = sqrt(dx * dx + dz * dz)
    val rIn = craterRadius - (CRATER_OUTER_WALL + CRATER_LEDGE_WIDTH + CRATER_INNER_WALL + 2f) * s
    return smoothstep(((r - rIn) / (3f * s)).coerceIn(0f, 1f)) * (1f - smoothstep(((r - craterRadius - 2f * s) / (3f * s)).coerceIn(0f, 1f)))
  }

  /**
   * Ground-cover weights at (x, z), written to [out]: out[0] = forest (0..1: trees and forest floor),
   * out[1] = gravel (0..1: shingle shores, rock, trails, gully beds). Pure functions of the terrain, so
   * the mesher (vertex attribute for the shader) and the world populator (where trees and rocks go)
   * always agree. When [out] has 4 slots it also receives the height (2) and the slope (3).
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
    val trail = trailAt(x, z)
    val shore = lakeShoreAt(x, z)
    val wall = craterWallAt(x, z)

    val heightBand = smoothstep(((above - 4f) / 3f).coerceIn(0f, 1f)) * (1f - smoothstep(((above - 27f) / 8f).coerceIn(0f, 1f)))
    val gentle = 1f - smoothstep(((slope - 0.36f) / 0.34f).coerceIn(0f, 1f))
    // Forest: thin near the coast, dense inland and in a wide ring around the lakes and the sinkhole;
    // the patch noise still leaves meadows
    val inland = inlandAt(x, z)
    val interior = smoothstep(((inland - 50f * s) / (170f * s)).coerceIn(0f, 1f))
    val cdx = x - craterX; val cdz = z - craterZ
    val craterRing = 1f - smoothstep(((sqrt(cdx * cdx + cdz * cdz) / craterRadius - 1.6f) / 1.4f).coerceIn(0f, 1f))
    var lakeRing = 0f
    for (l in lakes) {
      val d = sqrt((x - l.x) * (x - l.x) + (z - l.z) * (z - l.z)) / l.radius
      lakeRing = max(lakeRing, 1f - smoothstep(((d - 2.2f) / 1.8f).coerceIn(0f, 1f)))
    }
    val dense = max(interior, max(craterRing, lakeRing))
    val patchLow = 0.38f - 0.19f * dense
    val patch = smoothstep((((0.5f + 0.5f * fbm(x * 0.011f * freqScale + 55.5f, z * 0.011f * freqScale - 17.7f, 3, 9)) - patchLow) / 0.13f).coerceIn(0f, 1f))
    val sdx = x - spawnX; val sdz = z - spawnZ
    val meadow = 1f - smoothstep(((sqrt(sdx * sdx + sdz * sdz) - SPAWN_OPEN * s) / (8f * s)).coerceIn(0f, 1f))
    out[0] = (heightBand * gentle * patch * (1f - rz) * (1f - gully) * (1f - (trail * 1.5f).coerceAtMost(1f)) * (1f - shore) * (1f - wall) * (1f - meadow)).coerceIn(0f, 1f)

    val shingle = smoothstep(((above + 0.6f) / 1.1f).coerceIn(0f, 1f)) * (1f - smoothstep(((above - 1.6f) / 1.6f).coerceIn(0f, 1f)))
    val scree = smoothstep(((slope - 0.40f) / 0.30f).coerceIn(0f, 1f)) * 0.45f
    out[1] = maxOf(maxOf(shingle * (0.30f + 0.70f * (1f - cove)), rz * 0.85f, gully * 0.45f), maxOf(scree, trail * 0.95f, wall * 0.9f)).coerceIn(0f, 1f)
    if (out.size >= 4) {
      out[2] = h
      out[3] = slope
    }
  }

  // --- helpers ------------------------------------------------------------------------------

  private fun segmentDistance(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
    val dx = bx - ax; val dz = bz - az
    val l2 = dx * dx + dz * dz
    val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (pz - az) * dz) / l2).coerceIn(0f, 1f)
    val qx = ax + dx * t - px; val qz = az + dz * t - pz
    return sqrt(qx * qx + qz * qz)
  }

  /** Eases heights above [CAP_START] towards [MAX_HEIGHT_ABOVE_SEA] so no peak can reach the top of the world. */
  private fun softCap(h: Float): Float {
    val over = h - (seaLevel + CAP_START)
    return if (over <= 0f) h else seaLevel + CAP_START + CAP_SOFT_RANGE * (1f - exp(-over / CAP_SOFT_RANGE))
  }

  private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

  /** Fractal Brownian motion of [octaves] value-noise layers, roughly in [-1, 1]; [layer] decorrelates uses. */
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

    /** The world the constants are tuned for: 700 m half-side = a 1.4 x 1.4 km peninsula. */
    const val REFERENCE_RADIUS = 700f

    /** Sea floor depth under the sea level, in metres. */
    const val SEABED_DEPTH = 7f

    /** Height of the top of the beach above sea level; the hills start from here. */
    const val BEACH_TOP_HEIGHT = 3.5f

    /** Hard ceiling of the terrain above sea level (the soft cap approaches it asymptotically). */
    const val MAX_HEIGHT_ABOVE_SEA = 50f

    /** A lake's water plane spans this many radii from its centre (its banks hold up to [LAKE_BANK_HOLD]). */
    const val LAKE_WATER_EXTENT = 1.35f

    // Layout (normalised by the radius; v > 0 is south)
    private const val CRATER_U = -0.36f
    private const val CRATER_V = -0.08f
    private const val SPAWN_U = 0.05f
    /** Spawn distance from the shoreline, metres. */
    private const val SPAWN_FROM_SHORE = 150f
    private const val SPAWN_MEADOW = 22f
    private const val SPAWN_OPEN = 20f
    private const val SPAWN_SLOPE = 0.035f
    /** South-east lake centre relative to the spawn (metres): ~110 m away, north-east of it. */
    private const val LAKE_SE_DX = 95f
    private const val LAKE_SE_DZ = -55f

    // Sinkhole (metres at the reference size)
    private const val CRATER_RADIUS = 75f
    private const val CRATER_PLATEAU = 70f
    private const val CRATER_PLATEAU_LIFT = 8f
    private const val CRATER_DEPTH = 20f
    private const val CRATER_OUTER_WALL = 4.5f
    private const val CRATER_LEDGE_WIDTH = 4.0f
    private const val CRATER_INNER_WALL = 5.5f
    private const val CRATER_LAKE_FRACTION = 0.34f
    private const val CRATER_PATH_ANGLE = 0.35f
    /** The ledge descends over this angle (~315 deg, ~330 m of path for ~20 m: about 6 %). */
    private const val CRATER_PATH_SPAN = 5.5f
    private const val TWO_PI = 6.2831855f

    // Lakes
    private const val LAKE_DEPTH = 3.0f
    private const val LAKE_BANK_HEIGHT = 0.9f
    const val LAKE_BANK_HOLD = 2.0f
    const val LAKE_BANK_END = 3.0f
    private const val CREEK_WIDTH = 7f
    private const val CREEK_DEPTH = 1.3f

    // Trails
    private const val TRAIL_HALF_WIDTH = 1.7f
    private const val TRAIL_CELL = 32f

    // Profile, metres at the reference size
    private const val WATER_SPAN = 100f
    private const val BEACH_SPAN = 80f
    private const val INTERIOR_SPAN = 250f
    private const val COAST_FREQUENCY = 1f / 240f
    private const val COAST_WARP = 38f
    private const val HILL_FREQUENCY = 1f / 150f
    private const val MOUNTAIN_FREQUENCY = 1f / 240f
    private const val GAIN = 0.5f
    private const val LACUNARITY = 2.0f

    // Relief, metres (scaled by 'relief' on smaller worlds)
    private const val CLIFF_LIFT = 7f
    private const val CRAG_AMPLITUDE = 4.5f
    private const val HUMMOCK_AMPLITUDE = 1.3f
    private const val SPUR_AMPLITUDE = 5f
    private const val GULLY_FREQUENCY = 1f / 65f
    private const val GULLY_DEPTH = 3.5f
    private const val TERRACE_STEP = 2.2f
    private const val RISE = 5f
    private const val NORTH_RISE = 16f
    private const val HILL_AMPLITUDE = 5.5f
    private const val MOUNTAIN_AMPLITUDE = 22f
    private const val MOUNTAIN_CREST = 10f
    private const val SEABED_ROUGHNESS = 0.7f
    private const val MIN_RELIEF = 0.15f

    private const val CAP_START = 44f
    private const val CAP_SOFT_RANGE = MAX_HEIGHT_ABOVE_SEA - CAP_START
  }
}

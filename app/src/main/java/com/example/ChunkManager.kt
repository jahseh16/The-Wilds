package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Frustum
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns the world as a grid of 16x64x16 [Chunk]s filled with a procedural island, meshes them
 * with Marching Cubes and answers every spatial query (density, raycast, spawn, excavation) in
 * world coordinates by routing it to the right chunk.
 *
 * The 1 km island spans 64 x 64 chunk slots but only the ones near the player exist: resident
 * chunks live in a sparse `HashMap<Long, Chunk>` keyed by [Chunk.key], so memory is proportional to
 * the streaming radius, not to the size of the world.
 *
 * Only the chunks within [LOAD_RADIUS_CHUNKS] of the player are resident (see [update]); the rest
 * of the world exists implicitly as "terrain function + [ChunkDeltaStore] edits", so queries such
 * as [getDensityAt] and [raycast] give the same answer whether or not a chunk is currently loaded.
 *
 * The manager knows nothing about the screen or the player object: the only input is the position
 * passed to [update]. Per-chunk content (trees, rocks, rivers...) can hook in through
 * [ChunkListener] without touching the terrain code.
 *
 * Grid-space convention: a grid point (gx, gy, gz) sits at world (minX + gx*voxelSize, minY +
 * gy*voxelSize, minZ + gz*voxelSize) and is stored by the chunk that contains cell (gx, gz).
 */
class ChunkManager(
  val islandRadius: Float = DEFAULT_ISLAND_RADIUS,
  seed: Int = IslandTerrain.DEFAULT_SEED,
  val voxelSize: Float = 1.0f,
  val seaLevel: Float = SEA_LEVEL
) : Disposable {

  // World grid: island plus a shoreline margin of open sea floor, rounded up to whole chunks
  val chunksX: Int = ceil(2f * (islandRadius + WORLD_MARGIN) / (Chunk.SIZE_X * voxelSize)).toInt()
  val chunksZ: Int = chunksX
  val cellsX: Int = chunksX * Chunk.SIZE_X
  val cellsY: Int = Chunk.SIZE_Y
  val cellsZ: Int = chunksZ * Chunk.SIZE_Z

  val minX: Float = 0.0f
  val maxX: Float = cellsX * voxelSize
  val minY: Float = -12.0f
  val maxY: Float = minY + cellsY * voxelSize
  val minZ: Float = 0.0f
  val maxZ: Float = cellsZ * voxelSize

  val centerX: Float = (minX + maxX) * 0.5f
  val centerZ: Float = (minZ + maxZ) * 0.5f

  private val terrain = IslandTerrain(seed, centerX, centerZ, islandRadius, seaLevel)

  private val terrainMaterial = Material(
    ColorAttribute.createDiffuse(Color(0.32f, 0.45f, 0.24f, 1f)),
    IntAttribute.createCullFace(GL20.GL_NONE)
  )

  /** Resident chunks only, keyed by [Chunk.key]; a missing key means "not loaded" (streamed in/out). */
  private val chunks = HashMap<Long, Chunk>()

  /** The same chunks as a list, so the per-frame loops (culling, unloading) allocate nothing. */
  private val residentChunks = ArrayList<Chunk>()

  // The mesher asks for the density of neighbouring chunks thousands of times in a row, always
  // around the same 3x3 chunks. A small direct-mapped cache in front of the HashMap makes those
  // lookups free of hashing and boxing; it is flushed whenever a chunk is added or removed.
  private val lookupKeys = LongArray(LOOKUP_CACHE_SIZE) { NO_KEY }
  private val lookupChunks = arrayOfNulls<Chunk>(LOOKUP_CACHE_SIZE)

  /** Player excavation, kept for the whole session even while its chunk is unloaded. */
  private val deltas = ChunkDeltaStore()

  private val chunkSpanX: Float = Chunk.SIZE_X * voxelSize
  private val chunkSpanZ: Float = Chunk.SIZE_Z * voxelSize
  private val loadRadius: Float = LOAD_RADIUS_CHUNKS * chunkSpanX
  private val loadRadiusSq: Float = loadRadius * loadRadius
  private val unloadRadius: Float = (LOAD_RADIUS_CHUNKS + UNLOAD_HYSTERESIS_CHUNKS) * chunkSpanX
  private val unloadRadiusSq: Float = unloadRadius * unloadRadius

  private val loadQueue = ArrayDeque<Long>()
  private var lastPlayerChunkX = Int.MIN_VALUE
  private var lastPlayerChunkZ = Int.MIN_VALUE
  private val listeners = ArrayList<ChunkListener>(2)

  private class QueueEntry(val key: Long, val distSq: Float)

  private val aimModel: Model
  val aimIndicatorInstance: ModelInstance

  // Reusable scratch buffers for zero-allocation Marching Cubes & raycasting
  private val cornerDensities = FloatArray(8)
  private val cornerPositionsX = FloatArray(8)
  private val cornerPositionsY = FloatArray(8)
  private val cornerPositionsZ = FloatArray(8)
  private val cornerNormalsX = FloatArray(8)
  private val cornerNormalsY = FloatArray(8)
  private val cornerNormalsZ = FloatArray(8)
  private val edgeVertexIndices = ShortArray(12)

  // Sky exposure of every grid point of the chunk being meshed, including the +X / +Z border
  // columns that belong to the neighbours: index = (lz * (SIZE_X + 1) + lx) * GRID_Y + gy
  private val skyExposure = FloatArray((Chunk.SIZE_X + 1) * (Chunk.SIZE_Z + 1) * Chunk.GRID_Y)
  private val cornerSky = FloatArray(8)

  private val scratchVertexBuffer = FloatArray(Chunk.MAX_SLICE_VERTICES * Chunk.VERTEX_FLOATS)
  private val scratchIndexBuffer = ShortArray(Chunk.MAX_SLICE_INDICES)

  private val tempNormal = Vector3()
  private val tempPos = Vector3()
  private val tempQuat = Quaternion()

  init {
    // No chunk is generated here: they stream in through prewarm() / update()
    aimModel = createAimIndicatorModel()
    aimIndicatorInstance = ModelInstance(aimModel)
  }

  /** Number of chunks currently resident in memory. */
  val loadedChunkCount: Int
    get() = residentChunks.size

  /**
   * Distance from the player up to which terrain is guaranteed to exist in every direction: the
   * load radius minus the half diagonal of a chunk (a chunk is loaded when its CENTRE is in range).
   * The terrain shader fades into the fog just before it, so the edge of the world is never a cut.
   */
  val solidTerrainRadius: Float
    get() = loadRadius - chunkSpanX * 0.75f

  /** Number of chunks queued to be generated (nearest first), one per [update] call. */
  val pendingChunkCount: Int
    get() = loadQueue.size

  fun isChunkLoaded(cx: Int, cz: Int): Boolean = chunkAt(cx, cz) != null

  /** The resident chunk at (cx, cz), or null. Cached; allocation-free on a cache hit. */
  private fun chunkAt(cx: Int, cz: Int): Chunk? {
    val key = Chunk.key(cx, cz)
    val slot = (cx * 7 + cz * 3) and (LOOKUP_CACHE_SIZE - 1)
    if (lookupKeys[slot] == key) return lookupChunks[slot]
    val chunk = chunks[key]
    lookupKeys[slot] = key
    lookupChunks[slot] = chunk
    return chunk
  }

  private fun flushLookupCache() {
    java.util.Arrays.fill(lookupKeys, NO_KEY)
    java.util.Arrays.fill(lookupChunks, null)
  }

  private fun inWorld(cx: Int, cz: Int): Boolean = cx in 0 until chunksX && cz in 0 until chunksZ

  // --- Streaming ------------------------------------------------------------------------------

  /**
   * Keeps the resident set in sync with [playerPos]. Call once per frame, before rendering or
   * querying the terrain:
   *  - chunks beyond the unload radius are disposed (their edits stay in [deltas]);
   *  - missing chunks inside the load radius are queued nearest-first, and at most ONE of them is
   *    generated and meshed per call, so walking never produces a burst of work in a single frame.
   *
   * [delta] is the frame time in seconds; it is part of the signature so later systems (fade-in,
   * decoration spawning) can be budgeted per frame without changing callers.
   */
  fun update(playerPos: Vector3, delta: Float) {
    val playerChunkX = chunkCoordX(playerPos.x)
    val playerChunkZ = chunkCoordZ(playerPos.z)
    if (playerChunkX != lastPlayerChunkX || playerChunkZ != lastPlayerChunkZ) {
      lastPlayerChunkX = playerChunkX
      lastPlayerChunkZ = playerChunkZ
      unloadDistantChunks(playerPos.x, playerPos.z)
      rebuildLoadQueue(playerPos.x, playerPos.z, playerChunkX, playerChunkZ)
    }
    loadNextQueuedChunk()
  }

  /**
   * Synchronously loads every chunk within [radiusChunks] (square window) of [center], so the
   * ground under the player is visible on the very first frame. Call once after creation.
   */
  fun prewarm(center: Vector3, radiusChunks: Int = PREWARM_RADIUS_CHUNKS) {
    val pcx = chunkCoordX(center.x)
    val pcz = chunkCoordZ(center.z)
    for (cz in (pcz - radiusChunks).coerceAtLeast(0)..(pcz + radiusChunks).coerceAtMost(chunksZ - 1)) {
      for (cx in (pcx - radiusChunks).coerceAtLeast(0)..(pcx + radiusChunks).coerceAtMost(chunksX - 1)) {
        if (chunkAt(cx, cz) == null) loadChunk(cx, cz)
      }
    }
  }

  fun addListener(listener: ChunkListener) {
    listeners.add(listener)
  }

  fun removeListener(listener: ChunkListener) {
    listeners.remove(listener)
  }

  private fun chunkCoordX(worldX: Float): Int = floor((worldX - minX) / chunkSpanX).toInt()

  private fun chunkCoordZ(worldZ: Float): Int = floor((worldZ - minZ) / chunkSpanZ).toInt()

  /** Squared horizontal distance from (px, pz) to the centre of chunk (cx, cz). */
  private fun chunkDistSq(cx: Int, cz: Int, px: Float, pz: Float): Float {
    val dx = minX + (cx + 0.5f) * chunkSpanX - px
    val dz = minZ + (cz + 0.5f) * chunkSpanZ - pz
    return dx * dx + dz * dz
  }

  private fun unloadDistantChunks(px: Float, pz: Float) {
    // Backwards: unloading removes the element from the list being walked
    for (i in residentChunks.indices.reversed()) {
      val chunk = residentChunks[i]
      if (chunkDistSq(chunk.cx, chunk.cz, px, pz) > unloadRadiusSq) unloadChunk(chunk)
    }
  }

  private fun rebuildLoadQueue(px: Float, pz: Float, playerChunkX: Int, playerChunkZ: Int) {
    loadQueue.clear()
    val window = LOAD_RADIUS_CHUNKS + 1
    val candidates = ArrayList<QueueEntry>()
    for (cz in (playerChunkZ - window).coerceAtLeast(0)..(playerChunkZ + window).coerceAtMost(chunksZ - 1)) {
      for (cx in (playerChunkX - window).coerceAtLeast(0)..(playerChunkX + window).coerceAtMost(chunksX - 1)) {
        if (chunkAt(cx, cz) != null) continue
        val distSq = chunkDistSq(cx, cz, px, pz)
        if (distSq <= loadRadiusSq) candidates.add(QueueEntry(Chunk.key(cx, cz), distSq))
      }
    }
    candidates.sortBy { it.distSq }
    for (entry in candidates) loadQueue.addLast(entry.key)
  }

  /** Generates and meshes the nearest queued chunk. Returns false when nothing was pending. */
  private fun loadNextQueuedChunk(): Boolean {
    while (loadQueue.isNotEmpty()) {
      val key = loadQueue.removeFirst()
      val cx = Chunk.keyX(key)
      val cz = Chunk.keyZ(key)
      if (chunkAt(cx, cz) != null) continue
      loadChunk(cx, cz)
      return true
    }
    return false
  }

  private fun loadChunk(cx: Int, cz: Int) {
    val key = Chunk.key(cx, cz)
    val chunk = Chunk(cx, cz, terrainMaterial)
    generateChunk(chunk)
    deltas.applyTo(key, chunk.density)
    // Registered before meshing so the chunk's own border samples resolve to it, not to the
    // analytic fallback. Loaded neighbours stay valid: the fallback returns exactly these values.
    chunks[key] = chunk
    residentChunks.add(chunk)
    flushLookupCache()
    rebuildChunk(chunk)
    for (listener in listeners) listener.onChunkLoaded(chunk)
  }

  private fun unloadChunk(chunk: Chunk) {
    for (listener in listeners) listener.onChunkUnloaded(chunk)
    chunks.remove(Chunk.key(chunk.cx, chunk.cz))
    residentChunks.remove(chunk)
    flushLookupCache()
    chunk.dispose()
  }

  // --- Generation ---------------------------------------------------------------------------

  private fun generateChunk(chunk: Chunk) {
    val baseGx = chunk.cx * Chunk.SIZE_X
    val baseGz = chunk.cz * Chunk.SIZE_Z
    var maxSurface = -Float.MAX_VALUE
    for (lz in 0..Chunk.SIZE_Z) {
      val wz = minZ + (baseGz + lz) * voxelSize
      for (lx in 0..Chunk.SIZE_X) {
        val wx = minX + (baseGx + lx) * voxelSize
        val surfaceHeight = terrain.heightAt(wx, wz)
        if (surfaceHeight > maxSurface) maxSurface = surfaceHeight
        // The +X / +Z border columns belong to the neighbours: sampled only for the height bound
        if (lx == Chunk.SIZE_X || lz == Chunk.SIZE_Z) continue
        for (gy in 0 until Chunk.GRID_Y) {
          val wy = minY + gy * voxelSize
          // Positive inside earth (below surface), negative in air (above surface)
          chunk.density[Chunk.index(lx, gy, lz)] = surfaceHeight - wy
        }
      }
    }
    chunk.maxSurfaceY = maxSurface
  }

  /** Analytic (unedited) terrain density at a grid point, independent of any loaded chunk. */
  private fun terrainDensity(gx: Int, gy: Int, gz: Int): Float =
    terrain.heightAt(minX + gx * voxelSize, minZ + gz * voxelSize) - (minY + gy * voxelSize)

  // --- Density access across chunk borders ----------------------------------------------------

  /**
   * Density at a global grid point. Coordinates are clamped to the world grid. A resident chunk
   * answers from its own array; for a point owned by a chunk that is NOT loaded (streamed out, or
   * not streamed in yet) the answer is rebuilt from the terrain function plus the saved edits,
   * which is exactly what that chunk will contain when it loads. That is what keeps chunk seams
   * closed at the streaming frontier and lets physics/raycasts work over unloaded ground.
   * Points on the far (+X / +Z) world edge have no owning chunk and are always analytic.
   */
  private fun gridDensity(gx: Int, gy: Int, gz: Int): Float {
    val x = gx.coerceIn(0, cellsX)
    val y = gy.coerceIn(0, cellsY)
    val z = gz.coerceIn(0, cellsZ)
    if (x >= cellsX || z >= cellsZ) return terrainDensity(x, y, z)

    val cx = x / Chunk.SIZE_X
    val cz = z / Chunk.SIZE_Z
    val local = Chunk.index(x % Chunk.SIZE_X, y, z % Chunk.SIZE_Z)
    val chunk = chunkAt(cx, cz)
    if (chunk != null) return chunk.density[local]

    val edited = deltas.get(Chunk.key(cx, cz), local)
    return if (edited.isNaN()) terrainDensity(x, y, z) else edited
  }

  private fun computeGridGradientNormal(gx: Int, gy: Int, gz: Int, outIndex: Int) {
    // Outward normal points from solid (>0) to air (<=0), i.e., -grad(density)
    val nx = gridDensity(gx - 1, gy, gz) - gridDensity(gx + 1, gy, gz)
    val ny = gridDensity(gx, gy - 1, gz) - gridDensity(gx, gy + 1, gz)
    val nz = gridDensity(gx, gy, gz - 1) - gridDensity(gx, gy, gz + 1)
    val lenSq = nx * nx + ny * ny + nz * nz
    if (lenSq > 1e-8f) {
      val invLen = 1f / sqrt(lenSq)
      cornerNormalsX[outIndex] = nx * invLen
      cornerNormalsY[outIndex] = ny * invLen
      cornerNormalsZ[outIndex] = nz * invLen
    } else {
      cornerNormalsX[outIndex] = 0f
      cornerNormalsY[outIndex] = 1f
      cornerNormalsZ[outIndex] = 0f
    }
  }

  // --- Sky exposure ---------------------------------------------------------------------------

  /**
   * One downward sweep per grid column of the chunk (plus its +X/+Z border columns) so the mesher
   * can attach an exposure to every vertex. It only reads the column itself, so an edit can change
   * it only for the columns it touches, which the existing dirty-range logic already covers.
   */
  private fun computeSkyExposure(baseGx: Int, baseGz: Int, topIndex: Int) {
    for (lz in 0..Chunk.SIZE_Z) {
      val gz = baseGz + lz
      for (lx in 0..Chunk.SIZE_X) {
        val gx = baseGx + lx
        SkyExposure.fillColumn(
          skyExposure, (lz * (Chunk.SIZE_X + 1) + lx) * Chunk.GRID_Y, topIndex, voxelSize
        ) { gy -> gridDensity(gx, gy, gz) }
      }
    }
  }

  private fun skyAt(lx: Int, gy: Int, lz: Int): Float =
    skyExposure[(lz * (Chunk.SIZE_X + 1) + lx) * Chunk.GRID_Y + gy]

  /**
   * Sky exposure at a world position, 1.0 under open sky and ~0.0 deep underground. Same model the
   * mesher bakes into the vertices (vertical cover only), handy for gameplay such as "am I in a cave".
   */
  fun skyExposureAt(x: Float, y: Float, z: Float): Float {
    val step = voxelSize * 0.5f
    var cover = 0f
    var py = y + step
    while (py < maxY && cover < SkyExposure.COVER_CUTOFF) {
      cover += getDensityAt(x, py, z).coerceIn(0f, 1f) * step
      py += step
    }
    return SkyExposure.exposureForCover(cover)
  }

  /** Height of the original, unedited terrain (what the sea floor and beaches looked like at the start). */
  fun baseTerrainHeightAt(x: Float, z: Float): Float = terrain.heightAt(x, z)

  // --- Meshing -------------------------------------------------------------------------------

  /**
   * Re-runs Marching Cubes over every cell of [chunk]. Geometry is emitted in slices; a slice is
   * flushed *before* the next cell could overflow the 16-bit index range, so nothing is ever
   * truncated no matter how carved up the chunk is.
   */
  private fun rebuildChunk(chunk: Chunk) {
    val baseGx = chunk.cx * Chunk.SIZE_X
    val baseGz = chunk.cz * Chunk.SIZE_Z

    // Nothing is solid above the highest generated surface (excavation only removes earth), so
    // the cell layers above it are skipped: on the 64-level chunk a beach chunk meshes ~20 layers
    val topCell = (floor((chunk.maxSurfaceY - minY) / voxelSize).toInt() + 1).coerceIn(0, cellsY - 1)
    computeSkyExposure(baseGx, baseGz, topCell + 1)

    var vertCount = 0
    var idxCount = 0
    var sliceCount = 0

    var boundMinX = Float.MAX_VALUE
    var boundMinY = Float.MAX_VALUE
    var boundMinZ = Float.MAX_VALUE
    var boundMaxX = -Float.MAX_VALUE
    var boundMaxY = -Float.MAX_VALUE
    var boundMaxZ = -Float.MAX_VALUE

    for (gy in 0..topCell) {
      val wy0 = minY + gy * voxelSize
      val wy1 = wy0 + voxelSize

      for (lz in 0 until Chunk.SIZE_Z) {
        val gz = baseGz + lz
        val wz0 = minZ + gz * voxelSize
        val wz1 = wz0 + voxelSize

        for (lx in 0 until Chunk.SIZE_X) {
          val gx = baseGx + lx
          val wx0 = minX + gx * voxelSize
          val wx1 = wx0 + voxelSize

          // Paul Bourke cube corner layout:
          // 0:(0,0,0), 1:(1,0,0), 2:(1,0,1), 3:(0,0,1),
          // 4:(0,1,0), 5:(1,1,0), 6:(1,1,1), 7:(0,1,1)
          cornerDensities[0] = gridDensity(gx, gy, gz)
          cornerDensities[1] = gridDensity(gx + 1, gy, gz)
          cornerDensities[2] = gridDensity(gx + 1, gy, gz + 1)
          cornerDensities[3] = gridDensity(gx, gy, gz + 1)
          cornerDensities[4] = gridDensity(gx, gy + 1, gz)
          cornerDensities[5] = gridDensity(gx + 1, gy + 1, gz)
          cornerDensities[6] = gridDensity(gx + 1, gy + 1, gz + 1)
          cornerDensities[7] = gridDensity(gx, gy + 1, gz + 1)

          var cubeIndex = 0
          if (cornerDensities[0] > ISO_LEVEL) cubeIndex = cubeIndex or 1
          if (cornerDensities[1] > ISO_LEVEL) cubeIndex = cubeIndex or 2
          if (cornerDensities[2] > ISO_LEVEL) cubeIndex = cubeIndex or 4
          if (cornerDensities[3] > ISO_LEVEL) cubeIndex = cubeIndex or 8
          if (cornerDensities[4] > ISO_LEVEL) cubeIndex = cubeIndex or 16
          if (cornerDensities[5] > ISO_LEVEL) cubeIndex = cubeIndex or 32
          if (cornerDensities[6] > ISO_LEVEL) cubeIndex = cubeIndex or 64
          if (cornerDensities[7] > ISO_LEVEL) cubeIndex = cubeIndex or 128

          val edgeMask = EDGE_TABLE[cubeIndex]
          if (edgeMask == 0) continue

          // Never truncate: hand the full slice to the GPU and start a fresh one instead
          if (vertCount + MAX_CELL_VERTICES > Chunk.MAX_SLICE_VERTICES ||
            idxCount + MAX_CELL_INDICES > Chunk.MAX_SLICE_INDICES
          ) {
            chunk.uploadSlice(sliceCount++, scratchVertexBuffer, vertCount, scratchIndexBuffer, idxCount)
            vertCount = 0
            idxCount = 0
          }

          cornerPositionsX[0] = wx0; cornerPositionsY[0] = wy0; cornerPositionsZ[0] = wz0
          cornerPositionsX[1] = wx1; cornerPositionsY[1] = wy0; cornerPositionsZ[1] = wz0
          cornerPositionsX[2] = wx1; cornerPositionsY[2] = wy0; cornerPositionsZ[2] = wz1
          cornerPositionsX[3] = wx0; cornerPositionsY[3] = wy0; cornerPositionsZ[3] = wz1
          cornerPositionsX[4] = wx0; cornerPositionsY[4] = wy1; cornerPositionsZ[4] = wz0
          cornerPositionsX[5] = wx1; cornerPositionsY[5] = wy1; cornerPositionsZ[5] = wz0
          cornerPositionsX[6] = wx1; cornerPositionsY[6] = wy1; cornerPositionsZ[6] = wz1
          cornerPositionsX[7] = wx0; cornerPositionsY[7] = wy1; cornerPositionsZ[7] = wz1

          cornerSky[0] = skyAt(lx, gy, lz)
          cornerSky[1] = skyAt(lx + 1, gy, lz)
          cornerSky[2] = skyAt(lx + 1, gy, lz + 1)
          cornerSky[3] = skyAt(lx, gy, lz + 1)
          cornerSky[4] = skyAt(lx, gy + 1, lz)
          cornerSky[5] = skyAt(lx + 1, gy + 1, lz)
          cornerSky[6] = skyAt(lx + 1, gy + 1, lz + 1)
          cornerSky[7] = skyAt(lx, gy + 1, lz + 1)

          computeGridGradientNormal(gx, gy, gz, 0)
          computeGridGradientNormal(gx + 1, gy, gz, 1)
          computeGridGradientNormal(gx + 1, gy, gz + 1, 2)
          computeGridGradientNormal(gx, gy, gz + 1, 3)
          computeGridGradientNormal(gx, gy + 1, gz, 4)
          computeGridGradientNormal(gx + 1, gy + 1, gz, 5)
          computeGridGradientNormal(gx + 1, gy + 1, gz + 1, 6)
          computeGridGradientNormal(gx, gy + 1, gz + 1, 7)

          for (edge in 0 until 12) {
            if ((edgeMask and (1 shl edge)) != 0) {
              val c0 = EDGE_CORNER_A[edge]
              val c1 = EDGE_CORNER_B[edge]
              val d0 = cornerDensities[c0]
              val d1 = cornerDensities[c1]
              val denom = d1 - d0
              val t = if (abs(denom) > 1e-6f) {
                ((ISO_LEVEL - d0) / denom).coerceIn(0f, 1f)
              } else {
                0.5f
              }

              val px = cornerPositionsX[c0] + t * (cornerPositionsX[c1] - cornerPositionsX[c0])
              val py = cornerPositionsY[c0] + t * (cornerPositionsY[c1] - cornerPositionsY[c0])
              val pz = cornerPositionsZ[c0] + t * (cornerPositionsZ[c1] - cornerPositionsZ[c0])

              var nx = cornerNormalsX[c0] + t * (cornerNormalsX[c1] - cornerNormalsX[c0])
              var ny = cornerNormalsY[c0] + t * (cornerNormalsY[c1] - cornerNormalsY[c0])
              var nz = cornerNormalsZ[c0] + t * (cornerNormalsZ[c1] - cornerNormalsZ[c0])
              val nLenSq = nx * nx + ny * ny + nz * nz
              if (nLenSq > 1e-8f) {
                val inv = 1f / sqrt(nLenSq)
                nx *= inv
                ny *= inv
                nz *= inv
              } else {
                nx = 0f; ny = 1f; nz = 0f
              }

              val base = vertCount * Chunk.VERTEX_FLOATS
              scratchVertexBuffer[base] = px
              scratchVertexBuffer[base + 1] = py
              scratchVertexBuffer[base + 2] = pz
              scratchVertexBuffer[base + 3] = nx
              scratchVertexBuffer[base + 4] = ny
              scratchVertexBuffer[base + 5] = nz
              scratchVertexBuffer[base + 6] = cornerSky[c0] + t * (cornerSky[c1] - cornerSky[c0])

              if (px < boundMinX) boundMinX = px
              if (py < boundMinY) boundMinY = py
              if (pz < boundMinZ) boundMinZ = pz
              if (px > boundMaxX) boundMaxX = px
              if (py > boundMaxY) boundMaxY = py
              if (pz > boundMaxZ) boundMaxZ = pz

              edgeVertexIndices[edge] = vertCount.toShort()
              vertCount++
            }
          }

          val triRow = TRI_TABLE[cubeIndex]
          var i = 0
          while (triRow[i] != -1) {
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i]]
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i + 1]]
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i + 2]]
            i += 3
          }
        }
      }
    }

    if (idxCount > 0 && vertCount > 0) {
      chunk.uploadSlice(sliceCount++, scratchVertexBuffer, vertCount, scratchIndexBuffer, idxCount)
    }
    chunk.finishRebuild(sliceCount, boundMinX, boundMinY, boundMinZ, boundMaxX, boundMaxY, boundMaxZ)
  }

  // --- Rendering -----------------------------------------------------------------------------

  /**
   * Draws every chunk whose surface bounds intersect [frustum]. Returns how many chunks were
   * submitted, which is handy for debugging the culling.
   */
  fun renderVisible(
    batch: ModelBatch,
    environment: Environment,
    shader: Shader,
    frustum: Frustum
  ): Int {
    var drawn = 0
    for (i in 0 until residentChunks.size) {
      val chunk = residentChunks[i]
      if (!chunk.hasGeometry) continue
      if (!frustum.boundsInFrustum(chunk.bounds)) continue
      batch.render(chunk, environment, shader)
      drawn++
    }
    return drawn
  }

  // --- Excavation ----------------------------------------------------------------------------

  /**
   * Subtracts density inside a 3D sphere around [center] to carve caves and horizontal tunnels,
   * rebuilding only the chunks whose meshes can change (including neighbours across borders).
   */
  fun excavateSphere(center: Vector3, radius: Float, strength: Float) {
    if (radius <= 0f || strength <= 0f) return

    // Leave gy=0 intact as bedrock floor and keep the outer world boundary sealed
    val minGx = floor((center.x - radius - minX) / voxelSize).toInt().coerceIn(1, cellsX - 1)
    val maxGx = ceil((center.x + radius - minX) / voxelSize).toInt().coerceIn(1, cellsX - 1)
    val minGy = floor((center.y - radius - minY) / voxelSize).toInt().coerceIn(1, cellsY - 1)
    val maxGy = ceil((center.y + radius - minY) / voxelSize).toInt().coerceIn(1, cellsY - 1)
    val minGz = floor((center.z - radius - minZ) / voxelSize).toInt().coerceIn(1, cellsZ - 1)
    val maxGz = ceil((center.z + radius - minZ) / voxelSize).toInt().coerceIn(1, cellsZ - 1)

    val radiusSq = radius * radius
    var modified = false

    for (gy in minGy..maxGy) {
      val wy = minY + gy * voxelSize
      val dy = wy - center.y
      val dySq = dy * dy

      for (gz in minGz..maxGz) {
        val wz = minZ + gz * voxelSize
        val dz = wz - center.z
        val dyzSq = dySq + dz * dz

        for (gx in minGx..maxGx) {
          val wx = minX + gx * voxelSize
          val dx = wx - center.x
          val distSq = dx * dx + dyzSq

          if (distSq < radiusSq) {
            // smoothstep falloff: 1 at the centre -> 0 at the rim, zero slope at both ends so the
            // carved wall blends into the untouched terrain without a crease
            val t = sqrt(distSq) / radius
            val falloff = 1f - t * t * (3f - 2f * t)
            val chunkKey = Chunk.key(gx / Chunk.SIZE_X, gz / Chunk.SIZE_Z)
            val local = Chunk.index(gx % Chunk.SIZE_X, gy, gz % Chunk.SIZE_Z)
            val updated = (gridDensity(gx, gy, gz) - strength * falloff).coerceAtLeast(-MAX_DENSITY)
            // Written to the resident chunk (if any) AND to the persistent deltas, so the edit
            // survives the chunk being streamed out and also works on chunks not loaded yet
            chunkAt(gx / Chunk.SIZE_X, gz / Chunk.SIZE_Z)?.let { it.density[local] = updated }
            deltas.put(chunkKey, local, updated)
            modified = true
          }
        }
      }
    }

    if (!modified) return

    // A cell's mesh depends on its 8 corners plus their central-difference normals (+-1 sample),
    // so cells in [minG - 2, maxG + 1] can change. That range may cross into neighbour chunks.
    val dirtyMinCellX = (minGx - 2).coerceAtLeast(0)
    val dirtyMaxCellX = (maxGx + 1).coerceAtMost(cellsX - 1)
    val dirtyMinCellZ = (minGz - 2).coerceAtLeast(0)
    val dirtyMaxCellZ = (maxGz + 1).coerceAtMost(cellsZ - 1)

    val minCx = (dirtyMinCellX / Chunk.SIZE_X).coerceIn(0, chunksX - 1)
    val maxCx = (dirtyMaxCellX / Chunk.SIZE_X).coerceIn(0, chunksX - 1)
    val minCz = (dirtyMinCellZ / Chunk.SIZE_Z).coerceIn(0, chunksZ - 1)
    val maxCz = (dirtyMaxCellZ / Chunk.SIZE_Z).coerceIn(0, chunksZ - 1)

    for (cz in minCz..maxCz) {
      for (cx in minCx..maxCx) {
        // Unloaded chunks need no remesh: they are built from the deltas when they load
        chunkAt(cx, cz)?.let { rebuildChunk(it) }
      }
    }
  }

  // --- Queries -------------------------------------------------------------------------------

  /**
   * Trilinear O(1) density interpolation at world coordinates (x, y, z).
   * Returns > 0 for solid ground, <= 0 for air.
   */
  fun getDensityAt(x: Float, y: Float, z: Float): Float {
    val lx = ((x - minX) / voxelSize).coerceIn(0f, cellsX - 0.0001f)
    val ly = ((y - minY) / voxelSize).coerceIn(0f, cellsY - 0.0001f)
    val lz = ((z - minZ) / voxelSize).coerceIn(0f, cellsZ - 0.0001f)

    val gx = lx.toInt()
    val gy = ly.toInt()
    val gz = lz.toInt()

    val tx = lx - gx
    val ty = ly - gy
    val tz = lz - gz

    val d000 = gridDensity(gx, gy, gz)
    val d100 = gridDensity(gx + 1, gy, gz)
    val d010 = gridDensity(gx, gy + 1, gz)
    val d110 = gridDensity(gx + 1, gy + 1, gz)
    val d001 = gridDensity(gx, gy, gz + 1)
    val d101 = gridDensity(gx + 1, gy, gz + 1)
    val d011 = gridDensity(gx, gy + 1, gz + 1)
    val d111 = gridDensity(gx + 1, gy + 1, gz + 1)

    val c00 = d000 + tx * (d100 - d000)
    val c10 = d010 + tx * (d110 - d010)
    val c01 = d001 + tx * (d101 - d001)
    val c11 = d011 + tx * (d111 - d011)

    val c0 = c00 + ty * (c10 - c00)
    val c1 = c01 + ty * (c11 - c01)

    return c0 + tz * (c1 - c0)
  }

  /**
   * Finds the highest air-to-solid surface Y at (x, z) and adds +2.0m clearance
   * so the player always spawns safely above the terrain.
   */
  fun findSpawnY(x: Float, z: Float): Float {
    var prevY = maxY - 0.1f
    var y = prevY - 0.25f
    while (y >= minY) {
      if (getDensityAt(x, y, z) > ISO_LEVEL) {
        var low = y
        var high = prevY
        repeat(6) {
          val mid = (low + high) * 0.5f
          if (getDensityAt(x, mid, z) > ISO_LEVEL) low = mid else high = mid
        }
        return high + 2.0f
      }
      prevY = y
      y -= 0.25f
    }
    return 4.0f
  }

  /**
   * Picks a spawn on the lowlands as close to the island centre as possible: walks outward in rings
   * until the terrain is above [seaLevel] but below the mountains, then lets [findSpawnY] settle the height.
   * Writes the result (x, feet-clearance y, z) into [out].
   */
  fun findDrySpawn(out: Vector3): Vector3 {
    var ring = 0f
    while (ring <= islandRadius) {
      val samples = if (ring == 0f) 1 else (ring * 0.8f).toInt().coerceAtLeast(8)
      for (i in 0 until samples) {
        val angle = (2.0 * PI * i / samples).toFloat()
        val x = centerX + cos(angle) * ring
        val z = centerZ + sin(angle) * ring
        if (isDryLand(x, z)) {
          return out.set(x, findSpawnY(x, z), z)
        }
      }
      ring += SPAWN_SEARCH_STEP
    }
    return out.set(centerX, findSpawnY(centerX, centerZ), centerZ)
  }

  private fun isDryLand(x: Float, z: Float): Boolean {
    val h = terrain.heightAt(x, z)
    // Comfortably above the water, but on the lowlands: not up on the mountains
    if (h < seaLevel + SPAWN_MIN_HEIGHT_ABOVE_SEA || h > seaLevel + SPAWN_MAX_HEIGHT_ABOVE_SEA) return false
    // Reject steep spots so the player does not start on a cliff face
    val d = SPAWN_SLOPE_PROBE
    return abs(terrain.heightAt(x + d, z) - h) < SPAWN_MAX_SLOPE_DELTA &&
      abs(terrain.heightAt(x - d, z) - h) < SPAWN_MAX_SLOPE_DELTA &&
      abs(terrain.heightAt(x, z + d) - h) < SPAWN_MAX_SLOPE_DELTA &&
      abs(terrain.heightAt(x, z - d) - h) < SPAWN_MAX_SLOPE_DELTA
  }

  /**
   * 3D volumetric raycast against the 0.0f isosurface up to [maxDistance] meters.
   * Uses a coarse 0.18m step followed by 6-step binary search refinement on impact.
   */
  fun raycast(
    origin: Vector3,
    direction: Vector3,
    maxDistance: Float = 3.5f,
    outHit: Vector3
  ): Boolean {
    val step = 0.18f
    var prevT = 0f
    var t = step

    while (t <= maxDistance + 0.0001f) {
      val clampedT = t.coerceAtMost(maxDistance)
      val px = origin.x + direction.x * clampedT
      val py = origin.y + direction.y * clampedT
      val pz = origin.z + direction.z * clampedT

      if (px in minX..maxX && py in minY..maxY && pz in minZ..maxZ) {
        if (getDensityAt(px, py, pz) > ISO_LEVEL) {
          var low = prevT
          var high = clampedT
          repeat(6) {
            val mid = (low + high) * 0.5f
            val mx = origin.x + direction.x * mid
            val my = origin.y + direction.y * mid
            val mz = origin.z + direction.z * mid
            if (getDensityAt(mx, my, mz) > ISO_LEVEL) {
              high = mid
            } else {
              low = mid
            }
          }
          val hitT = (low + high) * 0.5f
          outHit.set(
            origin.x + direction.x * hitT,
            origin.y + direction.y * hitT,
            origin.z + direction.z * hitT
          )
          return true
        }
      }

      prevT = clampedT
      t += step
    }

    return false
  }

  fun updateAimIndicator(hitPoint: Vector3) {
    getSurfaceNormalAt(hitPoint.x, hitPoint.y, hitPoint.z, tempNormal)
    tempQuat.setFromCross(Vector3.Y, tempNormal)
    tempPos.set(
      hitPoint.x + tempNormal.x * 0.05f,
      hitPoint.y + tempNormal.y * 0.05f,
      hitPoint.z + tempNormal.z * 0.05f
    )
    aimIndicatorInstance.transform.set(tempPos, tempQuat)
  }

  private fun getSurfaceNormalAt(x: Float, y: Float, z: Float, out: Vector3) {
    val eps = 0.25f
    val nx = getDensityAt(x - eps, y, z) - getDensityAt(x + eps, y, z)
    val ny = getDensityAt(x, y - eps, z) - getDensityAt(x, y + eps, z)
    val nz = getDensityAt(x, y, z - eps) - getDensityAt(x, y, z + eps)
    out.set(nx, ny, nz)
    if (out.len2() > 1e-8f) {
      out.nor()
    } else {
      out.set(Vector3.Y)
    }
  }

  private fun createAimIndicatorModel(): Model {
    val attributes = VertexAttributes(VertexAttribute.Position())
    val meshBuilder = MeshBuilder()
    meshBuilder.begin(attributes, GL20.GL_TRIANGLES)

    val segments = 32
    val innerRadius = 0.20f
    val outerRadius = 0.26f
    val twoPi = (PI * 2.0).toFloat()

    for (i in 0 until segments) {
      val a0 = twoPi * i / segments
      val a1 = twoPi * (i + 1) / segments
      val cos0 = cos(a0)
      val sin0 = sin(a0)
      val cos1 = cos(a1)
      val sin1 = sin(a1)

      val vIn0 = meshBuilder.vertex(Vector3(cos0 * innerRadius, 0f, sin0 * innerRadius), null, null, null)
      val vOut0 = meshBuilder.vertex(Vector3(cos0 * outerRadius, 0f, sin0 * outerRadius), null, null, null)
      val vIn1 = meshBuilder.vertex(Vector3(cos1 * innerRadius, 0f, sin1 * innerRadius), null, null, null)
      val vOut1 = meshBuilder.vertex(Vector3(cos1 * outerRadius, 0f, sin1 * outerRadius), null, null, null)

      meshBuilder.triangle(vIn0, vIn1, vOut0)
      meshBuilder.triangle(vOut0, vIn1, vOut1)
    }

    val dotRadius = 0.045f
    val centerVertex = meshBuilder.vertex(Vector3(0f, 0f, 0f), null, null, null)
    val dotSegments = 12
    for (i in 0 until dotSegments) {
      val a0 = twoPi * i / dotSegments
      val a1 = twoPi * (i + 1) / dotSegments
      val v0 = meshBuilder.vertex(Vector3(cos(a0) * dotRadius, 0f, sin(a0) * dotRadius), null, null, null)
      val v1 = meshBuilder.vertex(Vector3(cos(a1) * dotRadius, 0f, sin(a1) * dotRadius), null, null, null)
      meshBuilder.triangle(centerVertex, v1, v0)
    }

    val ringMesh = meshBuilder.end()
    val material = Material(
      ColorAttribute.createDiffuse(Color(0.98f, 0.86f, 0.30f, 1f)),
      IntAttribute.createCullFace(GL20.GL_NONE)
    )

    val modelBuilder = ModelBuilder()
    modelBuilder.begin()
    modelBuilder.part("aim_ring", ringMesh, GL20.GL_TRIANGLES, 0, ringMesh.numIndices, material)
    return modelBuilder.end()
  }

  override fun dispose() {
    for (chunk in residentChunks) chunk.dispose()
    residentChunks.clear()
    chunks.clear()
    flushLookupCache()
    loadQueue.clear()
    aimModel.dispose()
  }

  companion object {
    /** 500 m radius = a 1 km island. */
    const val DEFAULT_ISLAND_RADIUS = IslandTerrain.REFERENCE_RADIUS

    /**
     * Chunks within this many chunks (16 m each, ~112 m) of the player stay resident, in all
     * directions. Raised from 5 so that, from the hills, enough land is visible before the fog
     * takes over; see [solidTerrainRadius].
     */
    const val LOAD_RADIUS_CHUNKS = 7

    /**
     * Extra margin before a loaded chunk is released, so walking back and forth along the edge of
     * the radius does not dispose and regenerate the same chunk over and over.
     */
    const val UNLOAD_HYSTERESIS_CHUNKS = 1

    /** Default synchronous window (in chunks around the spawn) for [prewarm]. */
    const val PREWARM_RADIUS_CHUNKS = 1
    const val ISO_LEVEL = VoxelVolumeMesh.ISO_LEVEL

    /** World Y of the sea surface; the island fades to the sea floor below it. */
    const val SEA_LEVEL = 0f

    /** Open sea floor kept around the island, in metres, before rounding up to whole chunks. */
    private const val WORLD_MARGIN = 8f
    private const val MAX_DENSITY = 12.0f

    // Worst case a single Marching Cubes cell emits 12 edge vertices and 5 triangles (15 indices)
    private const val MAX_CELL_VERTICES = 12
    private const val MAX_CELL_INDICES = 15

    private const val LOOKUP_CACHE_SIZE = 32 // power of two; see chunkAt()
    private const val NO_KEY = Long.MIN_VALUE

    private const val SPAWN_SEARCH_STEP = 4f
    private const val SPAWN_MIN_HEIGHT_ABOVE_SEA = 2.0f
    private const val SPAWN_MAX_HEIGHT_ABOVE_SEA = 7.0f
    private const val SPAWN_SLOPE_PROBE = 1.5f
    private const val SPAWN_MAX_SLOPE_DELTA = 1.6f

    private val EDGE_TABLE = VoxelVolumeMesh.EDGE_TABLE
    private val TRI_TABLE = VoxelVolumeMesh.TRI_TABLE
    private val EDGE_CORNER_A = VoxelVolumeMesh.EDGE_CORNER_A
    private val EDGE_CORNER_B = VoxelVolumeMesh.EDGE_CORNER_B
  }
}

/**
 * Hook for per-chunk content (trees, rocks, rivers...) that must appear and disappear with the
 * terrain. Both callbacks run on the render thread inside [ChunkManager.update]/[ChunkManager.prewarm];
 * [onChunkLoaded] fires after the terrain mesh exists, [onChunkUnloaded] just before it is disposed.
 */
interface ChunkListener {
  fun onChunkLoaded(chunk: Chunk)
  fun onChunkUnloaded(chunk: Chunk)
}

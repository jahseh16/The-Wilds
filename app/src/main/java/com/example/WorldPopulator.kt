package com.example

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.RenderableProvider
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.math.Frustum
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Pool
import com.badlogic.gdx.utils.Array as GdxArray
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grows the ecosystem of the island as the terrain streams in: it is a [ChunkListener], so every
 * chunk that [ChunkManager] loads gets its trees, shrubs, boulders, pebbles, sticks and ground cover
 * seeded deterministically by [WorldScatter] and merged into two meshes (one per [Layer]) by
 * [ChunkScatterMesh]: a chunk's whole ecosystem costs two draw calls, culled with the chunk's frustum bounds.
 * Trees and boulders farther than [LOD_RADIUS] use ~530 / 300 triangle stand-ins instead of 2200 / 1200.
 *
 * Work is budgeted: at most one chunk is (re)built per [update] call, nearest first. The "far" content
 * (trees, shrubs, boulders, beach patches) is built when the chunk loads; the "near" content (grass,
 * ferns, weeds, flowers, pebbles, sticks) is added when the chunk comes within [NEAR_RADIUS] of the
 * player and dropped again beyond [NEAR_DROP_RADIUS], so only ~30 chunks carry it.
 *
 * It also owns the simple collision for tree trunks and big boulders (vertical cylinders).
 */
class WorldPopulator(
  private val world: ChunkManager,
  private val models: ScatterModels,
  private val solidAtlas: Texture,
  private val cutoutAtlas: Texture,
  private val clearings: List<Clearing>
) : ChunkListener, Disposable {

  private inner class Props(val cx: Int, val cz: Int) : RenderableProvider {
    var far: ArrayList<Placement>? = null
    var near: ArrayList<Placement>? = null
    var builtNear = false
    var builtLod = false
    var built = false
    var solidMesh: Mesh? = null
    var cutoutMesh: Mesh? = null
    val solid = Renderable()
    val cutout = Renderable()
    val bounds = BoundingBox()
    var hasGeometry = false
    /** x, z, radius, bottomY, topY per cylinder. */
    var colliders = FloatArray(0)
    var instances = 0
    var triangles = 0

    override fun getRenderables(renderables: GdxArray<Renderable>, pool: Pool<Renderable>) {
      if (solidMesh != null) renderables.add(solid)
      if (cutoutMesh != null) renderables.add(cutout)
    }

    fun disposeMeshes() {
      solidMesh?.dispose(); solidMesh = null
      cutoutMesh?.dispose(); cutoutMesh = null
    }
  }

  private val byKey = HashMap<Long, Props>()
  private val all = ArrayList<Props>()
  private val chunkSize = Chunk.SIZE_X * world.voxelSize

  private val solidMaterial = Material(FoliageShader.LAYER_SOLID, TextureAttribute.createDiffuse(solidAtlas))
  private val cutoutMaterial = Material(FoliageShader.LAYER_CUTOUT, TextureAttribute.createDiffuse(cutoutAtlas))

  /** Resident chunks that have content, and the instances / triangles drawn by them (debug / budgeting). */
  var instanceCount = 0
    private set
  var triangleCount = 0
    private set
  var drawCalls = 0
    private set

  private var lastPlayerX = 0f
  private var lastPlayerZ = 0f

  // --- ChunkListener ---------------------------------------------------------------------------

  override fun onChunkLoaded(chunk: Chunk) {
    val props = Props(chunk.cx, chunk.cz)
    byKey[Chunk.key(chunk.cx, chunk.cz)] = props
    all.add(props)
  }

  override fun onChunkUnloaded(chunk: Chunk) {
    val props = byKey.remove(Chunk.key(chunk.cx, chunk.cz)) ?: return
    all.remove(props)
    props.disposeMeshes()
    instanceCount -= props.instances
    triangleCount -= props.triangles
  }

  // --- Per-frame work ----------------------------------------------------------------------------

  /** Builds (at most) one chunk's meshes: the nearest one whose content is missing or out of date. */
  fun update(playerPos: Vector3) {
    lastPlayerX = playerPos.x
    lastPlayerZ = playerPos.z
    var best: Props? = null
    var bestDist = Float.MAX_VALUE
    var bestWantNear = false
    var bestWantLod = false
    for (p in all) {
      val d = distSq(p, playerPos.x, playerPos.z)
      val wantNear = if (p.builtNear) d <= NEAR_DROP_RADIUS * NEAR_DROP_RADIUS else d <= NEAR_RADIUS * NEAR_RADIUS
      val wantLod = if (p.builtLod) d > LOD_BACK_RADIUS * LOD_BACK_RADIUS else d > LOD_RADIUS * LOD_RADIUS
      if (p.built && wantNear == p.builtNear && wantLod == p.builtLod) continue
      if (d < bestDist) { best = p; bestDist = d; bestWantNear = wantNear; bestWantLod = wantLod }
    }
    if (best != null) build(best, bestWantNear, bestWantLod)
  }

  private fun distSq(p: Props, x: Float, z: Float): Float {
    val dx = (p.cx + 0.5f) * chunkSize - x
    val dz = (p.cz + 0.5f) * chunkSize - z
    return dx * dx + dz * dz
  }

  private fun build(p: Props, wantNear: Boolean, wantLod: Boolean) {
    val far = p.far ?: ArrayList<Placement>().also {
      WorldScatter.planFar(world.terrain, p.cx, p.cz, chunkSize, clearings, it)
      p.far = it
      p.colliders = buildColliders(it)
    }
    val list = ArrayList<Placement>(far.size + 256)
    list.addAll(far)
    if (wantNear) {
      val near = p.near ?: ArrayList<Placement>().also {
        WorldScatter.planNear(world.terrain, p.cx, p.cz, chunkSize, clearings, it)
        p.near = it
      }
      list.addAll(near)
    } else {
      p.near = null
    }

    instanceCount -= p.instances
    triangleCount -= p.triangles
    p.disposeMeshes()

    val (solid, cutout) = ChunkScatterMesh.build(list, models, wantLod)
    p.solidMesh = solid?.let { toMesh(it) }
    p.cutoutMesh = cutout?.let { toMesh(it) }
    configure(p.solid, solidMaterial, p.solidMesh, solid)
    configure(p.cutout, cutoutMaterial, p.cutoutMesh, cutout)

    p.bounds.inf()
    for (layer in listOf(solid, cutout)) {
      if (layer == null) continue
      p.bounds.ext(layer.minX - BOUNDS_PAD, layer.minY - BOUNDS_PAD, layer.minZ - BOUNDS_PAD)
      p.bounds.ext(layer.maxX + BOUNDS_PAD, layer.maxY + BOUNDS_PAD, layer.maxZ + BOUNDS_PAD)
    }
    p.hasGeometry = solid != null || cutout != null
    p.instances = list.size
    p.triangles = ((solid?.indexCount ?: 0) + (cutout?.indexCount ?: 0)) / 3
    instanceCount += p.instances
    triangleCount += p.triangles
    p.built = true
    p.builtNear = wantNear
    p.builtLod = wantLod
  }

  private fun toMesh(data: LayerData): Mesh {
    val mesh = Mesh(
      true, data.vertexCount, data.indexCount,
      VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0),
      VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_sway"),
      VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_near")
    )
    mesh.setVertices(data.vertices, 0, data.vertexCount * ChunkScatterMesh.FLOATS)
    mesh.setIndices(data.indices, 0, data.indexCount)
    return mesh
  }

  private fun configure(renderable: Renderable, material: Material, mesh: Mesh?, data: LayerData?) {
    if (mesh == null || data == null) return
    renderable.material = material
    renderable.meshPart.set("scatter", mesh, 0, data.indexCount, GL20.GL_TRIANGLES)
    renderable.worldTransform.idt()
    val cx = (data.minX + data.maxX) * 0.5f; val cy = (data.minY + data.maxY) * 0.5f; val cz = (data.minZ + data.maxZ) * 0.5f
    renderable.meshPart.center.set(cx, cy, cz)
    renderable.meshPart.halfExtents.set((data.maxX - data.minX) * 0.5f, (data.maxY - data.minY) * 0.5f, (data.maxZ - data.minZ) * 0.5f)
    renderable.meshPart.radius = renderable.meshPart.halfExtents.len()
  }

  /** Draws every chunk's ecosystem that intersects [frustum]. Returns the number of draw calls issued. */
  fun render(batch: ModelBatch, environment: Environment, shader: Shader, frustum: Frustum): Int {
    var calls = 0
    for (p in all) {
      if (!p.hasGeometry || !frustum.boundsInFrustum(p.bounds)) continue
      batch.render(p, environment, shader)
      calls += (if (p.solidMesh != null) 1 else 0) + (if (p.cutoutMesh != null) 1 else 0)
    }
    drawCalls = calls
    return calls
  }

  // --- Collision -----------------------------------------------------------------------------------

  private fun buildColliders(placements: List<Placement>): FloatArray {
    val out = ArrayList<Float>()
    for (p in placements) {
      val cos = cos(p.yaw); val sin = sin(p.yaw)
      when (p.kind) {
        Kind.TREE -> {
          val ox = (cos * models.trunkCenterX + sin * models.trunkCenterZ) * p.scale
          val oz = (-sin * models.trunkCenterX + cos * models.trunkCenterZ) * p.scale
          out.add(p.x + ox); out.add(p.z + oz)
          out.add(models.trunkRadius * p.scale * TRUNK_COLLIDER_SHRINK)
          out.add(p.y); out.add(p.y + TREE_COLLIDER_HEIGHT * p.scale)
        }
        Kind.ROCK -> {
          out.add(p.x); out.add(p.z)
          out.add(models.rockRadius * p.scale)
          out.add(p.y - 0.12f * models.rockHeight * p.scale); out.add(p.y + 0.88f * models.rockHeight * p.scale)
        }
        else -> Unit
      }
    }
    return out.toFloatArray()
  }

  /**
   * True if a body at ([toX], [toZ]) with [bodyRadius] and feet at [feetY] (height [bodyHeight])
   * would be inside a trunk or boulder it was not already overlapping: moving out of a collider (or
   * along its surface without getting closer) is always allowed, so nobody can get stuck.
   */
  fun blocks(fromX: Float, fromZ: Float, toX: Float, toZ: Float, feetY: Float, bodyHeight: Float, bodyRadius: Float): Boolean {
    val cx0 = floor(toX / chunkSize).toInt()
    val cz0 = floor(toZ / chunkSize).toInt()
    for (cz in cz0 - 1..cz0 + 1) {
      for (cx in cx0 - 1..cx0 + 1) {
        val p = byKey[Chunk.key(cx, cz)] ?: continue
        val c = p.colliders
        var i = 0
        while (i < c.size) {
          val x = c[i]; val z = c[i + 1]; val r = c[i + 2] + bodyRadius
          val y0 = c[i + 3]; val y1 = c[i + 4]
          i += 5
          if (feetY + bodyHeight <= y0 || feetY >= y1 - STEP_UP) continue
          val nx = toX - x; val nz = toZ - z
          val dNew = sqrt(nx * nx + nz * nz)
          if (dNew >= r) continue
          val ox = fromX - x; val oz = fromZ - z
          if (dNew < sqrt(ox * ox + oz * oz)) return true
        }
      }
    }
    return false
  }

  /** Number of chunks that currently carry any content (debug). */
  val populatedChunks: Int get() = all.count { it.hasGeometry }

  override fun dispose() {
    for (p in all) p.disposeMeshes()
    all.clear()
    byKey.clear()
  }

  companion object {
    /** Small ground cover is generated for chunks whose centre is within this many metres of the player. */
    const val NEAR_RADIUS = 42f
    const val NEAR_DROP_RADIUS = 56f

    /** Chunks whose centre is farther than this get the low-triangle trees and boulders (back to full detail inside the second radius). */
    const val LOD_RADIUS = 66f
    const val LOD_BACK_RADIUS = 58f
    private const val BOUNDS_PAD = 0.6f
    private const val TREE_COLLIDER_HEIGHT = 3.2f
    private const val TRUNK_COLLIDER_SHRINK = 0.9f
    private const val STEP_UP = 0.35f
  }
}

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
 * chunk that [ChunkManager] loads gets its pines, shrubs, boulders, pebbles, sticks and ground cover
 * seeded deterministically by [WorldScatter] and merged into one mesh per [Layer] by
 * [ChunkScatterMesh]: a chunk's whole ecosystem costs at most three draw calls (atlas opaque, atlas
 * alpha-tested, pine bark), culled with the chunk's frustum bounds. Pines and boulders farther than
 * [LOD_RADIUS] use ~200 / 300 triangle stand-ins instead of ~1050 / 1200.
 *
 * Felling: every pine has a [Tree] record (its vertex ranges in the merged meshes and its collider).
 * [fell] hides the trunk + crown by collapsing just those vertices in the chunk meshes (no re-meshing:
 * libGDX re-uploads the buffer once on its next bind), lowers the collider to the stump, and remembers the tree id so the chunk
 * comes back with only the stump after streaming out and in again.
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
  /** Pine bark: diffuse + normal map, repeat wrap (see [FoliageShader.LAYER_BARK]). */
  val barkMaterial: Material,
  private val clearings: List<Clearing>
) : ChunkListener, Disposable {

  /** One pine of a chunk: what [fell] needs. */
  inner class Tree(val placement: Placement, val spec: PineSpec, val colliderIndex: Int) {
    val ranges = ArrayList<TreeRange>()
    val x: Float get() = placement.x
    val y: Float get() = placement.y
    val z: Float get() = placement.z
    val scale: Float get() = placement.scale
    val height: Float get() = spec.height * placement.scale
    val trunkRadius: Float get() = spec.baseRadius * placement.scale
    lateinit var owner: Props
      internal set
  }

  inner class Props(val cx: Int, val cz: Int) : RenderableProvider {
    var far: ArrayList<Placement>? = null
    var near: ArrayList<Placement>? = null
    var builtNear = false
    var builtLod = false
    var built = false
    val meshes = arrayOfNulls<Mesh>(Layer.values().size)
    val renderables = Array(Layer.values().size) { Renderable() }
    val bounds = BoundingBox()
    var hasGeometry = false
    /** x, z, radius, bottomY, topY per cylinder. */
    var colliders = FloatArray(0)
    var trees = ArrayList<Tree>()
    var instances = 0
    var triangles = 0

    override fun getRenderables(renderables: GdxArray<Renderable>, pool: Pool<Renderable>) {
      for (i in meshes.indices) if (meshes[i] != null) renderables.add(this.renderables[i])
    }

    val meshCount: Int get() = meshes.count { it != null }

    fun disposeMeshes() {
      for (i in meshes.indices) { meshes[i]?.dispose(); meshes[i] = null }
    }
  }

  private val byKey = HashMap<Long, Props>()
  private val all = ArrayList<Props>()
  private val chunkSize = Chunk.SIZE_X * world.voxelSize

  private val materials = arrayOf(
    Material(FoliageShader.LAYER_SOLID, TextureAttribute.createDiffuse(solidAtlas)),
    Material(FoliageShader.LAYER_CUTOUT, TextureAttribute.createDiffuse(cutoutAtlas)),
    barkMaterial
  )

  /** Ids of the trees chopped down this session (survive chunk streaming). */
  private val felledIds = HashSet<Long>()
  private var zeroVertices = FloatArray(0)

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
      for (pl in it) if (pl.kind.isTree && pl.id in felledIds) pl.felled = true
      p.far = it
      buildColliders(p, it)
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

    val result = ChunkScatterMesh.build(list, models, wantLod)
    p.bounds.inf()
    for (layer in Layer.values()) {
      val data = result.layers[layer]
      val mesh = data?.let { toMesh(it) }
      p.meshes[layer.ordinal] = mesh
      configure(p.renderables[layer.ordinal], materials[layer.ordinal], mesh, data)
      if (data == null) continue
      p.bounds.ext(data.minX - BOUNDS_PAD, data.minY - BOUNDS_PAD, data.minZ - BOUNDS_PAD)
      p.bounds.ext(data.maxX + BOUNDS_PAD, data.maxY + BOUNDS_PAD, data.maxZ + BOUNDS_PAD)
    }
    // Which vertices belong to which pine (trees are the first placements: they come from the far list)
    val byPlacement = HashMap<Placement, Tree>()
    for (t in p.trees) { t.ranges.clear(); byPlacement[t.placement] = t }
    for (r in result.treeRanges) byPlacement[list[r.placementIndex]]?.ranges?.add(r)
    p.hasGeometry = result.layers.values.any { it != null }
    p.instances = list.size
    p.triangles = result.triangleCount
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
      VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_near"),
      VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_ao")
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
      calls += p.meshCount
    }
    drawCalls = calls
    return calls
  }

  // --- Collision -----------------------------------------------------------------------------------

  private fun buildColliders(p: Props, placements: List<Placement>) {
    val out = ArrayList<Float>()
    p.trees = ArrayList()
    for (pl in placements) {
      when {
        pl.kind.isTree -> {
          val spec = models.pineSpec(pl.kind)
          val tree = Tree(pl, spec, out.size).also { it.owner = p }
          p.trees.add(tree)
          out.add(pl.x); out.add(pl.z)
          out.add(spec.baseRadius * pl.scale * TRUNK_COLLIDER_SHRINK)
          out.add(pl.y - PineTreeGenerator.STUMP_BURY)
          out.add(pl.y + if (pl.felled) PineTreeGenerator.STUMP_TOP * pl.scale else spec.height * pl.scale)
        }
        pl.kind == Kind.ROCK -> {
          out.add(pl.x); out.add(pl.z)
          out.add(models.rockRadius * pl.scale)
          out.add(pl.y - 0.12f * models.rockHeight * pl.scale); out.add(pl.y + 0.88f * models.rockHeight * pl.scale)
        }
        else -> Unit
      }
    }
    p.colliders = out.toFloatArray()
  }

  // --- Trees: aiming and felling --------------------------------------------------------------

  /**
   * Nearest standing pine trunk hit by the ray within [maxDistance] (and below [reachHeight] metres
   * above its base, i.e. where an arm can strike). Writes the hit point into [outHit] and returns
   * the tree, or null.
   */
  fun raycastTree(origin: Vector3, direction: Vector3, maxDistance: Float, reachHeight: Float, outHit: Vector3): Tree? {
    val dx = direction.x; val dz = direction.z
    val a = dx * dx + dz * dz
    if (a < 1e-8f) return null
    var best: Tree? = null
    var bestT = maxDistance
    val cx0 = floor(origin.x / chunkSize).toInt(); val cz0 = floor(origin.z / chunkSize).toInt()
    for (cz in cz0 - 1..cz0 + 1) for (cx in cx0 - 1..cx0 + 1) {
      val p = byKey[Chunk.key(cx, cz)] ?: continue
      for (t in p.trees) {
        if (t.placement.felled) continue
        val r = t.trunkRadius * AIM_RADIUS_SCALE
        val ox = origin.x - t.x; val oz = origin.z - t.z
        val b = 2f * (ox * dx + oz * dz)
        val c = ox * ox + oz * oz - r * r
        val disc = b * b - 4f * a * c
        if (disc < 0f) continue
        val hitT = (-b - sqrt(disc)) / (2f * a)
        if (hitT < 0f || hitT >= bestT) continue
        val y = origin.y + direction.y * hitT
        if (y < t.y - 0.3f || y > t.y + minOf(reachHeight, t.height)) continue
        best = t; bestT = hitT
      }
    }
    if (best != null) outHit.set(origin).mulAdd(direction, bestT)
    return best
  }

  /**
   * Chops [tree] down: its trunk and crown disappear from the chunk meshes (their vertices are
   * collapsed to a point, so the triangles become degenerate), its collider shrinks to the stump, and it stays felled for the session.
   */
  fun fell(tree: Tree) {
    if (tree.placement.felled) return
    tree.placement.felled = true
    felledIds.add(tree.placement.id)
    val p = tree.owner
    for (r in tree.ranges) {
      val mesh = p.meshes[r.layer.ordinal] ?: continue
      val n = r.vertexCount * ChunkScatterMesh.FLOATS
      if (zeroVertices.size < n) zeroVertices = FloatArray(n)
      mesh.updateVertices(r.firstVertex * ChunkScatterMesh.FLOATS, zeroVertices, 0, n)
    }
    tree.ranges.clear()
    if (tree.colliderIndex + 4 < p.colliders.size) {
      p.colliders[tree.colliderIndex + 4] = tree.y + PineTreeGenerator.STUMP_TOP * tree.scale
    }
  }

  /** Number of trees felled this session. */
  val felledCount: Int get() = felledIds.size

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
    const val LOD_RADIUS = 52f
    const val LOD_BACK_RADIUS = 45f
    private const val BOUNDS_PAD = 0.6f
    private const val TRUNK_COLLIDER_SHRINK = 0.95f
    /** Aiming at a trunk is a little forgiving (thin trunks on a phone screen). */
    private const val AIM_RADIUS_SCALE = 1.25f
    private const val STEP_UP = 0.35f
  }
}

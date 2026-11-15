package com.example

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.RenderableProvider
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Pool
import com.badlogic.gdx.utils.Array as GdxArray
import kotlin.math.max
import kotlin.math.min

/**
 * A 16x32x16 column of the density field plus the GPU meshes generated from it.
 *
 * Density storage owns one sample per cell *lower corner* (16 x 33 x 16: the 33 levels cover the
 * full height, there is no vertical neighbour). Samples on the +X / +Z faces of the chunk belong to
 * the neighbouring chunk, so [ChunkManager] resolves them across chunk borders; this removes seams
 * and means an edit is written in exactly one place.
 *
 * The surface is split into one or more [MeshSlice]s so that 16-bit indices can never overflow
 * and no geometry is ever silently dropped, however much the player carves the chunk.
 */
class Chunk(
  val cx: Int,
  val cz: Int,
  private val material: Material
) : Disposable, RenderableProvider {

  /** Density samples: > 0 is solid earth, <= 0 is air. */
  val density = FloatArray(SIZE_X * GRID_Y * SIZE_Z)

  /** Tight world-space bounds of the generated surface, used for frustum culling. */
  val bounds = BoundingBox()

  private class MeshSlice(
    val mesh: Mesh,
    val renderable: Renderable,
    val vertexCapacity: Int,
    val indexCapacity: Int
  )

  private val slices = ArrayList<MeshSlice>(1)

  /** Number of slices currently holding geometry (0 = nothing to draw). */
  var activeSlices: Int = 0
    private set

  val hasGeometry: Boolean
    get() = activeSlices > 0

  /**
   * Hands every active slice to the batch. [ModelBatch.render] has no overload taking a bare
   * Renderable plus Environment/Shader, so the chunk is a [RenderableProvider] instead; the
   * persistent per-slice renderables are added directly (nothing is taken from the pool).
   */
  override fun getRenderables(renderables: GdxArray<Renderable>, pool: Pool<Renderable>) {
    for (i in 0 until activeSlices) {
      renderables.add(slices[i].renderable)
    }
  }

  /**
   * Uploads one batch of mesh data into slice [index], growing (re-creating) the GPU buffers only
   * when the existing ones are too small.
   */
  fun uploadSlice(
    index: Int,
    vertices: FloatArray,
    vertexCount: Int,
    indices: ShortArray,
    indexCount: Int
  ) {
    var slice = if (index < slices.size) slices[index] else null
    if (slice == null || slice.vertexCapacity < vertexCount || slice.indexCapacity < indexCount) {
      slice?.mesh?.dispose()
      val vertexCapacity = min(max(vertexCount + vertexCount / 4, MIN_SLICE_VERTICES), MAX_SLICE_VERTICES)
      val indexCapacity = min(max(indexCount + indexCount / 4, MIN_SLICE_INDICES), MAX_SLICE_INDICES)
      val mesh = Mesh(false, vertexCapacity, indexCapacity, ATTRIBUTES)
      val renderable = Renderable().apply {
        material = this@Chunk.material
        meshPart.set("chunk_${cx}_${cz}_$index", mesh, 0, 0, GL20.GL_TRIANGLES)
      }
      slice = MeshSlice(mesh, renderable, vertexCapacity, indexCapacity)
      if (index < slices.size) slices[index] = slice else slices.add(slice)
    }

    slice.mesh.setVertices(vertices, 0, vertexCount * VERTEX_FLOATS)
    slice.mesh.setIndices(indices, 0, indexCount)
    slice.renderable.meshPart.set("chunk_${cx}_${cz}_$index", slice.mesh, 0, indexCount, GL20.GL_TRIANGLES)
  }

  /**
   * Finishes a rebuild: keeps the first [usedSlices] slices, frees the rest, and stores the tight
   * bounds (also used as the sort centre / radius of each renderable).
   */
  fun finishRebuild(
    usedSlices: Int,
    minX: Float, minY: Float, minZ: Float,
    maxX: Float, maxY: Float, maxZ: Float
  ) {
    while (slices.size > usedSlices) {
      slices.removeAt(slices.size - 1).mesh.dispose()
    }
    activeSlices = usedSlices
    if (usedSlices == 0) {
      bounds.clr()
      return
    }

    bounds.set(
      Vector3(minX - BOUNDS_PADDING, minY - BOUNDS_PADDING, minZ - BOUNDS_PADDING),
      Vector3(maxX + BOUNDS_PADDING, maxY + BOUNDS_PADDING, maxZ + BOUNDS_PADDING)
    )
    for (slice in slices) {
      val part = slice.renderable.meshPart
      bounds.getCenter(part.center)
      bounds.getDimensions(part.halfExtents).scl(0.5f)
      part.radius = part.halfExtents.len()
    }
  }

  override fun dispose() {
    for (slice in slices) {
      slice.mesh.dispose()
    }
    slices.clear()
    activeSlices = 0
  }

  companion object {
    const val SIZE_X = 16
    const val SIZE_Y = 32
    const val SIZE_Z = 16
    const val GRID_Y = SIZE_Y + 1

    const val VERTEX_FLOATS = 6 // position (3) + normal (3)

    // Slice limits: vertices are addressed by 16-bit indices, so a slice never exceeds this many.
    // A cell emits at most 12 vertices / 15 indices; the mesher flushes a slice before it could overflow.
    const val MAX_SLICE_VERTICES = 30000
    const val MAX_SLICE_INDICES = 60000
    private const val MIN_SLICE_VERTICES = 512
    private const val MIN_SLICE_INDICES = 1024
    private const val BOUNDS_PADDING = 0.05f

    private val ATTRIBUTES = VertexAttributes(
      VertexAttribute.Position(),
      VertexAttribute.Normal()
    )

    fun index(lx: Int, gy: Int, lz: Int): Int = (gy * SIZE_Z + lz) * SIZE_X + lx
  }
}

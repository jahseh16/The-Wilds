package com.example

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.ceil

/**
 * The freshwater lakes of the peninsula ([IslandTerrain.lakes]), each a flat square of water at its
 * own level drawn with [WaterShader] in calm mode. Like the sea, each one bakes a small [ShoreMap] of
 * the terrain (depth below ITS level, 1 m texels) so the water only shows where the ground is below
 * the surface, with a wet line and see-through shallows along the bank. The terrain keeps the banks
 * above the water out to [Lake.waterExtent], so a square plane never leaks over lower land.
 *
 * Cost: one draw call per lake in view, a few small textures (~25k height samples in total at start-up).
 */
class LakeRenderer(terrain: IslandTerrain) : Disposable {

  private class Body(val lake: Lake, val shader: WaterShader, val texture: Texture, val mesh: Mesh, val renderable: Renderable)

  private val bodies = ArrayList<Body>()
  private var clock = 0f

  var visual: VisualSettings = VisualSettings()
    set(value) { field = value; bodies.forEach { it.shader.visual = value } }

  var sky: SkyPalette = SkyPalette()
    set(value) { field = value; bodies.forEach { it.shader.sky = value } }

  init {
    for (lake in terrain.lakes) {
      val extent = lake.waterExtent
      val texels = ceil(2f * extent / TEXEL_METRES).toInt().coerceAtLeast(8)
      val span = texels * TEXEL_METRES
      val originX = lake.x - span * 0.5f
      val originZ = lake.z - span * 0.5f
      val encoded = ShoreMap.bake(texels, texels, originX, originZ, span, span, lake.level, terrain::heightAt)
      val pixmap = Pixmap(texels, texels, Pixmap.Format.RGBA8888)
      for (row in 0 until texels) for (col in 0 until texels) {
        pixmap.drawPixel(col, row, (encoded[row * texels + col] shl 24) or 0xFF)
      }
      val texture = Texture(pixmap)
      pixmap.dispose()
      texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
      texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
      val shader = WaterShader(texture, originX, originZ, span, span).apply { calm = 1f; init() }
      val h = extent
      val mesh = Mesh(true, 4, 6, VertexAttribute.Position()).apply {
        setVertices(floatArrayOf(-h, 0f, -h, h, 0f, -h, h, 0f, h, -h, 0f, h))
        setIndices(shortArrayOf(0, 1, 2, 0, 2, 3))
      }
      val renderable = Renderable().apply {
        material = Material(BlendingAttribute(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f))
        meshPart.set("lake", mesh, 0, 6, GL20.GL_TRIANGLES)
        worldTransform.setToTranslation(lake.x, lake.level, lake.z)
        meshPart.center.set(0f, 0f, 0f)
        meshPart.halfExtents.set(h, 0.1f, h)
        meshPart.radius = h * 1.42f
      }
      bodies.add(Body(lake, shader, texture, mesh, renderable))
    }
  }

  val lakeCount: Int get() = bodies.size

  fun update(delta: Float, fogDensity: Float, farDistance: Float) {
    clock = (clock + delta) % WaterShader.TIME_WRAP
    for (b in bodies) {
      b.shader.time = clock
      b.shader.fogDensity = fogDensity
      b.shader.farDistance = farDistance
    }
  }

  /** Submits the lakes in view (call in the same batch as the sea, after the opaque world). */
  fun render(batch: ModelBatch, environment: Environment, camera: com.badlogic.gdx.graphics.Camera) {
    for (b in bodies) {
      tmp.set(b.lake.x, b.lake.level, b.lake.z)
      if (!camera.frustum.sphereInFrustum(tmp, b.lake.waterExtent * 1.42f)) continue
      b.renderable.environment = environment
      b.renderable.shader = b.shader
      batch.render(b.renderable)
    }
  }

  private val tmp = Vector3()

  override fun dispose() {
    for (b in bodies) { b.shader.dispose(); b.texture.dispose(); b.mesh.dispose() }
    bodies.clear()
  }

  private companion object {
    const val TEXEL_METRES = 1f
  }
}

package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.RenderableProvider
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Pool
import com.badlogic.gdx.utils.Array as GdxArray
import kotlin.math.ceil

/**
 * The sea around the island: one huge flat quad at [ChunkManager.seaLevel] that follows the camera
 * horizontally (so it always reaches the horizon) and is drawn with [WaterShader] after the opaque
 * terrain. Waves live in the shader, so the mesh is just 4 vertices.
 *
 * It bakes a [ShoreMap] of the original terrain once at construction ([SHORE_TEXEL_METRES] per texel,
 * ~260k height samples for the 1 km island, a fraction of a second) which gives the shader the water
 * depth for foam and transparency. The shores are long gentle slopes, so 2 m texels are plenty.
 */
class OceanRenderer(world: ChunkManager) : Disposable, RenderableProvider {

  private val seaLevel = world.seaLevel
  private val shoreTexture: Texture
  private val shader: WaterShader
  private val mesh: Mesh
  private val renderable = Renderable()
  private var clock = 0f

  /** Live visual calibration, forwarded to the water shader. */
  var visual: VisualSettings
    get() = shader.visual
    set(value) { shader.visual = value }

  /** Atmosphere of the frame, forwarded to the water shader (horizon haze colour). */
  var sky: SkyPalette
    get() = shader.sky
    set(value) { shader.sky = value }

  init {
    val spanX = world.maxX - world.minX
    val spanZ = world.maxZ - world.minZ
    val width = ceil(spanX / SHORE_TEXEL_METRES).toInt()
    val height = ceil(spanZ / SHORE_TEXEL_METRES).toInt()
    val encoded = ShoreMap.bake(
      width, height, world.minX, world.minZ, spanX, spanZ, seaLevel, world::seaFloorHeightAt
    )

    val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
    // The map lives in the red channel (RGBA8888 is packed 0xRRGGBBAA)
    for (row in 0 until height) {
      for (col in 0 until width) {
        pixmap.drawPixel(col, row, (encoded[row * width + col] shl 24) or 0xFF)
      }
    }
    shoreTexture = Texture(pixmap)
    pixmap.dispose()
    // Linear for a smooth shoreline; clamped, so everything outside the world reads as the
    // deep sea its border texels describe
    shoreTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
    shoreTexture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)

    shader = WaterShader(shoreTexture, world.minX, world.minZ, spanX, spanZ).apply { init() }

    val h = HALF_EXTENT
    mesh = Mesh(true, 4, 6, VertexAttribute.Position()).apply {
      setVertices(floatArrayOf(-h, 0f, -h, h, 0f, -h, h, 0f, h, -h, 0f, h))
      setIndices(shortArrayOf(0, 1, 2, 0, 2, 3))
    }
    renderable.apply {
      material = Material(BlendingAttribute(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f))
      meshPart.set("ocean", mesh, 0, 6, GL20.GL_TRIANGLES)
      worldTransform.setToTranslation(0f, seaLevel, 0f)
    }
  }

  /** Advances the wave clock and re-centres the plane under the camera. */
  fun update(delta: Float, cameraPosition: Vector3, fogDensity: Float, farDistance: Float, skyColor: Color) {
    clock = (clock + delta) % WaterShader.TIME_WRAP
    shader.time = clock
    shader.fogDensity = fogDensity
    shader.farDistance = farDistance
    shader.skyColor.set(skyColor)
    renderable.worldTransform.setToTranslation(cameraPosition.x, seaLevel, cameraPosition.z)
  }

  /**
   * Submits the sea to [batch]. Call it after the terrain, inside the same begin()/end(): the
   * ModelBatch draws blended renderables after the opaque ones.
   */
  fun render(batch: ModelBatch, environment: Environment) {
    batch.render(this, environment, shader)
  }

  override fun getRenderables(renderables: GdxArray<Renderable>, pool: Pool<Renderable>) {
    renderables.add(renderable)
  }

  override fun dispose() {
    mesh.dispose()
    shader.dispose()
    shoreTexture.dispose()
  }

  companion object {
    /** Shore map resolution: one texel per this many metres (the world is ~1.4 km: 570 x 570 texels). */
    private const val SHORE_TEXEL_METRES = 2.5f

    /**
     * Half the side of the plane. Larger than the far-plane corner distance of the camera frustum
     * (about 600 m for a 320 m far plane on a wide phone screen), so the quad's own edge is never visible.
     */
    private const val HALF_EXTENT = 700f
  }
}

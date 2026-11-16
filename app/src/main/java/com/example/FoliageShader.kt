package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute
import com.badlogic.gdx.graphics.g3d.attributes.SpotLightsAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.RenderContext
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.GdxRuntimeException

/**
 * Shader for the batched ecosystem meshes (trees, shrubs, rocks, pebbles, sticks, grass cards...).
 *
 *  - Alpha test: fragments below 0.5 alpha are discarded, so leaves and cards need no sorting and
 *    write depth like any opaque surface.
 *  - Wind: vertices are displaced by a travelling sine wave scaled by the per-vertex amplitude the
 *    batcher baked into `a_sway` (zero for trunks and rocks, growing towards the tips of leaves and
 *    blades), driven by [time].
 *  - The same day/night lighting, flashlight and fog as [TerrainShader], including the fade-out at
 *    the edge of the streamed area; small ground cover ("near" vertices) additionally erodes away
 *    between [smallFadeStart] and [smallFadeEnd] metres by raising the alpha threshold.
 *  - A desaturating grade per layer so the vegetation sits in the same natural palette as the ground.
 *
 * GLSL ES 1.00, accepted by every ES 2.0 / 3.0 device.
 */
class FoliageShader : Shader {

  private lateinit var program: ShaderProgram
  private val loc = HashMap<String, Int>()
  private val tmpDir = Vector3()

  var time: Float = 0f
  var fogDensity: Float = 0f
  var fadeStart: Float = 1e6f
  var fadeEnd: Float = 2e6f
  var smallFadeStart: Float = 28f
  var smallFadeEnd: Float = 40f

  override fun init() {
    ShaderProgram.pedantic = false
    program = ShaderProgram(VERTEX, FRAGMENT)
    if (!program.isCompiled) throw GdxRuntimeException("FoliageShader compile failed:\n${program.log}")
    for (name in UNIFORMS) loc[name] = program.getUniformLocation(name)
  }

  private fun u(name: String): Int = loc.getValue(name)

  override fun compareTo(other: Shader): Int = if (other === this) 0 else -1

  override fun canRender(instance: Renderable): Boolean = true

  override fun begin(camera: Camera, context: RenderContext) {
    context.setDepthTest(GL20.GL_LEQUAL)
    context.setDepthMask(true)
    context.setCullFace(GL20.GL_NONE)
    context.setBlending(false, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    program.bind()
    program.setUniformMatrix(u("u_projViewTrans"), camera.combined)
    program.setUniformf(u("u_cameraPos"), camera.position)
    program.setUniformf(u("u_time"), time)
    program.setUniformf(u("u_fogDensity"), fogDensity)
    program.setUniformf(u("u_fadeRange"), fadeStart, fadeEnd)
    program.setUniformf(u("u_smallFade"), smallFadeStart, smallFadeEnd)
    textureBinder = context.textureBinder
  }

  private var textureBinder: com.badlogic.gdx.graphics.g3d.utils.TextureBinder? = null

  override fun render(renderable: Renderable) {
    val texture = (renderable.material.get(TextureAttribute.Diffuse) as TextureAttribute).textureDescription
    program.setUniformi(u("u_diffuse"), textureBinder!!.bind(texture))
    if (renderable.material.id == LAYER_CUTOUT) {
      program.setUniformf(u("u_grade"), 0.76f, 0.83f, 0.70f, 0.72f)
      program.setUniformf(u("u_wrap"), 0.55f)
    } else {
      program.setUniformf(u("u_grade"), 0.90f, 0.90f, 0.90f, 0.75f)
      program.setUniformf(u("u_wrap"), 0.0f)
    }
    bindEnvironment(renderable)
    renderable.meshPart.render(program)
  }

  private fun bindEnvironment(renderable: Renderable) {
    val env = renderable.environment
    val fog = env?.get(ColorAttribute.Fog) as? ColorAttribute
    if (fog != null) program.setUniformf(u("u_fogColor"), fog.color.r, fog.color.g, fog.color.b)
    else program.setUniformf(u("u_fogColor"), 0f, 0f, 0f)

    val ambient = env?.get(ColorAttribute.AmbientLight) as? ColorAttribute
    if (ambient != null) program.setUniformf(u("u_ambientColor"), ambient.color.r, ambient.color.g, ambient.color.b)
    else program.setUniformf(u("u_ambientColor"), 0.4f, 0.4f, 0.4f)

    val dir = (env?.get(DirectionalLightsAttribute.Type) as? DirectionalLightsAttribute)?.lights?.firstOrNull()
    if (dir != null) {
      program.setUniformf(u("u_dirLightColor"), dir.color.r, dir.color.g, dir.color.b)
      tmpDir.set(dir.direction).nor()
      program.setUniformf(u("u_dirLightDir"), tmpDir)
    } else {
      program.setUniformf(u("u_dirLightColor"), 0f, 0f, 0f)
      program.setUniformf(u("u_dirLightDir"), 0f, -1f, 0f)
    }

    val spot = (env?.get(SpotLightsAttribute.Type) as? SpotLightsAttribute)?.lights?.firstOrNull()
    if (spot != null && spot.intensity > 0f) {
      program.setUniformf(u("u_spotLightColor"), spot.color.r, spot.color.g, spot.color.b)
      program.setUniformf(u("u_spotLightPos"), spot.position)
      tmpDir.set(spot.direction).nor()
      program.setUniformf(u("u_spotLightDir"), tmpDir)
      program.setUniformf(u("u_spotLightIntensity"), spot.intensity)
      program.setUniformf(u("u_spotLightCutoff"), spot.cutoffAngle)
    } else {
      program.setUniformf(u("u_spotLightIntensity"), 0f)
    }
  }

  override fun end() = Unit

  override fun dispose() {
    if (::program.isInitialized) program.dispose()
  }

  companion object {
    const val LAYER_SOLID = "scatter_solid"
    const val LAYER_CUTOUT = "scatter_cutout"

    private val UNIFORMS = listOf(
      "u_projViewTrans", "u_cameraPos", "u_time", "u_fogDensity", "u_fadeRange", "u_smallFade",
      "u_diffuse", "u_grade", "u_wrap", "u_fogColor", "u_ambientColor", "u_dirLightColor", "u_dirLightDir",
      "u_spotLightColor", "u_spotLightPos", "u_spotLightDir", "u_spotLightIntensity", "u_spotLightCutoff"
    )

    const val VERTEX = """#version 100
attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec2 a_texCoord0;
attribute float a_sway;
attribute float a_near;

uniform mat4 u_projViewTrans;
uniform float u_time;

varying vec3 v_pos;
varying vec3 v_normal;
varying vec2 v_uv;
varying float v_near;

void main() {
    vec3 p = a_position;
    // Two travelling waves; the phase follows the world position so a gust visibly sweeps across the field
    float ph = dot(p.xz, vec2(0.62, 0.48));
    float w = sin(u_time * 1.7 + ph) * 0.65 + sin(u_time * 3.3 + ph * 1.9) * 0.35;
    p.x += w * a_sway;
    p.z += w * a_sway * 0.6;
    v_pos = p;
    v_normal = a_normal;
    v_uv = a_texCoord0;
    v_near = a_near;
    gl_Position = u_projViewTrans * vec4(p, 1.0);
}
"""

    const val FRAGMENT = """#version 100
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HP highp
#else
#define HP mediump
#endif

varying HP vec3 v_pos;
varying mediump vec3 v_normal;
varying mediump vec2 v_uv;
varying mediump float v_near;

uniform sampler2D u_diffuse;
uniform HP vec3 u_cameraPos;
uniform HP vec2 u_fadeRange;
uniform HP vec2 u_smallFade;
uniform mediump vec4 u_grade;   // rgb tint, a = saturation kept
uniform mediump float u_wrap;   // 0 = plain Lambert, 1 = half-Lambert (leaves are lit from behind too)
uniform mediump float u_fogDensity;
uniform mediump vec3 u_fogColor;
uniform mediump vec3 u_ambientColor;
uniform mediump vec3 u_dirLightColor;
uniform mediump vec3 u_dirLightDir;
uniform mediump vec3 u_spotLightColor;
uniform HP vec3 u_spotLightPos;
uniform mediump vec3 u_spotLightDir;
uniform mediump float u_spotLightIntensity;
uniform mediump float u_spotLightCutoff;

const mediump vec3 LUMA = vec3(0.30, 0.59, 0.11);

void main() {
    mediump vec4 tex = texture2D(u_diffuse, v_uv);
    HP float camDist = length(u_cameraPos - v_pos);

    // Alpha test (no blending, no sorting). Small ground cover erodes away with distance by raising the threshold
    mediump float cutoff = 0.5 + 0.55 * v_near * smoothstep(u_smallFade.x, u_smallFade.y, camDist);
    if (tex.a < cutoff) discard;

    mediump vec3 albedo = mix(vec3(dot(tex.rgb, LUMA)), tex.rgb, u_grade.a) * u_grade.rgb;
    // The pale grass / flower cards are darkened so they sit in the ground instead of glowing on it
    albedo *= 1.0 - 0.34 * v_near * step(0.1, u_wrap);

    mediump vec3 N = normalize(v_normal);
    mediump vec3 L = normalize(-u_dirLightDir);
    mediump float d = dot(N, L);
    d = mix(max(d, 0.0), d * 0.5 + 0.5, u_wrap);
    mediump vec3 lighting = u_ambientColor * albedo + u_dirLightColor * albedo * d;

    if (u_spotLightIntensity > 0.001) {
        HP vec3 toLight = u_spotLightPos - v_pos;
        HP float distSq = dot(toLight, toLight);
        mediump vec3 spotL = toLight * inversesqrt(max(distSq, 0.0001));
        mediump float cosTheta = dot(-spotL, normalize(u_spotLightDir));
        mediump float cutoffCos = cos(radians(u_spotLightCutoff));
        if (cosTheta > cutoffCos) {
            mediump float cone = clamp((cosTheta - cutoffCos) / max(1.0 - cutoffCos, 0.0001), 0.0, 1.0);
            mediump float atten = u_spotLightIntensity / (1.0 + 0.16 * distSq);
            mediump float spotDiff = mix(max(dot(N, spotL), 0.0), 0.7, u_wrap);
            lighting += albedo * spotDiff * u_spotLightColor * pow(cone, 1.35) * atten * 0.55;
        }
    }

    HP float fogArg = camDist * u_fogDensity;
    mediump float fogAmount = clamp(1.0 - exp(-fogArg * fogArg), 0.0, 1.0);
    fogAmount = max(fogAmount, smoothstep(u_fadeRange.x, u_fadeRange.y, camDist));
    gl_FragColor = vec4(mix(lighting, u_fogColor, fogAmount), 1.0);
}
"""
  }
}

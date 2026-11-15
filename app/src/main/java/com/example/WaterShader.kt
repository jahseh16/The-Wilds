package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute
import com.badlogic.gdx.graphics.g3d.attributes.SpotLightsAttribute
import com.badlogic.gdx.graphics.g3d.utils.RenderContext
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.GdxRuntimeException

/**
 * Light-weight sea shader (GLSL ES 2.0 / 3.0): the surface is a flat plane, all the motion is done
 * per pixel with a handful of analytic waves, so there is no vertex displacement and the only
 * texture read is the baked [ShoreMap].
 *
 * What it draws, from the sun/moon, ambient and fog of the [com.badlogic.gdx.graphics.g3d.Environment]
 * (so it follows the day/night cycle without any extra plumbing):
 *  - Fresnel mix between the water body colour and the sky (fog) colour,
 *  - sun/moon specular glints,
 *  - turquoise shallows fading to deep blue, with transparency that grows with depth,
 *  - animated white foam where the baked depth is low, plus an outgoing wave crest,
 *  - exp-squared fog and a fade to the fog colour before the far plane.
 *
 * The caller sets [time], [fogDensity] and [farDistance] each frame, then renders through a
 * ModelBatch AFTER the opaque terrain (it blends and does not write depth).
 */
class WaterShader(
  private val shoreMap: Texture,
  private val shoreOriginX: Float,
  private val shoreOriginZ: Float,
  private val shoreSpanX: Float,
  private val shoreSpanZ: Float
) : Shader {

  private lateinit var program: ShaderProgram

  private var uProjViewTransLoc = -1
  private var uWorldTransLoc = -1
  private var uCameraPosLoc = -1
  private var uTimeLoc = -1
  private var uShoreMapLoc = -1
  private var uShoreRectLoc = -1
  private var uAmbientColorLoc = -1
  private var uDirLightColorLoc = -1
  private var uDirLightDirLoc = -1
  private var uFogColorLoc = -1
  private var uSkyColorLoc = -1
  private var uFogDensityLoc = -1
  private var uFarDistanceLoc = -1
  private var uSpotLightColorLoc = -1
  private var uSpotLightPosLoc = -1
  private var uSpotLightDirLoc = -1
  private var uSpotLightIntensityLoc = -1
  private var uSpotLightCutoffLoc = -1

  private val tmpDir = Vector3()

  /** Animation clock in seconds. The waves repeat every [TIME_WRAP] s, so wrap it there (seamless). */
  var time: Float = 0f

  /** Exponential-squared fog density in 1/metre (colour comes from the Environment's Fog attribute). */
  var fogDensity: Float = 0f

  /** Camera far plane: the sea blends into [skyColor] before it gets clipped. */
  var farDistance: Float = 320f

  /**
   * The colour the screen is cleared to (the sky). Fog is lighter than the clear colour in the day
   * phases, so the far end of the sea fades to this instead: no bright band at the horizon.
   */
  val skyColor = Color(0.45f, 0.65f, 0.85f, 1f)

  override fun init() {
    val useEs30 = Gdx.gl30 != null
    ShaderProgram.pedantic = false
    program = ShaderProgram(
      if (useEs30) VERTEX_SHADER_ES30 else VERTEX_SHADER_ES20,
      if (useEs30) FRAGMENT_SHADER_ES30 else FRAGMENT_SHADER_ES20
    )
    if (!program.isCompiled) {
      throw GdxRuntimeException("WaterShader compile failed:\n${program.log}")
    }

    uProjViewTransLoc = program.getUniformLocation("u_projViewTrans")
    uWorldTransLoc = program.getUniformLocation("u_worldTrans")
    uCameraPosLoc = program.getUniformLocation("u_cameraPos")
    uTimeLoc = program.getUniformLocation("u_time")
    uShoreMapLoc = program.getUniformLocation("u_shoreMap")
    uShoreRectLoc = program.getUniformLocation("u_shoreRect")
    uAmbientColorLoc = program.getUniformLocation("u_ambientColor")
    uDirLightColorLoc = program.getUniformLocation("u_dirLightColor")
    uDirLightDirLoc = program.getUniformLocation("u_dirLightDir")
    uFogColorLoc = program.getUniformLocation("u_fogColor")
    uSkyColorLoc = program.getUniformLocation("u_skyColor")
    uFogDensityLoc = program.getUniformLocation("u_fogDensity")
    uFarDistanceLoc = program.getUniformLocation("u_farDistance")
    uSpotLightColorLoc = program.getUniformLocation("u_spotLightColor")
    uSpotLightPosLoc = program.getUniformLocation("u_spotLightPos")
    uSpotLightDirLoc = program.getUniformLocation("u_spotLightDir")
    uSpotLightIntensityLoc = program.getUniformLocation("u_spotLightIntensity")
    uSpotLightCutoffLoc = program.getUniformLocation("u_spotLightCutoff")
  }

  override fun compareTo(other: Shader): Int = if (other === this) 0 else -1

  override fun canRender(instance: Renderable): Boolean = true

  override fun begin(camera: Camera, context: RenderContext) {
    // Transparent surface over the already drawn terrain: test against its depth, never write it
    context.setDepthTest(GL20.GL_LEQUAL)
    context.setDepthMask(false)
    context.setCullFace(GL20.GL_NONE)
    context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

    program.bind()
    program.setUniformMatrix(uProjViewTransLoc, camera.combined)
    program.setUniformf(uCameraPosLoc, camera.position)
    program.setUniformf(uTimeLoc, time)
    program.setUniformi(uShoreMapLoc, context.textureBinder.bind(shoreMap))
    program.setUniformf(
      uShoreRectLoc,
      shoreOriginX, shoreOriginZ, 1f / shoreSpanX, 1f / shoreSpanZ
    )
    program.setUniformf(uFogDensityLoc, fogDensity)
    program.setUniformf(uFarDistanceLoc, farDistance)
    program.setUniformf(uSkyColorLoc, skyColor.r, skyColor.g, skyColor.b)
  }

  override fun render(renderable: Renderable) {
    program.setUniformMatrix(uWorldTransLoc, renderable.worldTransform)
    bindEnvironment(renderable)
    renderable.meshPart.render(program)
  }

  private fun bindEnvironment(renderable: Renderable) {
    val env = renderable.environment
    val fog = env?.get(ColorAttribute.Fog) as? ColorAttribute
    if (fog != null) {
      program.setUniformf(uFogColorLoc, fog.color.r, fog.color.g, fog.color.b)
    } else {
      program.setUniformf(uFogColorLoc, 0.6f, 0.75f, 0.9f)
    }

    val ambient = env?.get(ColorAttribute.AmbientLight) as? ColorAttribute
    if (ambient != null) {
      program.setUniformf(uAmbientColorLoc, ambient.color.r, ambient.color.g, ambient.color.b)
    } else {
      program.setUniformf(uAmbientColorLoc, 0.4f, 0.4f, 0.4f)
    }

    val dirLight = (env?.get(DirectionalLightsAttribute.Type) as? DirectionalLightsAttribute)
      ?.lights?.firstOrNull()
    if (dirLight != null) {
      program.setUniformf(uDirLightColorLoc, dirLight.color.r, dirLight.color.g, dirLight.color.b)
      tmpDir.set(dirLight.direction).nor()
      program.setUniformf(uDirLightDirLoc, tmpDir)
    } else {
      program.setUniformf(uDirLightColorLoc, 0f, 0f, 0f)
      program.setUniformf(uDirLightDirLoc, 0f, -1f, 0f)
    }

    val spot = (env?.get(SpotLightsAttribute.Type) as? SpotLightsAttribute)?.lights?.firstOrNull()
    if (spot != null && spot.intensity > 0f) {
      program.setUniformf(uSpotLightColorLoc, spot.color.r, spot.color.g, spot.color.b)
      program.setUniformf(uSpotLightPosLoc, spot.position)
      tmpDir.set(spot.direction).nor()
      program.setUniformf(uSpotLightDirLoc, tmpDir)
      program.setUniformf(uSpotLightIntensityLoc, spot.intensity)
      program.setUniformf(uSpotLightCutoffLoc, spot.cutoffAngle)
    } else {
      program.setUniformf(uSpotLightIntensityLoc, 0f)
    }
  }

  override fun end() = Unit

  override fun dispose() {
    if (::program.isInitialized) program.dispose()
  }

  companion object {
    /** Seconds after which [time] must wrap; every wave and foam phase is a whole number of cycles per this. */
    const val TIME_WRAP = 600f

    private const val VERTEX_SHADER_ES30 = """#version 300 es
in vec3 a_position;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

out highp vec3 v_worldPos;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    gl_Position = u_projViewTrans * worldPos;
}
"""

    private const val VERTEX_SHADER_ES20 = """#version 100
attribute vec3 a_position;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

varying vec3 v_worldPos;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    gl_Position = u_projViewTrans * worldPos;
}
"""

    private const val FRAGMENT_HEADER_ES30 = """#version 300 es
precision mediump float;
#define HP highp
#define SAMPLE_PLAIN(tex, uv) texture(tex, uv)

in highp vec3 v_worldPos;
out mediump vec4 fragColor;
"""

    private const val FRAGMENT_HEADER_ES20 = """#version 100
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HP highp
#else
#define HP mediump
#endif
#define SAMPLE_PLAIN(tex, uv) texture2D(tex, uv)
#define fragColor gl_FragColor

varying HP vec3 v_worldPos;
"""

    private const val FRAGMENT_BODY = """
uniform HP vec3 u_cameraPos;
uniform HP float u_time;
uniform sampler2D u_shoreMap;
uniform HP vec4 u_shoreRect;

uniform mediump vec3 u_ambientColor;
uniform mediump vec3 u_dirLightColor;
uniform mediump vec3 u_dirLightDir;

uniform mediump vec3 u_fogColor;
uniform mediump vec3 u_skyColor;
uniform mediump float u_fogDensity;
uniform HP float u_farDistance;

uniform mediump vec3 u_spotLightColor;
uniform HP vec3 u_spotLightPos;
uniform mediump vec3 u_spotLightDir;
uniform mediump float u_spotLightIntensity;
uniform mediump float u_spotLightCutoff;

// Must match ShoreMap.DEPTH_RANGE
const mediump float DEPTH_RANGE = 12.0;
// 2*pi / 600 s. Every wave / foam phase below is an integer number of cycles per 600 s, so the
// CPU can wrap u_time at 600 without any visible jump.
const HP float TIME_TO_PHASE = 0.0104719755;

const mediump vec3 SHALLOW_COLOR = vec3(0.10, 0.62, 0.62);
const mediump vec3 DEEP_COLOR = vec3(0.010, 0.140, 0.300);
const mediump vec3 FOAM_COLOR = vec3(0.96, 0.98, 1.00);

HP float hash21(HP vec2 p) {
    HP vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

mediump float valueNoise(HP vec2 p) {
    HP vec2 i = floor(p);
    HP vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    mediump float a = hash21(i);
    mediump float b = hash21(i + vec2(1.0, 0.0));
    mediump float c = hash21(i + vec2(0.0, 1.0));
    mediump float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Slope (dh/dx, dh/dz) of six travelling waves. A flat plane needs no height, only the normal, so
// this is just cosines. 'detail' (0..1) fades out the small waves with distance to avoid shimmering.
// Each term: direction, spatial frequency, integer cycles per 600 s, amplitude * frequency. The
// position is warped by two slow sines first so the crests are never perfectly straight parallel
// lines (which read as a venetian blind at grazing angles).
HP vec2 waveSlope(HP vec2 p0, HP float ph, mediump float detail) {
    HP vec2 p = p0 + 1.8 * vec2(sin(p0.y * 0.17 + ph * 50.0), sin(p0.x * 0.13 - ph * 43.0));
    HP vec2 g = vec2(0.0);
    g += vec2(0.92, 0.39) * (cos(dot(p, vec2(0.92, 0.39)) * 0.55 + ph * 86.0) * 0.165);
    g += vec2(-0.45, 0.89) * (cos(dot(p, vec2(-0.45, 0.89)) * 0.93 + ph * 124.0) * 0.140);
    g += vec2(0.62, -0.78) * (cos(dot(p, vec2(0.62, -0.78)) * 1.35 + ph * 151.0) * 0.100);
    g += vec2(0.25, -0.97) * (cos(dot(p, vec2(0.25, -0.97)) * 1.90 + ph * 181.0) * 0.133 * detail);
    g += vec2(-0.99, 0.12) * (cos(dot(p, vec2(-0.99, 0.12)) * 2.50 + ph * 207.0) * 0.090 * detail);
    g += vec2(-0.87, -0.49) * (cos(dot(p, vec2(-0.87, -0.49)) * 3.40 + ph * 248.0) * 0.119 * detail);
    return g;
}

void main() {
    // Baked depth of the original terrain below this point (metres, > 0 = under water)
    HP vec2 shoreUv = (v_worldPos.xz - u_shoreRect.xy) * u_shoreRect.zw;
    mediump float depth = (SAMPLE_PLAIN(u_shoreMap, shoreUv).r - 0.5) * DEPTH_RANGE;
    // The sea only exists outside the island: no water sheet inside dug pits or over dry land
    if (depth < -0.04) discard;

    HP vec3 toCam = u_cameraPos - v_worldPos;
    HP float dist = length(toCam);
    HP float fogArg = dist * u_fogDensity;
    mediump float fogAmount = 1.0 - exp(-fogArg * fogArg);
    // The plane is clipped by the camera far plane; be fully fogged before that happens
    mediump float farAmount = smoothstep(u_farDistance * 0.55, u_farDistance * 0.9, dist);
    fogAmount = max(fogAmount, farAmount);
    // Fully faded water has exactly the clear colour, so the far edge of the sea meets the sky seamlessly
    mediump vec3 fogTarget = mix(u_fogColor, u_skyColor, farAmount);
    if (fogAmount > 0.999) {
        fragColor = vec4(fogTarget, 1.0);
        return;
    }

    mediump vec3 V = toCam / max(dist, 0.0001);
    HP float ph = u_time * TIME_TO_PHASE;

    mediump float detail = 1.0 - smoothstep(15.0, 80.0, dist);
    mediump float flatten = 0.85 / (1.0 + dist * 0.025);
    mediump vec2 slope = waveSlope(v_worldPos.xz, ph, detail) * flatten;
    mediump vec3 N = normalize(vec3(-slope.x, 1.0, -slope.y));

    mediump vec3 L = normalize(-u_dirLightDir);
    mediump float ndl = max(dot(N, L), 0.0);
    mediump vec3 lightScale = min(u_ambientColor + u_dirLightColor * (0.30 + 0.55 * ndl), vec3(1.1));

    // Body colour: turquoise shallows -> deep blue, lit by the current sun/moon/ambient
    mediump float depthMix = smoothstep(0.0, 4.5, depth);
    mediump vec3 body = mix(SHALLOW_COLOR, DEEP_COLOR, depthMix) * lightScale;

    // Fresnel: looking straight down shows the body, grazing angles show the sky (fog colour)
    // (computed from a flattened normal: at grazing angles the full wave slope flips the reflection
    // on and off per crest and turns into stripes)
    mediump vec3 Nf = normalize(vec3(-slope.x * 0.3, 1.0, -slope.y * 0.3));
    mediump float nv = clamp(dot(Nf, V), 0.0, 1.0);
    mediump float fresnel = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
    mediump vec3 col = mix(body, u_fogColor, fresnel);

    // Sun / moon glints
    mediump vec3 H = normalize(L + V);
    mediump float nh = max(dot(N, H), 0.0);
    mediump float spec = pow(nh, 220.0) * 1.8 + pow(nh, 28.0) * 0.12;

    // Foam: a washing band that breathes in and out (6 s) along the shore, a thin crest line
    // travelling out from it, both broken up by noise
    mediump float swash = 0.5 + 0.5 * sin(ph * 100.0);
    mediump float wobble =
        0.16 * sin(v_worldPos.x * 1.7 + v_worldPos.z * 0.6 + ph * 120.0) +
        0.11 * sin(v_worldPos.z * 1.3 - v_worldPos.x * 0.5 - ph * 95.0);
    mediump float edge = depth + wobble;
    mediump float band = 1.0 - smoothstep(0.0, 0.35 + 0.65 * swash, edge);
    mediump float crest = 1.0 - smoothstep(0.0, 0.18, abs(edge - (0.9 + 0.8 * swash)));
    mediump float breakup = valueNoise(v_worldPos.xz * 1.6 + vec2(ph * 40.0, -ph * 30.0));
    mediump float foam = clamp(band + crest * 0.7, 0.0, 1.0) * smoothstep(0.30, 0.62, breakup + band * 0.35);

    col += u_dirLightColor * spec * (1.0 - foam);
    mediump vec3 foamLight = min(u_ambientColor + u_dirLightColor * 0.85, vec3(1.0));
    col = mix(col, FOAM_COLOR * foamLight, foam * 0.95);

    // Flashlight on the water surface
    if (u_spotLightIntensity > 0.001) {
        HP vec3 toLight = u_spotLightPos - v_worldPos;
        HP float distSq = dot(toLight, toLight);
        mediump vec3 spotL = toLight * inversesqrt(max(distSq, 0.0001));
        mediump float cosTheta = dot(-spotL, normalize(u_spotLightDir));
        mediump float cutoffCos = cos(radians(u_spotLightCutoff));
        if (cosTheta > cutoffCos) {
            mediump float cone = clamp((cosTheta - cutoffCos) / max(1.0 - cutoffCos, 0.0001), 0.0, 1.0);
            mediump float atten = u_spotLightIntensity / (1.0 + 0.16 * distSq);
            mediump vec3 halfDir = normalize(spotL + V);
            mediump float spotSpec = pow(max(dot(N, halfDir), 0.0), 60.0) * 0.5;
            mediump float spotDiff = max(dot(N, spotL), 0.0);
            col += (mix(SHALLOW_COLOR, DEEP_COLOR, depthMix) * spotDiff * 0.6 + vec3(spotSpec)) *
                u_spotLightColor * pow(cone, 1.35) * atten;
        }
    }

    // Transparent in the shallows (the sea bed shows through), opaque far out; reflections and
    // foam are always visible; the very edge fades so there is no hard line on the sand
    mediump float alpha = mix(0.25, 0.95, smoothstep(0.0, 3.2, depth));
    alpha = max(alpha, fresnel);
    alpha = mix(alpha, 0.98, foam);
    alpha *= smoothstep(-0.04, 0.10, depth);

    col = mix(col, fogTarget, fogAmount);
    alpha = mix(alpha, 1.0, fogAmount);
    fragColor = vec4(col, alpha);
}
"""

    private const val FRAGMENT_SHADER_ES30 = FRAGMENT_HEADER_ES30 + FRAGMENT_BODY
    private const val FRAGMENT_SHADER_ES20 = FRAGMENT_HEADER_ES20 + FRAGMENT_BODY
  }
}

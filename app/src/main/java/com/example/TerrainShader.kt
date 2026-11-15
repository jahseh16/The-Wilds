package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.GLTexture
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
import kotlin.math.min

class TerrainShader(
  private val grassColor: Texture,
  private val grassNormal: Texture,
  private val dirtColor: Texture,
  private val dirtNormal: Texture,
  private val rockColor: Texture,
  private val rockNormal: Texture
) : Shader {

  private lateinit var program: ShaderProgram

  private var uProjViewTransLoc = -1
  private var uWorldTransLoc = -1
  private var uCameraPosLoc = -1

  private var uGrassColorLoc = -1
  private var uGrassNormalLoc = -1
  private var uDirtColorLoc = -1
  private var uDirtNormalLoc = -1
  private var uRockColorLoc = -1
  private var uRockNormalLoc = -1

  private var uUvScaleLoc = -1
  private var uNormalStrengthLoc = -1

  private var uAmbientColorLoc = -1
  private var uDirLightColorLoc = -1
  private var uDirLightDirLoc = -1

  private var uFogColorLoc = -1
  private var uFogDensityLoc = -1

  private var uSpotLightColorLoc = -1
  private var uSpotLightPosLoc = -1
  private var uSpotLightDirLoc = -1
  private var uSpotLightIntensityLoc = -1
  private var uSpotLightCutoffLoc = -1

  private val tmpDir = Vector3()

  /**
   * Exponential-squared fog density in 1/metre (0 = no fog). The fog COLOUR comes from the
   * Environment's [ColorAttribute.Fog]; the density is set by the caller each frame because libGDX
   * has no standard attribute for it.
   */
  var fogDensity: Float = 0f

  /**
   * Trilinear mipmapping plus anisotropic filtering (when the GPU exposes it) for a terrain texture.
   *
   * Only the MINIFICATION filter may be a mipmap mode: GL rejects a mipmap enum as the magnification
   * filter (GL_INVALID_ENUM, parameter ignored), which silently leaves magnification on Nearest
   * and shows up as chunky texels when standing close to the ground.
   */
  private fun configureTerrainTexture(texture: Texture) {
    val hasMipMaps = texture.textureData.useMipMaps()
    texture.setFilter(
      if (hasMipMaps) Texture.TextureFilter.MipMapLinearLinear else Texture.TextureFilter.Linear,
      Texture.TextureFilter.Linear
    )
    texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)

    val maxAnisotropy = GLTexture.getMaxAnisotropicFilterLevel()
    if (hasMipMaps && maxAnisotropy > 1f) {
      texture.setAnisotropicFilter(min(maxAnisotropy, MAX_ANISOTROPY))
    }
  }

  override fun init() {
    listOf(grassColor, grassNormal, dirtColor, dirtNormal, rockColor, rockNormal)
      .forEach(::configureTerrainTexture)

    val useEs30 = Gdx.gl30 != null
    val vertexShader = if (useEs30) VERTEX_SHADER_ES30 else VERTEX_SHADER_ES20
    val fragmentShader = if (useEs30) FRAGMENT_SHADER_ES30 else FRAGMENT_SHADER_ES20

    ShaderProgram.pedantic = false
    program = ShaderProgram(vertexShader, fragmentShader)
    if (!program.isCompiled) {
      throw GdxRuntimeException("TerrainShader compile failed:\n${program.log}")
    }

    uProjViewTransLoc = program.getUniformLocation("u_projViewTrans")
    uWorldTransLoc = program.getUniformLocation("u_worldTrans")
    uCameraPosLoc = program.getUniformLocation("u_cameraPos")

    uGrassColorLoc = program.getUniformLocation("u_grassColor")
    uGrassNormalLoc = program.getUniformLocation("u_grassNormal")
    uDirtColorLoc = program.getUniformLocation("u_dirtColor")
    uDirtNormalLoc = program.getUniformLocation("u_dirtNormal")
    uRockColorLoc = program.getUniformLocation("u_rockColor")
    uRockNormalLoc = program.getUniformLocation("u_rockNormal")

    uUvScaleLoc = program.getUniformLocation("u_uvScale")
    uNormalStrengthLoc = program.getUniformLocation("u_normalStrength")

    uAmbientColorLoc = program.getUniformLocation("u_ambientColor")
    uDirLightColorLoc = program.getUniformLocation("u_dirLightColor")
    uDirLightDirLoc = program.getUniformLocation("u_dirLightDir")

    uFogColorLoc = program.getUniformLocation("u_fogColor")
    uFogDensityLoc = program.getUniformLocation("u_fogDensity")

    uSpotLightColorLoc = program.getUniformLocation("u_spotLightColor")
    uSpotLightPosLoc = program.getUniformLocation("u_spotLightPos")
    uSpotLightDirLoc = program.getUniformLocation("u_spotLightDir")
    uSpotLightIntensityLoc = program.getUniformLocation("u_spotLightIntensity")
    uSpotLightCutoffLoc = program.getUniformLocation("u_spotLightCutoff")
  }

  override fun compareTo(other: Shader): Int = if (other === this) 0 else -1

  override fun canRender(instance: Renderable): Boolean = true

  override fun begin(camera: Camera, context: RenderContext) {
    context.setDepthTest(GL20.GL_LEQUAL)
    context.setDepthMask(true)
    context.setCullFace(GL20.GL_NONE)
    context.setBlending(false, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

    program.bind()
    program.setUniformMatrix(uProjViewTransLoc, camera.combined)
    program.setUniformf(uCameraPosLoc, camera.position)

    program.setUniformi(uGrassColorLoc, context.textureBinder.bind(grassColor))
    program.setUniformi(uGrassNormalLoc, context.textureBinder.bind(grassNormal))
    program.setUniformi(uDirtColorLoc, context.textureBinder.bind(dirtColor))
    program.setUniformi(uDirtNormalLoc, context.textureBinder.bind(dirtNormal))
    program.setUniformi(uRockColorLoc, context.textureBinder.bind(rockColor))
    program.setUniformi(uRockNormalLoc, context.textureBinder.bind(rockNormal))

    program.setUniformf(uUvScaleLoc, TRIPLANAR_UV_SCALE)
    program.setUniformf(uNormalStrengthLoc, NORMAL_MAP_STRENGTH)
    program.setUniformf(uFogDensityLoc, fogDensity)
  }

  override fun render(renderable: Renderable) {
    program.setUniformMatrix(uWorldTransLoc, renderable.worldTransform)
    bindEnvironmentLights(renderable)
    renderable.meshPart.render(program)
  }

  private fun bindEnvironmentLights(renderable: Renderable) {
    val env = renderable.environment
    val fogAttr = env?.get(ColorAttribute.Fog) as? ColorAttribute
    if (fogAttr != null) {
      program.setUniformf(uFogColorLoc, fogAttr.color.r, fogAttr.color.g, fogAttr.color.b)
    } else {
      program.setUniformf(uFogColorLoc, 0f, 0f, 0f)
    }

    if (env == null) {
      program.setUniformf(uAmbientColorLoc, 0.5f, 0.5f, 0.5f)
      program.setUniformf(uDirLightColorLoc, 0f, 0f, 0f)
      program.setUniformf(uDirLightDirLoc, 0f, -1f, 0f)
      program.setUniformf(uSpotLightIntensityLoc, 0f)
      return
    }

    val ambientAttr = env.get(ColorAttribute.AmbientLight) as? ColorAttribute
    if (ambientAttr != null) {
      program.setUniformf(
        uAmbientColorLoc,
        ambientAttr.color.r,
        ambientAttr.color.g,
        ambientAttr.color.b
      )
    } else {
      program.setUniformf(uAmbientColorLoc, 0.35f, 0.35f, 0.35f)
    }

    val dirAttr = env.get(DirectionalLightsAttribute.Type) as? DirectionalLightsAttribute
    val dirLight = dirAttr?.lights?.firstOrNull()
    if (dirLight != null) {
      program.setUniformf(
        uDirLightColorLoc,
        dirLight.color.r,
        dirLight.color.g,
        dirLight.color.b
      )
      tmpDir.set(dirLight.direction).nor()
      program.setUniformf(uDirLightDirLoc, tmpDir)
    } else {
      program.setUniformf(uDirLightColorLoc, 0f, 0f, 0f)
      program.setUniformf(uDirLightDirLoc, 0f, -1f, 0f)
    }

    val spotAttr = env.get(SpotLightsAttribute.Type) as? SpotLightsAttribute
    val spotLight = spotAttr?.lights?.firstOrNull()
    if (spotLight != null && spotLight.intensity > 0f) {
      program.setUniformf(
        uSpotLightColorLoc,
        spotLight.color.r,
        spotLight.color.g,
        spotLight.color.b
      )
      program.setUniformf(uSpotLightPosLoc, spotLight.position)
      tmpDir.set(spotLight.direction).nor()
      program.setUniformf(uSpotLightDirLoc, tmpDir)
      program.setUniformf(uSpotLightIntensityLoc, spotLight.intensity)
      program.setUniformf(uSpotLightCutoffLoc, spotLight.cutoffAngle)
    } else {
      program.setUniformf(uSpotLightIntensityLoc, 0f)
    }
  }

  override fun end() = Unit

  override fun dispose() {
    if (::program.isInitialized) {
      program.dispose()
    }
  }

  private companion object {
    const val TRIPLANAR_UV_SCALE = 0.35f
    const val NORMAL_MAP_STRENGTH = 1.25f
    const val MAX_ANISOTROPY = 4f

    private const val VERTEX_SHADER_ES30 = """#version 300 es
in vec3 a_position;
in vec3 a_normal;
in float a_skyOcclusion;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

out highp vec3 v_worldPos;
out mediump vec3 v_worldNormal;
out mediump float v_skyOcc;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_worldNormal = normalize(mat3(u_worldTrans) * a_normal);
    v_skyOcc = a_skyOcclusion;
    gl_Position = u_projViewTrans * worldPos;
}
"""

    private const val VERTEX_SHADER_ES20 = """#version 100
attribute vec3 a_position;
attribute vec3 a_normal;
attribute float a_skyOcclusion;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

varying vec3 v_worldPos;
varying vec3 v_worldNormal;
varying float v_skyOcc;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_worldNormal = normalize(mat3(u_worldTrans) * a_normal);
    v_skyOcc = a_skyOcclusion;
    gl_Position = u_projViewTrans * worldPos;
}
"""

    // The fragment shader body is shared by both GLSL dialects; only these headers differ.
    // HP        = highp where available. World positions / UVs MUST stay highp: in fp16 (mediump)
    //             a UV near 50 only has ~0.03 resolution, which shows up as jittering streaks.
    // SAMPLE    = textureGrad on ES 3.0 (explicit gradients computed in uniform control flow, so the
    //             mip level stays correct inside divergent branches); plain texture2D on ES 2.0.
    private const val FRAGMENT_HEADER_ES30 = """#version 300 es
precision mediump float;
#define HP highp
#define SAMPLE(tex, uv, dx, dy) textureGrad(tex, uv, dx, dy)
#define DFDX(v) dFdx(v)
#define DFDY(v) dFdy(v)

in highp vec3 v_worldPos;
in mediump vec3 v_worldNormal;
in mediump float v_skyOcc;
out mediump vec4 fragColor;
"""

    private const val FRAGMENT_HEADER_ES20 = """#version 100
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HP highp
#else
#define HP mediump
#endif
#define SAMPLE(tex, uv, dx, dy) texture2D(tex, uv)
#define DFDX(v) ((v) * 0.0)
#define DFDY(v) ((v) * 0.0)
#define fragColor gl_FragColor

varying HP vec3 v_worldPos;
varying mediump vec3 v_worldNormal;
varying mediump float v_skyOcc;
"""

    // Texture-read budget per fragment: at most 4 reads. Flat ground costs 2, and beyond ~75 m the
    // normal map is skipped. Rock and soil are picked per pixel (noise-wobbled border); the one
    // blend that exists, sand <-> grass, is only done inside its transition band using the dominant
    // projection axis alone (2 colour + 2 normal reads), so it never exceeds the budget.
    private const val FRAGMENT_BODY = """
uniform HP vec3 u_cameraPos;

uniform sampler2D u_grassColor;
uniform sampler2D u_grassNormal;
uniform sampler2D u_dirtColor;
uniform sampler2D u_dirtNormal;
uniform sampler2D u_rockColor;
uniform sampler2D u_rockNormal;

uniform mediump float u_uvScale;
uniform mediump float u_normalStrength;

uniform mediump vec3 u_ambientColor;
uniform mediump vec3 u_dirLightColor;
uniform mediump vec3 u_dirLightDir;

uniform mediump vec3 u_fogColor;
uniform mediump float u_fogDensity;

uniform mediump vec3 u_spotLightColor;
uniform HP vec3 u_spotLightPos;
uniform mediump vec3 u_spotLightDir;
uniform mediump float u_spotLightIntensity;
uniform mediump float u_spotLightCutoff;

// Axis weights fade to exactly 0 at this value (max(w - cutoff, 0)) instead of popping off, so there
// are no hard seams where a projection axis stops contributing.
const mediump float WEIGHT_CUTOFF = 0.05;
// Normal mapping fades out between these camera distances (metres): far pixels are flat anyway
const HP float NORMAL_FADE_START = 45.0;
const HP float NORMAL_FADE_END = 75.0;
// Ambient light that still reaches a fully buried point (the flashlight must do the work)
const mediump float CAVE_AMBIENT_MIN = 0.05;
const mediump vec3 SAND_COLOR = vec3(0.80, 0.69, 0.50);

struct Proj {
    HP vec2 uv;
    HP vec2 dx;
    HP vec2 dy;
};

// Must be called from uniform control flow (derivatives)
Proj makeProj(HP vec2 uv) {
    Proj p;
    p.uv = uv;
    p.dx = DFDX(uv);
    p.dy = DFDY(uv);
    return p;
}

// Hash without sin(): stable on every GPU and free of texture reads
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

// Tri-planar colour of one material; weights tw sum to 1 and fade smoothly to 0.
// Each active axis costs exactly one texture read.
mediump vec3 sampleColor(sampler2D tex, Proj px, Proj py, Proj pz, mediump vec3 tw) {
    mediump vec3 col = vec3(0.0);
    if (tw.x > 0.0) col += SAMPLE(tex, px.uv, px.dx, px.dy).rgb * tw.x;
    if (tw.y > 0.0) col += SAMPLE(tex, py.uv, py.dx, py.dy).rgb * tw.y;
    if (tw.z > 0.0) col += SAMPLE(tex, pz.uv, pz.dx, pz.dy).rgb * tw.z;
    return col;
}

// Dominant-axis normal mapping: a single normal-texture read, scaled by amount (0 = geometry only)
mediump vec3 perturbNormal(
    sampler2D normTex,
    Proj dom,
    int domAxis,
    mediump vec3 geomN,
    mediump float amount
) {
    mediump vec3 tn = SAMPLE(normTex, dom.uv, dom.dx, dom.dy).xyz * 2.0 - 1.0;
    tn.xy *= u_normalStrength * amount;
    if (domAxis == 1) {
        return normalize(vec3(tn.x + geomN.x, geomN.y * tn.z, tn.y + geomN.z));
    } else if (domAxis == 0) {
        return normalize(vec3(geomN.x * tn.z, tn.y + geomN.y, tn.x + geomN.z));
    } else {
        return normalize(vec3(tn.x + geomN.x, tn.y + geomN.y, geomN.z * tn.z));
    }
}

void main() {
    mediump vec3 geomN = normalize(v_worldNormal);
    HP float camDist = length(u_cameraPos - v_worldPos);

    // Tri-planar axis blend weights
    mediump vec3 blend = abs(geomN);
    blend = blend * blend;
    blend = blend * blend;
    blend /= max(blend.x + blend.y + blend.z, 0.0001);

    mediump vec3 tw = max(blend - WEIGHT_CUTOFF, 0.0);
    tw /= max(tw.x + tw.y + tw.z, 0.0001);

    Proj projX = makeProj(v_worldPos.zy * u_uvScale);
    Proj projY = makeProj(v_worldPos.xz * u_uvScale);
    Proj projZ = makeProj(v_worldPos.xy * u_uvScale);

    // Dominant projection axis for the 1-read normal mapping (0=X, 1=Y, 2=Z)
    int domAxis = 2;
    Proj dom = projZ;
    if (blend.y >= blend.x && blend.y >= blend.z) {
        domAxis = 1;
        dom = projY;
    } else if (blend.x >= blend.z) {
        domAxis = 0;
        dom = projX;
    }

    // Sky exposure baked per vertex by the mesher: sun and sky light only reach points that can see
    // the sky, so a tunnel goes dark even at noon and the flashlight becomes necessary. The
    // smoothstep keeps entrances bright and makes anything under ~3 m of earth really dark.
    mediump float sky = smoothstep(0.04, 0.85, v_skyOcc);

    // ALU-only noise (no texture reads): two octaves drive the rock border and a slight brightness
    // variation that hides tiling; a third, very low one bends the sand/grass border into wide organic blotches
    HP vec2 noiseUv = v_worldPos.xz + v_worldPos.y * vec2(0.37, 0.71);
    mediump float n = valueNoise(noiseUv * 0.45);
    mediump float n2 = valueNoise(noiseUv * 2.3 + 17.0);
    mediump float nLow = valueNoise(noiseUv * 0.07 + 41.0);
    mediump float brightness = mix(0.9, 1.1, n);
    mediump float jitter = (n * 0.55 + n2 * 0.45 - 0.5) * 1.1;

    // Grass amount g (0 = bare sand/dirt, 1 = full grass) is a CONTINUOUS function of height and
    // slope, not a winner-takes-all pick, so the beach melts into the meadow. The height threshold is
    // displaced by low-frequency noise (+-1 m, i.e. ~15 m sideways on a beach) so the border is an
    // irregular wide gradient instead of a contour line, and the slope term fades grass out on steep
    // ground over a wide range of normals. Grass also needs sunlight (sky): never inside a tunnel.
    HP float ground = v_worldPos.y + (nLow - 0.5) * 2.0 + (n2 - 0.5) * 0.5;
    mediump float heightT = smoothstep(0.7, 3.6, ground);
    mediump float slopeT = smoothstep(0.62, 0.90, geomN.y + (n2 - 0.5) * 0.10);
    // Alpine zone: grass thins out between ~22 and ~32 m and the rock takes over (noise-shifted)
    HP float alt = v_worldPos.y + (nLow - 0.5) * 8.0;
    mediump float g = heightT * slopeT * sky * (1.0 - smoothstep(22.0, 32.0, alt));

    // Rock: underwater floor, overhangs, anything out of the sun, high peaks and steep mountain
    // flanks. It still competes with the soil by (noisy) score; where grass is strong it never wins.
    mediump float altRock = smoothstep(22.0, 34.0, alt);
    mediump float steepRock = (1.0 - smoothstep(0.42, 0.62, geomN.y)) * smoothstep(6.0, 16.0, v_worldPos.y);
    mediump float rockBase = clamp(
        (1.0 - smoothstep(-2.4, 0.5, v_worldPos.y)) +
            (1.0 - smoothstep(-0.35, 0.20, geomN.y)) * 0.75 +
            (1.0 - sky) + altRock + steepRock,
        0.0,
        1.0
    );
    bool isRock = rockBase * (1.0 - g) > 0.5 + 0.3 * jitter;
    // Up in the mountains and on steep flanks the ground under the thinning grass is rock, never sand-coloured dirt
    // (the switch happens where grass still covers ~90 %, so the change of the material under it is invisible)
    bool alpine = alt > 23.0 || steepRock > 0.3;
    bool bareRock = isRock || (g < 0.02 && alpine);

    mediump float normalAmount = 1.0 - smoothstep(NORMAL_FADE_START, NORMAL_FADE_END, camDist);
    bool useNormal = normalAmount > 0.0;

    // Reads: bare rock or pure soil = up to 3 colour (one per active axis) + 1 normal. Only inside
    // the grass transition band (sand/rock <-> grass) both materials are needed, and then only the
    // dominant axis is sampled (2 colour + 2 normal = 4), so the budget of 4 holds everywhere.
    mediump vec3 albedo;
    mediump vec3 N = geomN;
    if (bareRock) {
        albedo = sampleColor(u_rockColor, projX, projY, projZ, tw);
        if (useNormal) N = perturbNormal(u_rockNormal, dom, domAxis, geomN, normalAmount);
    } else if (g < 0.02) {
        albedo = sampleColor(u_dirtColor, projX, projY, projZ, tw);
        if (useNormal) N = perturbNormal(u_dirtNormal, dom, domAxis, geomN, normalAmount);
    } else if (g > 0.98) {
        albedo = sampleColor(u_grassColor, projX, projY, projZ, tw);
        if (useNormal) N = perturbNormal(u_grassNormal, dom, domAxis, geomN, normalAmount);
    } else {
        mediump vec3 grassCol = SAMPLE(u_grassColor, dom.uv, dom.dx, dom.dy).rgb;
        mediump vec3 lowCol;
        if (alpine) {
            lowCol = SAMPLE(u_rockColor, dom.uv, dom.dx, dom.dy).rgb;
        } else {
            lowCol = SAMPLE(u_dirtColor, dom.uv, dom.dx, dom.dy).rgb;
        }
        albedo = mix(lowCol, grassCol, g);
        if (useNormal) {
            mediump vec3 nLowN;
            if (alpine) {
                nLowN = perturbNormal(u_rockNormal, dom, domAxis, geomN, normalAmount);
            } else {
                nLowN = perturbNormal(u_dirtNormal, dom, domAxis, geomN, normalAmount);
            }
            mediump vec3 nGrass = perturbNormal(u_grassNormal, dom, domAxis, geomN, normalAmount);
            N = normalize(mix(nLowN, nGrass, g));
        }
    }

    mediump float luma = dot(albedo, vec3(0.30, 0.59, 0.11));
    if (!bareRock) {
        // Beach sand: the dirt texture pushed towards pale sand on gentle ground between just below
        // the waterline and the top of the beach (keeps its detail through the luminance)
        mediump float sandAmount = (1.0 - g) * smoothstep(-2.4, -0.3, v_worldPos.y) *
            (1.0 - smoothstep(2.4, 4.4, ground)) * smoothstep(0.55, 0.85, geomN.y);
        albedo = mix(albedo, SAND_COLOR * (0.55 + 0.65 * luma), sandAmount * 0.6);
    }
    // Mountain rock cools towards grey with altitude (the rock texture itself is brownish)
    albedo = mix(albedo, vec3(luma) * vec3(0.94, 0.97, 1.04), 0.6 * altRock);
    albedo *= brightness;

    // Ambient + Directional sun lighting, both scaled by the sky exposure
    mediump vec3 lighting = u_ambientColor * mix(CAVE_AMBIENT_MIN, 1.0, sky) * albedo;
    mediump vec3 dirL = normalize(-u_dirLightDir);
    mediump float dirDiff = max(dot(N, dirL), 0.0);
    lighting += albedo * u_dirLightColor * (dirDiff * sky);

    // SpotLight (Flashlight) with normal-mapped relief & subtle specular inside caves
    if (u_spotLightIntensity > 0.001) {
        HP vec3 toLight = u_spotLightPos - v_worldPos;
        HP float distSq = dot(toLight, toLight);
        mediump float dist = sqrt(distSq);
        mediump vec3 spotL = toLight / max(dist, 0.0001);

        mediump float cosTheta = dot(-spotL, normalize(u_spotLightDir));
        mediump float cutoffCos = cos(radians(u_spotLightCutoff));
        if (cosTheta > cutoffCos) {
            mediump float cone = clamp((cosTheta - cutoffCos) / max(1.0 - cutoffCos, 0.0001), 0.0, 1.0);
            mediump float spotFactor = pow(cone, 1.35);
            mediump float atten = u_spotLightIntensity / (1.0 + 0.16 * distSq);

            mediump float spotDiff = max(dot(N, spotL), 0.0);
            mediump vec3 viewDir = normalize(u_cameraPos - v_worldPos);
            mediump vec3 halfDir = normalize(spotL + viewDir);
            mediump float spec = pow(max(dot(N, halfDir), 0.0), 24.0) * 0.18;

            lighting += (albedo * spotDiff + vec3(spec)) * u_spotLightColor * spotFactor * atten;
        }
    }

    // Exponential-squared distance fog (colour follows the sky so distant terrain melts into it)
    HP float fogArg = camDist * u_fogDensity;
    mediump float fogAmount = clamp(1.0 - exp(-fogArg * fogArg), 0.0, 1.0);
    lighting = mix(lighting, u_fogColor, fogAmount);

    fragColor = vec4(lighting, 1.0);
}
"""

    private const val FRAGMENT_SHADER_ES30 = FRAGMENT_HEADER_ES30 + FRAGMENT_BODY
    private const val FRAGMENT_SHADER_ES20 = FRAGMENT_HEADER_ES20 + FRAGMENT_BODY
  }
}

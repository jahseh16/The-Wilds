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

  private var uSpotLightColorLoc = -1
  private var uSpotLightPosLoc = -1
  private var uSpotLightDirLoc = -1
  private var uSpotLightIntensityLoc = -1
  private var uSpotLightCutoffLoc = -1

  private val tmpDir = Vector3()

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
  }

  override fun render(renderable: Renderable) {
    program.setUniformMatrix(uWorldTransLoc, renderable.worldTransform)
    bindEnvironmentLights(renderable)
    renderable.meshPart.render(program)
  }

  private fun bindEnvironmentLights(renderable: Renderable) {
    val env = renderable.environment
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
    const val MAX_ANISOTROPY = 8f

    private const val VERTEX_SHADER_ES30 = """#version 300 es
in vec3 a_position;
in vec3 a_normal;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

out highp vec3 v_worldPos;
out mediump vec3 v_worldNormal;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_worldNormal = normalize(mat3(u_worldTrans) * a_normal);
    gl_Position = u_projViewTrans * worldPos;
}
"""

    private const val VERTEX_SHADER_ES20 = """#version 100
attribute vec3 a_position;
attribute vec3 a_normal;

uniform mat4 u_projViewTrans;
uniform mat4 u_worldTrans;

varying vec3 v_worldPos;
varying vec3 v_worldNormal;

void main() {
    vec4 worldPos = u_worldTrans * vec4(a_position, 1.0);
    v_worldPos = worldPos.xyz;
    v_worldNormal = normalize(mat3(u_worldTrans) * a_normal);
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
#define SAMPLE_PLAIN(tex, uv) texture(tex, uv)
#define DFDX(v) dFdx(v)
#define DFDY(v) dFdy(v)

in highp vec3 v_worldPos;
in mediump vec3 v_worldNormal;
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
#define SAMPLE_PLAIN(tex, uv) texture2D(tex, uv)
#define DFDX(v) ((v) * 0.0)
#define DFDY(v) ((v) * 0.0)
#define fragColor gl_FragColor

varying HP vec3 v_worldPos;
varying mediump vec3 v_worldNormal;
"""

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

uniform mediump vec3 u_spotLightColor;
uniform HP vec3 u_spotLightPos;
uniform mediump vec3 u_spotLightDir;
uniform mediump float u_spotLightIntensity;
uniform mediump float u_spotLightCutoff;

// Three sampling scales break the visible tiling of a single repeating texture:
//   base  = u_uvScale            (the original look)
//   fine  = base * FINE_SCALE    (small grain, rotated ~37 deg so its repeats never line up with base)
//   macro = base * MACRO_SCALE   (very large blotches; drives an organic mix/brightness mask)
const mediump float FINE_SCALE = 3.1;
const mediump float MACRO_SCALE = 0.19;
const mediump mat2 FINE_ROT = mat2(0.8, -0.6, 0.6, 0.8);
// Weights fade to exactly 0 at this value (max(w - cutoff, 0)) instead of popping off, so there
// are no hard seams where a projection axis or material layer stops contributing.
const mediump float WEIGHT_CUTOFF = 0.05;

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

// One projection: base-scale and fine-scale (rotated) samples mixed by fineMix
mediump vec3 sampleBlended(sampler2D tex, Proj p, mediump float fineMix) {
    mediump vec3 baseC = SAMPLE(tex, p.uv, p.dx, p.dy).rgb;
    HP vec2 fineUv = FINE_ROT * p.uv * FINE_SCALE;
    HP vec2 fineDx = FINE_ROT * p.dx * FINE_SCALE;
    HP vec2 fineDy = FINE_ROT * p.dy * FINE_SCALE;
    mediump vec3 fineC = SAMPLE(tex, fineUv, fineDx, fineDy).rgb;
    return mix(baseC, fineC, fineMix);
}

// Tri-planar colour; weights tw already sum to 1 and fade smoothly to 0
mediump vec3 sampleMaterial(
    sampler2D tex,
    Proj px,
    Proj py,
    Proj pz,
    mediump vec3 tw,
    mediump float fineMix
) {
    mediump vec3 col = vec3(0.0);
    if (tw.x > 0.0) col += sampleBlended(tex, px, fineMix) * tw.x;
    if (tw.y > 0.0) col += sampleBlended(tex, py, fineMix) * tw.y;
    if (tw.z > 0.0) col += sampleBlended(tex, pz, fineMix) * tw.z;
    return col;
}

// Dominant-axis normal mapping: single normal texture lookup per active layer
mediump vec3 sampleDominantNormal(
    sampler2D normTex,
    Proj dom,
    int domAxis,
    mediump vec3 geomN
) {
    mediump vec3 tn = SAMPLE(normTex, dom.uv, dom.dx, dom.dy).xyz * 2.0 - 1.0;
    tn.xy *= u_normalStrength;
    if (domAxis == 1) {
        return normalize(vec3(tn.x + geomN.x, geomN.y * tn.z, tn.y + geomN.z));
    } else if (domAxis == 0) {
        return normalize(vec3(geomN.x * tn.z, tn.y + geomN.y, tn.x + geomN.z));
    } else {
        return normalize(vec3(tn.x + geomN.x, tn.y + geomN.y, geomN.z * tn.z));
    }
}

// Very low-frequency tri-planar noise (0..1) taken from the dirt texture. Sampled unconditionally
// so its mip level is always well defined.
mediump float macroMask(HP vec3 p, mediump vec3 tw) {
    HP float s = u_uvScale * MACRO_SCALE;
    mediump float n =
        SAMPLE_PLAIN(u_dirtColor, p.zy * s + vec2(0.37, 0.11)).g * tw.x +
        SAMPLE_PLAIN(u_dirtColor, p.xz * s).g * tw.y +
        SAMPLE_PLAIN(u_dirtColor, p.xy * s + vec2(0.71, 0.53)).g * tw.z;
    return smoothstep(0.2, 0.7, n);
}

void main() {
    mediump vec3 geomN = normalize(v_worldNormal);

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

    // Dominant projection axis for fast 1-sample normal mapping (0=X, 1=Y, 2=Z)
    int domAxis = 2;
    Proj dom = projZ;
    if (blend.y >= blend.x && blend.y >= blend.z) {
        domAxis = 1;
        dom = projY;
    } else if (blend.x >= blend.z) {
        domAxis = 0;
        dom = projX;
    }

    // Organic variation: where the macro mask is high, the fine-scale grain shows through more
    // and the surface is slightly brighter; where it is low, the base scale dominates.
    mediump float organic = macroMask(v_worldPos, tw);
    mediump float fineMix = mix(0.25, 0.65, organic);
    mediump float brightness = mix(0.88, 1.12, organic);

    // Material layer weights: grass on flat exterior tops, dirt on slopes/walls, rock in deep tunnels
    // (smoothstep edges kept ascending: reversed edges are undefined in GLSL)
    mediump float grassW = smoothstep(0.66, 0.86, geomN.y) * smoothstep(-0.8, 0.5, v_worldPos.y);
    mediump float rockBase = clamp(
        (1.0 - smoothstep(-2.4, 0.5, v_worldPos.y)) +
            (1.0 - smoothstep(-0.35, 0.20, geomN.y)) * 0.75,
        0.0,
        1.0
    );
    mediump float nonGrass = 1.0 - grassW;
    mediump float rockW = max(rockBase * nonGrass - WEIGHT_CUTOFF, 0.0);
    mediump float dirtW = max((1.0 - rockBase) * nonGrass - WEIGHT_CUTOFF, 0.0);
    grassW = max(grassW - WEIGHT_CUTOFF, 0.0);

    mediump vec3 albedo = vec3(0.0);
    mediump vec3 accumNormal = vec3(0.0);
    mediump float matWeightSum = 0.0;

    if (grassW > 0.0) {
        albedo += sampleMaterial(u_grassColor, projX, projY, projZ, tw, fineMix) * grassW;
        accumNormal += sampleDominantNormal(u_grassNormal, dom, domAxis, geomN) * grassW;
        matWeightSum += grassW;
    }
    if (dirtW > 0.0) {
        albedo += sampleMaterial(u_dirtColor, projX, projY, projZ, tw, fineMix) * dirtW;
        accumNormal += sampleDominantNormal(u_dirtNormal, dom, domAxis, geomN) * dirtW;
        matWeightSum += dirtW;
    }
    if (rockW > 0.0) {
        albedo += sampleMaterial(u_rockColor, projX, projY, projZ, tw, fineMix) * rockW;
        accumNormal += sampleDominantNormal(u_rockNormal, dom, domAxis, geomN) * rockW;
        matWeightSum += rockW;
    }

    mediump float invMatW = 1.0 / max(matWeightSum, 0.0001);
    albedo *= invMatW * brightness;
    mediump vec3 N = normalize(accumNormal);

    // Ambient + Directional sun lighting
    mediump vec3 lighting = u_ambientColor * albedo;
    mediump vec3 dirL = normalize(-u_dirLightDir);
    mediump float dirDiff = max(dot(N, dirL), 0.0);
    lighting += albedo * u_dirLightColor * dirDiff;

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

    fragColor = vec4(lighting, 1.0);
}
"""

    private const val FRAGMENT_SHADER_ES30 = FRAGMENT_HEADER_ES30 + FRAGMENT_BODY
    private const val FRAGMENT_SHADER_ES20 = FRAGMENT_HEADER_ES20 + FRAGMENT_BODY
  }
}

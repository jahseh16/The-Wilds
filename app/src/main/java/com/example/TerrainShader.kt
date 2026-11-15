package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
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

  override fun init() {
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

    private const val FRAGMENT_SHADER_ES30 = """#version 300 es
precision mediump float;

in highp vec3 v_worldPos;
in mediump vec3 v_worldNormal;

uniform highp vec3 u_cameraPos;

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
uniform highp vec3 u_spotLightPos;
uniform mediump vec3 u_spotLightDir;
uniform mediump float u_spotLightIntensity;
uniform mediump float u_spotLightCutoff;

out mediump vec4 fragColor;

// Thresholded tri-planar color sampling: skips axes whose blend weight <= 0.05
mediump vec3 sampleTriplanarColor(
    sampler2D tex,
    mediump vec2 uvX,
    mediump vec2 uvY,
    mediump vec2 uvZ,
    mediump vec3 blend
) {
    mediump vec3 col = vec3(0.0);
    mediump float totalW = 0.0;
    if (blend.x > 0.05) {
        col += texture(tex, uvX).rgb * blend.x;
        totalW += blend.x;
    }
    if (blend.y > 0.05) {
        col += texture(tex, uvY).rgb * blend.y;
        totalW += blend.y;
    }
    if (blend.z > 0.05) {
        col += texture(tex, uvZ).rgb * blend.z;
        totalW += blend.z;
    }
    return col / max(totalW, 0.0001);
}

// Dominant-axis normal mapping: single normal texture lookup per active layer
mediump vec3 sampleDominantNormal(
    sampler2D normTex,
    mediump vec2 domUv,
    int domAxis,
    mediump vec3 geomN
) {
    mediump vec3 tn = texture(normTex, domUv).xyz * 2.0 - 1.0;
    tn.xy *= u_normalStrength;
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

    // Tri-planar axis blend weights
    mediump vec3 blend = abs(geomN);
    blend = blend * blend;
    blend = blend * blend;
    blend /= max(blend.x + blend.y + blend.z, 0.0001);

    // Dominant projection axis for fast 1-sample normal mapping (0=X, 1=Y, 2=Z)
    mediump vec2 uvX = v_worldPos.zy * u_uvScale;
    mediump vec2 uvY = v_worldPos.xz * u_uvScale;
    mediump vec2 uvZ = v_worldPos.xy * u_uvScale;

    int domAxis = 2;
    mediump vec2 domUv = uvZ;
    if (blend.y >= blend.x && blend.y >= blend.z) {
        domAxis = 1;
        domUv = uvY;
    } else if (blend.x >= blend.z) {
        domAxis = 0;
        domUv = uvX;
    }

    // Material layer weights: grass on flat exterior tops, dirt on slopes/walls, rock in deep tunnels
    mediump float grassW = smoothstep(0.66, 0.86, geomN.y) * smoothstep(-0.8, 0.5, v_worldPos.y);
    mediump float rockBase = clamp(
        smoothstep(0.5, -2.4, v_worldPos.y) + smoothstep(0.20, -0.35, geomN.y) * 0.75,
        0.0,
        1.0
    );
    mediump float nonGrass = 1.0 - grassW;
    mediump float rockW = rockBase * nonGrass;
    mediump float dirtW = (1.0 - rockBase) * nonGrass;

    mediump vec3 albedo = vec3(0.0);
    mediump vec3 accumNormal = vec3(0.0);
    mediump float matWeightSum = 0.0;

    // Skip sampling any material layer whose blend weight is <= 0.05
    if (grassW > 0.05) {
        albedo += sampleTriplanarColor(u_grassColor, uvX, uvY, uvZ, blend) * grassW;
        accumNormal += sampleDominantNormal(u_grassNormal, domUv, domAxis, geomN) * grassW;
        matWeightSum += grassW;
    }
    if (dirtW > 0.05) {
        albedo += sampleTriplanarColor(u_dirtColor, uvX, uvY, uvZ, blend) * dirtW;
        accumNormal += sampleDominantNormal(u_dirtNormal, domUv, domAxis, geomN) * dirtW;
        matWeightSum += dirtW;
    }
    if (rockW > 0.05) {
        albedo += sampleTriplanarColor(u_rockColor, uvX, uvY, uvZ, blend) * rockW;
        accumNormal += sampleDominantNormal(u_rockNormal, domUv, domAxis, geomN) * rockW;
        matWeightSum += rockW;
    }

    mediump float invMatW = 1.0 / max(matWeightSum, 0.0001);
    albedo *= invMatW;
    mediump vec3 N = normalize(accumNormal);

    // Ambient + Directional sun lighting
    mediump vec3 lighting = u_ambientColor * albedo;
    mediump vec3 dirL = normalize(-u_dirLightDir);
    mediump float dirDiff = max(dot(N, dirL), 0.0);
    lighting += albedo * u_dirLightColor * dirDiff;

    // SpotLight (Flashlight) with normal-mapped relief & subtle specular inside caves
    if (u_spotLightIntensity > 0.001) {
        highp vec3 toLight = u_spotLightPos - v_worldPos;
        highp float distSq = dot(toLight, toLight);
        mediump float dist = sqrt(distSq);
        mediump vec3 spotL = vec3(toLight) / max(dist, 0.0001);

        mediump float cosTheta = dot(-spotL, normalize(u_spotLightDir));
        mediump float cutoffCos = cos(radians(u_spotLightCutoff));
        if (cosTheta > cutoffCos) {
            mediump float cone = clamp((cosTheta - cutoffCos) / max(1.0 - cutoffCos, 0.0001), 0.0, 1.0);
            mediump float spotFactor = pow(cone, 1.35);
            mediump float atten = u_spotLightIntensity / (1.0 + 0.16 * float(distSq));

            mediump float spotDiff = max(dot(N, spotL), 0.0);
            mediump vec3 viewDir = normalize(vec3(u_cameraPos - v_worldPos));
            mediump vec3 halfDir = normalize(spotL + viewDir);
            mediump float spec = pow(max(dot(N, halfDir), 0.0), 24.0) * 0.18;

            lighting += (albedo * spotDiff + vec3(spec)) * u_spotLightColor * spotFactor * atten;
        }
    }

    fragColor = vec4(lighting, 1.0);
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

    private const val FRAGMENT_SHADER_ES20 = """#version 100
precision mediump float;

varying vec3 v_worldPos;
varying vec3 v_worldNormal;

uniform vec3 u_cameraPos;

uniform sampler2D u_grassColor;
uniform sampler2D u_grassNormal;
uniform sampler2D u_dirtColor;
uniform sampler2D u_dirtNormal;
uniform sampler2D u_rockColor;
uniform sampler2D u_rockNormal;

uniform float u_uvScale;
uniform float u_normalStrength;

uniform vec3 u_ambientColor;
uniform vec3 u_dirLightColor;
uniform vec3 u_dirLightDir;

uniform vec3 u_spotLightColor;
uniform vec3 u_spotLightPos;
uniform vec3 u_spotLightDir;
uniform float u_spotLightIntensity;
uniform float u_spotLightCutoff;

vec3 sampleTriplanarColor(sampler2D tex, vec2 uvX, vec2 uvY, vec2 uvZ, vec3 blend) {
    vec3 col = vec3(0.0);
    float totalW = 0.0;
    if (blend.x > 0.05) {
        col += texture2D(tex, uvX).rgb * blend.x;
        totalW += blend.x;
    }
    if (blend.y > 0.05) {
        col += texture2D(tex, uvY).rgb * blend.y;
        totalW += blend.y;
    }
    if (blend.z > 0.05) {
        col += texture2D(tex, uvZ).rgb * blend.z;
        totalW += blend.z;
    }
    return col / max(totalW, 0.0001);
}

vec3 sampleDominantNormal(sampler2D normTex, vec2 domUv, int domAxis, vec3 geomN) {
    vec3 tn = texture2D(normTex, domUv).xyz * 2.0 - 1.0;
    tn.xy *= u_normalStrength;
    if (domAxis == 1) {
        return normalize(vec3(tn.x + geomN.x, geomN.y * tn.z, tn.y + geomN.z));
    } else if (domAxis == 0) {
        return normalize(vec3(geomN.x * tn.z, tn.y + geomN.y, tn.x + geomN.z));
    } else {
        return normalize(vec3(tn.x + geomN.x, tn.y + geomN.y, geomN.z * tn.z));
    }
}

void main() {
    vec3 geomN = normalize(v_worldNormal);

    vec3 blend = abs(geomN);
    blend = blend * blend;
    blend = blend * blend;
    blend /= max(blend.x + blend.y + blend.z, 0.0001);

    vec2 uvX = v_worldPos.zy * u_uvScale;
    vec2 uvY = v_worldPos.xz * u_uvScale;
    vec2 uvZ = v_worldPos.xy * u_uvScale;

    int domAxis = 2;
    vec2 domUv = uvZ;
    if (blend.y >= blend.x && blend.y >= blend.z) {
        domAxis = 1;
        domUv = uvY;
    } else if (blend.x >= blend.z) {
        domAxis = 0;
        domUv = uvX;
    }

    float grassW = smoothstep(0.66, 0.86, geomN.y) * smoothstep(-0.8, 0.5, v_worldPos.y);
    float rockBase = clamp(
        smoothstep(0.5, -2.4, v_worldPos.y) + smoothstep(0.20, -0.35, geomN.y) * 0.75,
        0.0,
        1.0
    );
    float nonGrass = 1.0 - grassW;
    float rockW = rockBase * nonGrass;
    float dirtW = (1.0 - rockBase) * nonGrass;

    vec3 albedo = vec3(0.0);
    vec3 accumNormal = vec3(0.0);
    float matWeightSum = 0.0;

    if (grassW > 0.05) {
        albedo += sampleTriplanarColor(u_grassColor, uvX, uvY, uvZ, blend) * grassW;
        accumNormal += sampleDominantNormal(u_grassNormal, domUv, domAxis, geomN) * grassW;
        matWeightSum += grassW;
    }
    if (dirtW > 0.05) {
        albedo += sampleTriplanarColor(u_dirtColor, uvX, uvY, uvZ, blend) * dirtW;
        accumNormal += sampleDominantNormal(u_dirtNormal, domUv, domAxis, geomN) * dirtW;
        matWeightSum += dirtW;
    }
    if (rockW > 0.05) {
        albedo += sampleTriplanarColor(u_rockColor, uvX, uvY, uvZ, blend) * rockW;
        accumNormal += sampleDominantNormal(u_rockNormal, domUv, domAxis, geomN) * rockW;
        matWeightSum += rockW;
    }

    float invMatW = 1.0 / max(matWeightSum, 0.0001);
    albedo *= invMatW;
    vec3 N = normalize(accumNormal);

    vec3 lighting = u_ambientColor * albedo;
    vec3 dirL = normalize(-u_dirLightDir);
    float dirDiff = max(dot(N, dirL), 0.0);
    lighting += albedo * u_dirLightColor * dirDiff;

    if (u_spotLightIntensity > 0.001) {
        vec3 toLight = u_spotLightPos - v_worldPos;
        float distSq = dot(toLight, toLight);
        float dist = sqrt(distSq);
        vec3 spotL = toLight / max(dist, 0.0001);

        float cosTheta = dot(-spotL, normalize(u_spotLightDir));
        float cutoffCos = cos(radians(u_spotLightCutoff));
        if (cosTheta > cutoffCos) {
            float cone = clamp((cosTheta - cutoffCos) / max(1.0 - cutoffCos, 0.0001), 0.0, 1.0);
            float spotFactor = pow(cone, 1.35);
            float atten = u_spotLightIntensity / (1.0 + 0.16 * distSq);

            float spotDiff = max(dot(N, spotL), 0.0);
            vec3 viewDir = normalize(u_cameraPos - v_worldPos);
            vec3 halfDir = normalize(spotL + viewDir);
            float spec = pow(max(dot(N, halfDir), 0.0), 24.0) * 0.18;

            lighting += (albedo * spotDiff + vec3(spec)) * u_spotLightColor * spotFactor * atten;
        }
    }

    gl_FragColor = vec4(lighting, 1.0);
}
"""
  }
}

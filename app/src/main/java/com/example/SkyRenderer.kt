package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole sky, in three cheap passes drawn on the far plane AFTER the opaque world (depth test on,
 * no depth writes), so the GPU only shades the sky pixels that are actually visible:
 *
 *  1. **Dome** (one full-screen quad): analytic atmosphere from [SkyPalette] (Rayleigh-style gradient
 *     zenith -> horizon, Mie glow and aureole around the sun / moon, amber-copper sunsets with a
 *     violet upper sky), procedural clouds on a plane above the camera (3-4 FBM octaves from a
 *     128 px tiling noise texture: 2 reads for the shape, 1 more towards the light for lit edges and
 *     golden linings), the sun as a soft-edged disc with a corona (hidden by clouds), and a faint
 *     Milky Way at night. At the horizon it equals `skyHorizon()`, the fog colour every world shader
 *     fades into, so there is no seam. At most 3 texture reads (4 at night) per sky pixel.
 *  2. **Stars**: [STAR_COUNT] constant-pixel-size quads with twinkle, fading in at dusk, wheeling with
 *     the sky and hidden behind the clouds (the same cloud function).
 *  3. **Moon**: one quad with a procedural 128 px map (craters, maria, normals), lit by a phase that
 *     advances every in-game day, a cold halo, and cloud occlusion.
 *
 * Everything ends in [VisualSettings] grading. GLSL ES 1.00 (every ES 2.0 / 3.0 device).
 */
class SkyRenderer : Disposable {

  /** Live visual calibration: the sky shares the world's exposure, contrast, saturation and warmth. */
  var visual: VisualSettings = VisualSettings()

  /** The atmosphere colours of this frame (also read by the terrain, foliage and water fog). */
  val palette = SkyPalette()

  private val skyProgram = compile(DOME_VERTEX, DOME_FRAGMENT)
  private val moonProgram = compile(BODY_VERTEX, MOON_FRAGMENT)
  private val starProgram = compile(STAR_VERTEX, STAR_FRAGMENT)

  private val quad: Mesh = Mesh(true, 4, 6, VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_corner")).apply {
    setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))
    setIndices(shortArrayOf(0, 1, 2, 0, 2, 3))
  }
  private val stars: Mesh = Mesh(
    true, STAR_COUNT * 4, STAR_COUNT * 6,
    VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_dir"),
    VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_corner"),
    VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_star")
  ).apply {
    setVertices(starVertices())
    setIndices(starIndices())
  }

  private val noiseTexture = texture(SkyTextures.cloudNoise(NOISE_SIZE), NOISE_SIZE, repeat = true)
  private val moonTexture = texture(SkyTextures.moonMap(MOON_MAP_SIZE), MOON_MAP_SIZE, repeat = false)

  private val starRotation = Matrix4()
  private val galaxyNormal = Vector3()
  private val galaxyA = Vector3()
  private val galaxyB = Vector3()
  private var clock = 0f
  private var lastTimeOfDay = -1f
  private var days = 0f

  /** Lunar phase 0..1 (0.5 = full). Swings between a thin crescent and full over [LUNAR_DAYS] in-game days. */
  var moonPhase = 0.40f
    private set

  /** Advance the clocks and the palette. Call once per frame, after [DayNightCycle.update]. */
  fun update(dayNight: DayNightCycle, delta: Float) {
    clock = (clock + delta) % TIME_WRAP
    if (lastTimeOfDay >= 0f) {
      var dt = dayNight.timeOfDay - lastTimeOfDay
      if (dt < -0.5f) dt += 1f
      if (dt > 0f) days += dt
    }
    lastTimeOfDay = dayNight.timeOfDay
    moonPhase = 0.5f + 0.32f * cos((2.0 * PI * (days / LUNAR_DAYS + 0.15)).toFloat())
    palette.update(dayNight)
  }

  /**
   * Draws dome, stars and moon on the far plane. Call after the opaque geometry and before the
   * transparent sea, with the depth buffer of the frame still bound.
   */
  fun render(camera: Camera, dayNight: DayNightCycle) {
    val gl = Gdx.gl
    gl.glEnable(GL20.GL_DEPTH_TEST)
    gl.glDepthFunc(GL20.GL_LEQUAL)
    gl.glDepthMask(false)
    gl.glDisable(GL20.GL_CULL_FACE)
    gl.glDisable(GL20.GL_BLEND)

    noiseTexture.bind(1)
    moonTexture.bind(2)
    gl.glActiveTexture(GL20.GL_TEXTURE0)

    drawDome(camera)
    gl.glEnable(GL20.GL_BLEND)
    if (dayNight.starVisibility > 0.003f) drawStars(camera, dayNight)
    if (dayNight.moonVisibility > 0.003f && dayNight.moonDirection.y > -0.15f) drawMoon(camera, dayNight)

    gl.glDisable(GL20.GL_BLEND)
    gl.glDepthMask(true)
    gl.glDepthFunc(GL20.GL_LEQUAL)
  }

  private fun drawDome(camera: Camera) {
    val p = skyProgram
    val pal = palette
    p.bind()
    p.setUniformMatrix("u_invProjView", camera.invProjectionView)
    p.setUniformf("u_camPos", camera.position)
    bindCommon(p)
    p.setUniformf("u_sunDir", pal.sunDir)
    p.setUniformf("u_zenith", pal.zenith.r, pal.zenith.g, pal.zenith.b)
    p.setUniformf("u_fogColor", pal.horizon.r, pal.horizon.g, pal.horizon.b)
    p.setUniformf("u_sunDisc", pal.sunDisc.r, pal.sunDisc.g, pal.sunDisc.b)
    p.setUniformf("u_cloudLit", pal.cloudLit.r, pal.cloudLit.g, pal.cloudLit.b)
    p.setUniformf("u_cloudShadow", pal.cloudShadow.r, pal.cloudShadow.g, pal.cloudShadow.b)
    p.setUniformf("u_night", pal.night)
    // The Milky Way turns with the stars
    starRotation.setToRotationRad(DayNightCycle.CELESTIAL_AXIS, lastTimeOfDay.coerceAtLeast(0f) * 2f * PI.toFloat())
    galaxyNormal.set(GALAXY_NORMAL).rot(starRotation)
    galaxyA.set(GALAXY_A).rot(starRotation)
    galaxyB.set(galaxyNormal).crs(galaxyA)
    p.setUniformf("u_galaxyN", galaxyNormal)
    p.setUniformf("u_galaxyA", galaxyA)
    p.setUniformf("u_galaxyB", galaxyB)
    p.setUniformf("u_dither", 1f / 255f)
    quad.render(p, GL20.GL_TRIANGLES)
  }

  private fun drawStars(camera: Camera, dayNight: DayNightCycle) {
    starRotation.setToRotationRad(DayNightCycle.CELESTIAL_AXIS, dayNight.starAngle)
    val p = starProgram
    p.bind()
    p.setUniformMatrix("u_projView", camera.combined)
    p.setUniformMatrix("u_rotation", starRotation)
    p.setUniformf("u_camPos", camera.position)
    p.setUniformf("u_radius", min(SKY_RADIUS, camera.far * 0.8f))
    p.setUniformf("u_clipPerPixel", 2f / Gdx.graphics.width, 2f / Gdx.graphics.height)
    p.setUniformf("u_visibility", dayNight.starVisibility)
    // Stars are sized in pixels: scale them up on high-density phone screens (1.0 at 720p)
    p.setUniformf("u_pixelScale", (Gdx.graphics.height / 720f).coerceIn(1f, 3f))
    bindCommon(p)
    // Purely additive: a star can only brighten the sky
    Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE)
    stars.render(p, GL20.GL_TRIANGLES)
  }

  private fun drawMoon(camera: Camera, dayNight: DayNightCycle) {
    val p = moonProgram
    p.bind()
    p.setUniformMatrix("u_projView", camera.combined)
    p.setUniformf("u_camPos", camera.position)
    p.setUniformf("u_dir", dayNight.moonDirection)
    p.setUniformf("u_radius", min(SKY_RADIUS, camera.far * 0.8f))
    p.setUniformf("u_extent", MOON_EXTENT)
    p.setUniformf("u_disc", MOON_DISC / MOON_EXTENT)
    p.setUniformf("u_strength", dayNight.moonVisibility)
    p.setUniformi("u_moonMap", 2)
    // Phase light in the disc frame (x right, y up, z towards the viewer): full at 0.5
    val a = moonPhase * 2f * PI.toFloat()
    val lx = sin(a); val lz = -cos(a); val ly = 0.18f
    val l = sqrt(lx * lx + ly * ly + lz * lz)
    p.setUniformf("u_phaseLight", lx / l, ly / l, lz / l)
    p.setUniformf("u_fullness", 0.5f - 0.5f * cos(a))
    bindCommon(p)
    // Premultiplied alpha: the disc covers the sky behind it, the halo only adds light
    Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
    quad.render(p, GL20.GL_TRIANGLES)
  }

  /** Grade, clouds and glow uniforms shared by the three programs. */
  private fun bindCommon(p: ShaderProgram) {
    p.setUniformf("u_brightness", visual.brightness)
    p.setUniformf("u_contrast", visual.contrast)
    p.setUniformf("u_saturation", visual.saturation)
    p.setUniformf("u_warmth", visual.tintR, visual.tintG, visual.tintB)
    p.setUniformi("u_noise", 1)
    p.setUniformf("u_wind", (clock * WIND_U) % 1f, (clock * WIND_V) % 1f)
    p.setUniformf("u_coverage", palette.cloudCoverage)
    p.setUniformf("u_time", clock)
    p.setUniformf("u_glowDir", palette.lightDir)
    p.setUniformf("u_glowColor", palette.glow.r, palette.glow.g, palette.glow.b)
    p.setUniformf("u_aureole", palette.aureole.r, palette.aureole.g, palette.aureole.b)
  }

  override fun dispose() {
    skyProgram.dispose()
    moonProgram.dispose()
    starProgram.dispose()
    quad.dispose()
    stars.dispose()
    noiseTexture.dispose()
    moonTexture.dispose()
  }

  private fun texture(pixels: IntArray, size: Int, repeat: Boolean): Texture {
    val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
    val buffer = pixmap.pixels
    buffer.clear()
    for (px in pixels) {
      buffer.put((px ushr 24).toByte()); buffer.put((px ushr 16).toByte()); buffer.put((px ushr 8).toByte()); buffer.put(px.toByte())
    }
    buffer.flip()
    val t = Texture(pixmap, Pixmap.Format.RGBA8888, true)
    pixmap.dispose()
    t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
    val wrap = if (repeat) Texture.TextureWrap.Repeat else Texture.TextureWrap.ClampToEdge
    t.setWrap(wrap, wrap)
    return t
  }

  companion object {
    /** Stars on the whole sphere; roughly half are above the horizon at any time. */
    const val STAR_COUNT = 900
    private const val STAR_SEED = 20240611L

    /** Distance of the sky bodies from the camera; capped to stay inside the far plane. */
    private const val SKY_RADIUS = 100f

    /** Wrap for the clouds / twinkle clock, in seconds (keeps the float precise in long sessions). */
    private const val TIME_WRAP = 3600f

    /** Cloud drift in noise-texture units per second (the texture tiles, so the offset wraps at 1). */
    private const val WIND_U = 0.0045f
    private const val WIND_V = 0.0017f

    const val NOISE_SIZE = 128
    const val MOON_MAP_SIZE = 128
    const val LUNAR_DAYS = 10f

    // Moon quad half-extent (its halo reaches the edge) and disc radius, as tangents of the angle
    private const val MOON_EXTENT = 0.24f
    private const val MOON_DISC = 0.046f

    private val GALAXY_NORMAL = Vector3(0.35f, 0.55f, -0.76f).nor()
    private val GALAXY_A = Vector3(0.35f, 0.55f, -0.76f).crs(0f, 1f, 0f).nor()

    /** Floats per star vertex: direction (3) + quad corner (2) + brightness and phase (2). */
    const val STAR_VERTEX_FLOATS = 7

    /**
     * Scatters the stars uniformly over the sphere with a fixed seed (the same sky every run). Each
     * star is a quad of 4 vertices (direction xyz, corner xy, brightness, phase). Pure CPU code, so
     * it can be tested without a GL context.
     */
    fun starVertices(count: Int = STAR_COUNT, seed: Long = STAR_SEED): FloatArray {
      val random = Random(seed)
      val vertices = FloatArray(count * 4 * STAR_VERTEX_FLOATS)
      val cornerX = floatArrayOf(-1f, 1f, 1f, -1f)
      val cornerY = floatArrayOf(-1f, -1f, 1f, 1f)
      for (i in 0 until count) {
        // Uniform point on the sphere: uniform height, uniform angle
        val y = random.nextFloat() * 2f - 1f
        val angle = random.nextFloat() * 2f * PI.toFloat()
        val ring = sqrt(max(0f, 1f - y * y))
        val dx = cos(angle) * ring
        val dz = sin(angle) * ring
        // Few bright stars, many faint ones
        val u = random.nextFloat()
        val brightness = u * u * u
        val phase = random.nextFloat()
        for (c in 0 until 4) {
          val o = (i * 4 + c) * STAR_VERTEX_FLOATS
          vertices[o] = dx; vertices[o + 1] = y; vertices[o + 2] = dz
          vertices[o + 3] = cornerX[c]; vertices[o + 4] = cornerY[c]
          vertices[o + 5] = brightness; vertices[o + 6] = phase
        }
      }
      return vertices
    }

    fun starIndices(count: Int = STAR_COUNT): ShortArray {
      val indices = ShortArray(count * 6)
      for (i in 0 until count) {
        val v = i * 4
        val k = i * 6
        indices[k] = v.toShort(); indices[k + 1] = (v + 1).toShort(); indices[k + 2] = (v + 2).toShort()
        indices[k + 3] = v.toShort(); indices[k + 4] = (v + 2).toShort(); indices[k + 5] = (v + 3).toShort()
      }
      return indices
    }

    private fun compile(vertex: String, fragment: String): ShaderProgram {
      ShaderProgram.pedantic = false
      val program = ShaderProgram(vertex, fragment)
      check(program.isCompiled) { "Sky shader failed to compile: ${program.log}" }
      return program
    }

    private const val HEADER = """#version 100
precision mediump float;
#ifdef GL_FRAGMENT_PRECISION_HIGH
#define HP highp
#else
#define HP mediump
#endif
"""

    /**
     * Clouds on a plane above the camera, seen through the dome. One noise read gives 2 octaves (R, G),
     * a second, finer and faster-drifting read 2 more (B, A): 4-octave FBM for 2 texture reads.
     */
    const val CLOUD_GLSL = """
uniform sampler2D u_noise;
uniform mediump vec2 u_wind;
uniform mediump float u_coverage;

mediump vec2 cloudUv(mediump vec3 d) {
    return d.xz / (d.y + 0.16) * 0.21 + u_wind;
}

mediump float cloudShape(mediump vec2 uv) {
    mediump vec4 n = texture2D(u_noise, uv);
    mediump vec4 m = texture2D(u_noise, uv * 2.31 + u_wind * 0.8 + vec2(0.37, 0.11));
    // large shapes from the two low octaves; the fine octaves only fray the edges
    return n.r * 0.62 + n.g * 0.38 + (m.b * 0.6 + m.a * 0.4 - 0.5) * 0.16;
}

// Coverage -> opacity, faded out towards the horizon (where the plane projection stretches)
mediump float cloudCover(mediump float f, mediump float h) {
    return smoothstep(u_coverage, u_coverage + 0.11, f) * smoothstep(0.01, 0.13, h);
}

mediump float cloudDensityAt(mediump vec3 d) {
    if (d.y <= 0.0) return 0.0;
    return cloudCover(cloudShape(cloudUv(d)), d.y);
}
"""

    private const val DOME_VERTEX = """#version 100
attribute vec2 a_corner;
uniform mat4 u_invProjView;
uniform vec3 u_camPos;
varying vec3 v_dir;
void main() {
    vec4 far = u_invProjView * vec4(a_corner, 1.0, 1.0);
    v_dir = far.xyz / far.w - u_camPos;
    // on the far plane: z = w -> depth 1.0, behind everything already drawn
    gl_Position = vec4(a_corner, 1.0, 1.0);
}
"""

    private const val DOME_FRAGMENT = HEADER + VisualGrade.GLSL + SkyGlow.GLSL + CLOUD_GLSL + """
varying HP vec3 v_dir;
uniform HP vec3 u_sunDir;
uniform mediump vec3 u_zenith;
uniform mediump vec3 u_fogColor;
uniform mediump vec3 u_sunDisc;
uniform mediump vec3 u_cloudLit;
uniform mediump vec3 u_cloudShadow;
uniform mediump float u_night;
uniform mediump vec3 u_galaxyN;
uniform mediump vec3 u_galaxyA;
uniform mediump vec3 u_galaxyB;
uniform mediump float u_dither;

const HP float SUN_R = 0.040;

void main() {
    HP vec3 dir = normalize(v_dir);
    mediump vec3 d = dir;
    mediump float h = d.y;

    // --- Atmosphere: Rayleigh gradient + Mie glow / aureole -------------------------------
    mediump float c = max(dot(d, u_glowDir), 0.0);
    mediump float c2 = c * c;
    mediump float c4 = c2 * c2;
    mediump float c8 = c4 * c4;
    mediump float c16 = c8 * c8;
    mediump float c32 = c16 * c16;
    mediump float up = clamp(h, 0.0, 1.0);
    // horizon weight (1 at and below the horizon); the haze band is thicker towards the light
    mediump float k = 1.0 - up;
    k = k * k;
    k = k * k * (1.0 - 0.5 * c8 * (1.0 - k));
    mediump vec3 haze = u_fogColor + u_glowColor * (0.22 * c2 + 0.78 * c8);
    mediump vec3 sky = mix(u_zenith + u_glowColor * 0.30 * c4, haze, k) + u_aureole * c32;

    // --- Milky Way (night only: a uniform branch) -----------------------------------------
    if (u_night > 0.01) {
        mediump float g = dot(d, u_galaxyN);
        mediump float band = exp(-g * g * 16.0);
        mediump vec2 guv = vec2(dot(d, u_galaxyA), dot(d, u_galaxyB)) * 0.9;
        mediump vec4 gn = texture2D(u_noise, guv);
        mediump float dust = gn.g * 0.7 + gn.a * 0.6 - 0.25;
        sky += vec3(0.30, 0.34, 0.50) * band * max(dust, 0.0) * 0.16 * u_night * smoothstep(0.0, 0.25, h);
    }

    // --- Clouds ---------------------------------------------------------------------------
    mediump float cov = 0.0;
    if (h > 0.0) {
        mediump vec2 uv = cloudUv(d);
        mediump vec4 n = texture2D(u_noise, uv);
        mediump vec4 m = texture2D(u_noise, uv * 2.31 + u_wind * 0.8 + vec2(0.37, 0.11));
        mediump float body = n.r * 0.62 + n.g * 0.38;
        mediump float f = body + (m.b * 0.6 + m.a * 0.4 - 0.5) * 0.16;
        cov = cloudCover(f, h);
        if (cov > 0.003) {
            // one probe towards the light on the cloud plane: thinner there = lit edge
            mediump vec2 toL = u_glowDir.xz + vec2(0.0001, 0.0);
            toL = toL / length(toL) * 0.045;
            mediump vec4 pn = texture2D(u_noise, uv + toL);
            mediump float fl = pn.r * 0.62 + pn.g * 0.38;
            mediump float thick = smoothstep(0.0, 0.20, f - u_coverage);
            mediump float light = clamp(0.62 + (body - fl) * 1.8 - 0.30 * thick, 0.0, 1.0);
            mediump vec3 col = mix(u_cloudShadow, u_cloudLit, light);
            // golden / silver lining where the light grazes thin cloud
            col += (u_glowColor * 1.5 + u_aureole * 1.2) * (c4 * 0.35 + c16 * 0.9) * (1.0 - thick * 0.8);
            // far clouds sink into the haze
            col = mix(col, haze, k * 0.75);
            sky = mix(sky, col, cov * 0.97);
        }
    }

    // --- Sun: soft disc + corona, behind the clouds ----------------------------------------
    HP float sd = length(dir - u_sunDir);
    mediump float disc = 1.0 - smoothstep(SUN_R * 0.55, SUN_R, sd);
    mediump float sdm = sd / SUN_R;
    mediump float corona = 0.30 / (1.0 + sdm * sdm * 2.5);
    sky += u_sunDisc * (disc * 2.6 + corona) * (1.0 - cov) * smoothstep(-0.03, 0.02, h);

    // dither against banding in the dark gradients
    mediump float dn = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    gl_FragColor = vec4(visualGrade(sky) + (dn - 0.5) * u_dither, 1.0);
}
"""

    // Sun and moon share the quad vertex stage: a camera-facing square around the body direction,
    // pushed to the far plane (z = w) so the terrain in front of it hides it.
    private const val BODY_VERTEX = """#version 100
attribute vec2 a_corner;

uniform mat4 u_projView;
uniform vec3 u_camPos;
uniform vec3 u_dir;
uniform float u_radius;
uniform float u_extent;

varying vec2 v_uv;
varying float v_h;
varying vec3 v_dir;

void main() {
    vec3 up = abs(u_dir.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(up, u_dir));
    vec3 upv = cross(u_dir, right);
    vec3 d = u_dir + (right * a_corner.x + upv * a_corner.y) * u_extent;
    v_uv = a_corner;
    v_h = d.y / length(d);
    v_dir = d;
    vec4 clip = u_projView * vec4(u_camPos + d * u_radius, 1.0);
    gl_Position = vec4(clip.xy, clip.w, clip.w);
}
"""

    private const val MOON_FRAGMENT = HEADER + VisualGrade.GLSL + SkyGlow.GLSL + CLOUD_GLSL + """
uniform sampler2D u_moonMap;
uniform float u_disc;      // disc radius as a fraction of the quad half-size
uniform float u_strength;  // 0..1 (visibility)
uniform vec3 u_phaseLight; // sun direction in the disc frame (x right, y up, z towards us)
uniform float u_fullness;  // 0 new .. 1 full: brightness of the halo

varying vec2 v_uv;
varying float v_h;
varying vec3 v_dir;

void main() {
    vec2 p = v_uv / u_disc;
    float r2 = dot(p, p);
    float r = sqrt(r2);
    float cover = 1.0 - smoothstep(0.94, 1.0, r);
    vec3 col = vec3(0.0);
    if (r2 < 1.0) {
        vec3 ns = vec3(p, sqrt(1.0 - r2));
        vec4 tex = texture2D(u_moonMap, p * vec2(0.5, -0.5) + 0.5);
        vec3 nt = tex.xyz * 2.0 - 1.0;
        vec3 n = normalize(ns + vec3(nt.xy, 0.0) * 1.1);
        // soft terminator: relief catches the light where the sphere turns away
        float term = smoothstep(-0.10, 0.22, dot(ns, u_phaseLight));
        float lit = max(dot(n, u_phaseLight), 0.0) * term;
        float limb = 0.82 + 0.18 * ns.z;
        // earthshine keeps the dark side barely visible
        col = vec3(0.90, 0.92, 0.98) * tex.a * (lit * 1.45 + 0.035) * limb;
    }
    // cold halo, scaled by how much of the disc is lit
    float halo = exp(-max(r - 1.0, 0.0) * 3.0) * (0.06 + 0.16 * u_fullness) * (1.0 - cover);
    col += vec3(0.42, 0.52, 0.80) * halo;
    float horizon = smoothstep(-0.012, 0.03, v_h) * u_strength;
    float occl = 1.0 - cloudDensityAt(normalize(v_dir)) * 0.92;
    float a = cover * horizon * occl;
    gl_FragColor = vec4(visualGradeLight(col * horizon * occl), a);
}
"""

    // Stars: the direction is rotated by the sky rotation, projected, and the quad is then inflated in
    // clip space by a fixed number of pixels, so a star is the same size at any resolution.
    private const val STAR_VERTEX = """#version 100
attribute vec3 a_dir;
attribute vec2 a_corner;
attribute vec2 a_star;     // x = brightness 0..1, y = random phase 0..1

uniform mat4 u_projView;
uniform mat4 u_rotation;
uniform vec3 u_camPos;
uniform float u_radius;
uniform vec2 u_clipPerPixel;
uniform float u_time;
uniform float u_visibility;
uniform float u_pixelScale;

varying vec2 v_corner;
varying vec3 v_color;
varying vec3 v_dir;

void main() {
    vec3 d = (u_rotation * vec4(a_dir, 0.0)).xyz;
    // Thin air near the horizon dims the stars; below it they are gone
    float horizon = smoothstep(0.0, 0.16, d.y);
    float twinkle = 0.78 + 0.22 * sin(u_time * (1.3 + a_star.y * 2.2) + a_star.y * 61.0) * sin(u_time * 0.7 + a_star.y * 17.0);
    vec3 tint = mix(vec3(0.70, 0.82, 1.00), vec3(1.00, 0.88, 0.70), fract(a_star.y * 7.31));
    float b = a_star.x;
    v_color = tint * (0.40 + 0.85 * b) * twinkle * horizon * u_visibility;
    v_corner = a_corner;
    v_dir = d;
    vec4 clip = u_projView * vec4(u_camPos + d * u_radius, 1.0);
    float pixels = (1.2 + 2.0 * b * b + 0.6 * b) * u_pixelScale;
    clip.xy += a_corner * pixels * u_clipPerPixel * clip.w;
    gl_Position = vec4(clip.xy, clip.w, clip.w);
}
"""

    private const val STAR_FRAGMENT = HEADER + VisualGrade.GLSL + CLOUD_GLSL + """
varying vec2 v_corner;
varying vec3 v_color;
varying vec3 v_dir;

void main() {
    float s = 1.0 - clamp(length(v_corner), 0.0, 1.0);
    s = s * s * (3.0 - 2.0 * s);
    float clear = 1.0 - cloudDensityAt(normalize(v_dir));
    gl_FragColor = vec4(visualGradeLight(v_color * s * clear), 1.0);
}
"""
  }
}

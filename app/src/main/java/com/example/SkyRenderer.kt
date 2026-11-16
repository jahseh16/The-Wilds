package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sun, moon and stars, drawn 100 % from code (no textures or images) right after the clear colour
 * and before the terrain. The sky gradient itself is the clear colour that [DayNightCycle] already
 * provides; this class only adds the bodies on top of it:
 *
 *  - **Sun**: one small quad on the sun's direction with a hot disc and a warm halo.
 *  - **Moon**: one quad on the opposite direction with a silver disc, soft "seas" and a cool halo.
 *  - **Stars**: [STAR_COUNT] procedurally scattered points on the celestial sphere, as tiny quads of
 *    constant pixel size, fading in as the sun sets and wheeling with the sky during the night.
 *
 * Cost: three draw calls and about [STAR_COUNT] * 4 vertices. There is no full-screen pass: the
 * sun and moon quads cover a few percent of the screen, the stars a few hundred pixels. Everything
 * is drawn with depth testing and depth writes off, so the terrain, which is drawn afterwards,
 * simply paints over it (and hides whatever is below the horizon).
 *
 * The shaders are GLSL ES 1.00, which every ES 2.0 / 3.0 device accepts.
 */
class SkyRenderer : Disposable {

  private val sunProgram = compile(BODY_VERTEX, SUN_FRAGMENT)
  private val moonProgram = compile(BODY_VERTEX, MOON_FRAGMENT)
  private val starProgram = compile(STAR_VERTEX, STAR_FRAGMENT)

  /** Live visual calibration: the sun, moon and stars share the world's exposure, saturation and warmth. */
  var visual: VisualSettings = VisualSettings()

  private val quad: Mesh
  private val stars: Mesh

  private val sunColor = Color()
  private val moonColor = Color(0.86f, 0.90f, 1.00f, 1f)
  private val moonHalo = Color(0.45f, 0.55f, 0.85f, 1f)
  private val starRotation = Matrix4()
  private var clock = 0f

  init {
    quad = Mesh(true, 4, 6, VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_corner")).apply {
      setVertices(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))
      setIndices(shortArrayOf(0, 1, 2, 0, 2, 3))
    }
    stars = buildStars()
  }

  /**
   * Draws the sky bodies for the current frame. Call it right after glClear and before the terrain.
   * [delta] only drives the star twinkle.
   */
  fun render(camera: Camera, dayNight: DayNightCycle, delta: Float) {
    clock = (clock + delta) % TWINKLE_WRAP
    val radius = min(SKY_RADIUS, camera.far * 0.8f)
    val gl = Gdx.gl

    gl.glDisable(GL20.GL_DEPTH_TEST)
    gl.glDepthMask(false)
    gl.glDisable(GL20.GL_CULL_FACE)
    gl.glEnable(GL20.GL_BLEND)

    if (dayNight.starVisibility > 0.003f) {
      drawStars(camera, dayNight, radius)
    }
    if (dayNight.moonVisibility > 0.003f && dayNight.moonDirection.y > -0.15f) {
      drawMoon(camera, dayNight, radius)
    }
    if (dayNight.sunVisibility > 0.003f) {
      drawSun(camera, dayNight, radius)
    }

    gl.glDisable(GL20.GL_BLEND)
    gl.glDepthMask(true)
  }

  private fun drawSun(camera: Camera, dayNight: DayNightCycle, radius: Float) {
    // Warm and orange near the horizon, white-yellow when high
    val whiteness = smoothstep(dayNight.sunElevation / 0.55f)
    sunColor.set(SUN_WARM).lerp(SUN_WHITE, whiteness)

    val program = sunProgram
    program.bind()
    bindGrade(program)
    bindBody(program, camera, dayNight.sunDirection, radius, SUN_EXTENT, SUN_DISC / SUN_EXTENT)
    program.setUniformf("u_color", sunColor.r, sunColor.g, sunColor.b)
    program.setUniformf("u_strength", dayNight.sunVisibility)
    // Premultiplied alpha: the disc covers the sky, the halo (alpha 0) only adds light
    Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
    quad.render(program, GL20.GL_TRIANGLES)
  }

  private fun drawMoon(camera: Camera, dayNight: DayNightCycle, radius: Float) {
    val program = moonProgram
    program.bind()
    bindGrade(program)
    bindBody(program, camera, dayNight.moonDirection, radius, MOON_EXTENT, MOON_DISC / MOON_EXTENT)
    program.setUniformf("u_color", moonColor.r, moonColor.g, moonColor.b)
    program.setUniformf("u_haloColor", moonHalo.r, moonHalo.g, moonHalo.b)
    program.setUniformf("u_strength", dayNight.moonVisibility)
    Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
    quad.render(program, GL20.GL_TRIANGLES)
  }

  private fun bindGrade(program: ShaderProgram) {
    program.setUniformf("u_brightness", visual.brightness)
    program.setUniformf("u_contrast", visual.contrast)
    program.setUniformf("u_saturation", visual.saturation)
    program.setUniformf("u_warmth", visual.tintR, visual.tintG, visual.tintB)
  }

  private fun bindBody(
    program: ShaderProgram,
    camera: Camera,
    direction: com.badlogic.gdx.math.Vector3,
    radius: Float,
    extent: Float,
    discFraction: Float
  ) {
    program.setUniformMatrix("u_projView", camera.combined)
    program.setUniformf("u_camPos", camera.position)
    program.setUniformf("u_dir", direction)
    program.setUniformf("u_radius", radius)
    program.setUniformf("u_extent", extent)
    program.setUniformf("u_disc", discFraction)
  }

  private fun drawStars(camera: Camera, dayNight: DayNightCycle, radius: Float) {
    starRotation.setToRotationRad(DayNightCycle.CELESTIAL_AXIS, dayNight.starAngle)

    val program = starProgram
    program.bind()
    bindGrade(program)
    program.setUniformMatrix("u_projView", camera.combined)
    program.setUniformMatrix("u_rotation", starRotation)
    program.setUniformf("u_camPos", camera.position)
    program.setUniformf("u_radius", radius)
    program.setUniformf("u_clipPerPixel", 2f / Gdx.graphics.width, 2f / Gdx.graphics.height)
    program.setUniformf("u_time", clock)
    program.setUniformf("u_visibility", dayNight.starVisibility)
    // Stars are sized in pixels: scale them up on high-density phone screens (1.0 at 720p)
    program.setUniformf("u_pixelScale", (Gdx.graphics.height / 720f).coerceIn(1f, 3f))
    // Purely additive: a star can only brighten the sky
    Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE)
    stars.render(program, GL20.GL_TRIANGLES)
  }

  private fun buildStars(): Mesh = Mesh(
    true, STAR_COUNT * 4, STAR_COUNT * 6,
    VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_dir"),
    VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_corner"),
    VertexAttribute(VertexAttributes.Usage.Generic, 2, "a_star")
  ).apply {
    setVertices(starVertices())
    setIndices(starIndices())
  }

  override fun dispose() {
    sunProgram.dispose()
    moonProgram.dispose()
    starProgram.dispose()
    quad.dispose()
    stars.dispose()
  }

  private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
  }

  companion object {
    /** Stars on the whole sphere; roughly half are above the horizon at any time. */
    const val STAR_COUNT = 900
    private const val STAR_SEED = 20240611L

    /** Distance of the sky bodies from the camera; capped to stay inside the far plane. */
    private const val SKY_RADIUS = 100f

    /** Wrap for the twinkle clock, in seconds (keeps the float precise in long sessions). */
    private const val TWINKLE_WRAP = 3600f

    // Sizes as tangents of the angle from the body's centre. Half-extent of the quad (the halo
    // reaches its edge) and radius of the visible disc: disc 0.050 = ~2.9 deg, a touch larger than
    // real so it reads on a phone screen.
    private const val SUN_EXTENT = 0.46f
    private const val SUN_DISC = 0.050f
    private const val MOON_EXTENT = 0.22f
    private const val MOON_DISC = 0.045f

    private val SUN_WARM = Color(1.00f, 0.55f, 0.28f, 1f)
    private val SUN_WHITE = Color(1.00f, 0.97f, 0.86f, 1f)

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
          vertices[o] = dx
          vertices[o + 1] = y
          vertices[o + 2] = dz
          vertices[o + 3] = cornerX[c]
          vertices[o + 4] = cornerY[c]
          vertices[o + 5] = brightness
          vertices[o + 6] = phase
        }
      }
      return vertices
    }

    fun starIndices(count: Int = STAR_COUNT): ShortArray {
      val indices = ShortArray(count * 6)
      for (i in 0 until count) {
        val v = i * 4
        val k = i * 6
        indices[k] = v.toShort()
        indices[k + 1] = (v + 1).toShort()
        indices[k + 2] = (v + 2).toShort()
        indices[k + 3] = v.toShort()
        indices[k + 4] = (v + 2).toShort()
        indices[k + 5] = (v + 3).toShort()
      }
      return indices
    }

    private fun compile(vertex: String, fragment: String): ShaderProgram {
      val program = ShaderProgram(vertex, fragment)
      check(program.isCompiled) { "Sky shader failed to compile: ${program.log}" }
      return program
    }

    // Sun and moon share the quad vertex stage: a camera-facing square around the body direction.
    // v_h is the (approximate) sine of the elevation of each pixel's sky direction, used by the
    // fragments to hide anything below the horizon.
    private const val BODY_VERTEX = """#version 100
attribute vec2 a_corner;

uniform mat4 u_projView;
uniform vec3 u_camPos;
uniform vec3 u_dir;
uniform float u_radius;
uniform float u_extent;

varying vec2 v_uv;
varying float v_h;

void main() {
    vec3 up = abs(u_dir.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(up, u_dir));
    vec3 upv = cross(u_dir, right);
    vec3 d = u_dir + (right * a_corner.x + upv * a_corner.y) * u_extent;
    v_uv = a_corner;
    v_h = d.y / length(d);
    gl_Position = u_projView * vec4(u_camPos + d * u_radius, 1.0);
}
"""

    private const val SUN_FRAGMENT = """#version 100
precision mediump float;
""" + VisualGrade.GLSL + """

uniform vec3 u_color;
uniform float u_disc;      // disc radius as a fraction of the quad half-size
uniform float u_strength;  // 0..1

varying vec2 v_uv;
varying float v_h;

void main() {
    float r = length(v_uv);
    float rho = r / u_disc;
    float core = 1.0 - smoothstep(0.86, 1.0, rho);
    // Halo: a soft 1/(1+x^2) falloff forced to exactly 0 at the quad edge
    float edge = 1.0 - smoothstep(0.30, 1.0, r);
    float glow = edge * edge * 0.62 / (1.0 + rho * rho * 0.30);
    float horizon = smoothstep(-0.012, 0.03, v_h) * u_strength;
    // Premultiplied alpha: alpha only where the disc is, the halo just adds light
    gl_FragColor = vec4(visualGradeLight(u_color * (core * 1.4 + glow) * horizon), core * horizon);
}
"""

    private const val MOON_FRAGMENT = """#version 100
precision mediump float;
""" + VisualGrade.GLSL + """

uniform vec3 u_color;
uniform vec3 u_haloColor;
uniform float u_disc;
uniform float u_strength;

varying vec2 v_uv;
varying float v_h;

float blob(vec2 p, vec2 c, float r0, float r1) {
    return 1.0 - smoothstep(r0, r1, length(p - c));
}

void main() {
    float r = length(v_uv);
    float rho = r / u_disc;
    float cover = 1.0 - smoothstep(0.95, 1.0, rho);
    // Limb darkening of a lit sphere plus a few darker 'seas'
    float shade = 0.70 + 0.30 * sqrt(max(1.0 - rho * rho, 0.0));
    vec2 p = v_uv / u_disc;
    float seas = blob(p, vec2(-0.30, 0.22), 0.10, 0.30) +
        0.9 * blob(p, vec2(0.12, 0.38), 0.08, 0.22) +
        0.8 * blob(p, vec2(0.20, -0.20), 0.12, 0.34) +
        0.7 * blob(p, vec2(-0.44, -0.30), 0.05, 0.15) +
        0.5 * blob(p, vec2(0.46, 0.05), 0.04, 0.12);
    vec3 albedo = u_color * shade * (1.0 - 0.30 * clamp(seas, 0.0, 1.0));
    // Cool halo, 0 at the quad edge
    float edge = 1.0 - smoothstep(0.25, 1.0, r);
    float glow = edge * edge * 0.30 / (1.0 + rho * rho * 0.45);
    float horizon = smoothstep(-0.012, 0.03, v_h) * u_strength;
    gl_FragColor = vec4(visualGradeLight((albedo * cover + u_haloColor * glow) * horizon), cover * horizon);
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

void main() {
    vec3 d = (u_rotation * vec4(a_dir, 0.0)).xyz;
    // Thin air near the horizon dims the stars; below it they are gone
    float horizon = smoothstep(0.0, 0.16, d.y);
    float twinkle = 0.80 + 0.20 * sin(u_time * (1.3 + a_star.y * 2.2) + a_star.y * 61.0);
    vec3 tint = mix(vec3(0.70, 0.82, 1.00), vec3(1.00, 0.90, 0.72), fract(a_star.y * 7.31));
    float b = a_star.x;
    v_color = tint * (0.45 + 0.75 * b) * twinkle * horizon * u_visibility;
    v_corner = a_corner;
    vec4 clip = u_projView * vec4(u_camPos + d * u_radius, 1.0);
    float pixels = (1.3 + 1.7 * b) * u_pixelScale;
    clip.xy += a_corner * pixels * u_clipPerPixel * clip.w;
    gl_Position = clip;
}
"""

    private const val STAR_FRAGMENT = """#version 100
precision mediump float;
""" + VisualGrade.GLSL + """

varying vec2 v_corner;
varying vec3 v_color;

void main() {
    float s = 1.0 - clamp(length(v_corner), 0.0, 1.0);
    s = s * s * (3.0 - 2.0 * s);
    gl_FragColor = vec4(visualGradeLight(v_color * s), 1.0);
}
"""
  }
}

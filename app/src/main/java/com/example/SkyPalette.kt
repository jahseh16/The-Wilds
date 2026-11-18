package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import kotlin.math.abs

/**
 * Per-frame colours of the atmosphere, derived from [DayNightCycle] (pure CPU, unit tested). The sky
 * dome, the clouds and, crucially, the fog of the terrain, foliage and sea all read the same values,
 * so the horizon has no seam: everything fades into [skyHorizon]-coloured haze.
 *
 * Model (classic analytic sky, no ray-marching):
 *  - **Rayleigh**: a vertical gradient from [zenith] down to the horizon colour (the cycle's fog colour).
 *    The zenith deepens to violet / dark blue during twilight while the horizon burns amber.
 *  - **Mie**: forward scattering around the light source, split into a broad warm [glow] (strong when
 *    the sun is low: the sunset band) and a tight bright [aureole] around the disc. By night the moon
 *    takes the sun's place with a faint, cold version of both.
 *  - **Clouds**: lit colour (gold / copper at sunset, white by day, moon-grey by night) and shadowed colour.
 */
class SkyPalette {

  /** Light source for the scattering and the cloud lighting: the sun, or the moon at night. */
  val lightDir = Vector3(0f, 1f, 0f)
  val sunDir = Vector3(0f, 1f, 0f)
  val moonDir = Vector3(0f, -1f, 0f)

  val zenith = Color()
  /** Horizon / fog base colour (= [DayNightCycle.fogColor]). */
  val horizon = Color()
  val glow = Color()
  val aureole = Color()
  /** Sun disc colour (HDR, > 1) including its visibility. */
  val sunDisc = Color()
  val cloudLit = Color()
  val cloudShadow = Color()

  /** 0 by day .. 1 in full night (stars, Milky Way, moonlit clouds). */
  var night = 0f
    private set
  /** 1 around sunrise / sunset, 0 at noon and midnight. */
  var twilight = 0f
    private set
  var cloudCoverage = 0.50f

  fun update(cycle: DayNightCycle) {
    val e = cycle.sunElevation
    sunDir.set(cycle.sunDirection)
    moonDir.set(cycle.moonDirection)
    val day = smooth(e / 0.35f)
    twilight = 1f - smooth(abs(e - 0.03f) / 0.32f)
    night = 1f - smooth((e + 0.20f) / 0.22f)
    val sunGlow = smooth((e + 0.15f) / 0.20f)
    val moonGlow = smooth((-e - 0.15f) / 0.25f) * cycle.moonVisibility

    horizon.set(cycle.fogColor)
    mix(TWILIGHT_ZENITH, DAY_ZENITH, day, zenith)
    lerp(zenith, NIGHT_ZENITH, night)

    if (e > -0.15f) {
      lightDir.set(sunDir)
      glow.set(SUNSET_GLOW).mul(0.62f * twilight * sunGlow)
      glow.add(DAY_GLOW.r * day, DAY_GLOW.g * day, DAY_GLOW.b * day, 0f)
      val whiteness = smooth(e / 0.45f)
      mix(SUN_LOW, SUN_HIGH, whiteness, aureole)
      aureole.mul((0.30f + 0.45f * twilight) * sunGlow)
    } else {
      lightDir.set(moonDir)
      glow.set(MOON_GLOW).mul(moonGlow)
      aureole.set(MOON_AUREOLE).mul(moonGlow)
    }

    mix(SUN_LOW, SUN_HIGH, smooth(e / 0.45f), sunDisc)
    sunDisc.mul(SUN_INTENSITY * cycle.sunVisibility)

    mix(SUNSET_CLOUD_LIT, DAY_CLOUD_LIT, day, cloudLit)
    lerp(cloudLit, NIGHT_CLOUD_LIT, night)
    mix(SUNSET_CLOUD_SHADOW, DAY_CLOUD_SHADOW, day, cloudShadow)
    lerp(cloudShadow, NIGHT_CLOUD_SHADOW, night)
    for (c in arrayOf(zenith, horizon, glow, aureole, sunDisc, cloudLit, cloudShadow)) c.a = 1f
  }

  /**
   * CPU mirror of the shaders' `skyHorizon()`: the haze colour seen along [dir] (unit vector).
   * [out] receives r, g, b.
   */
  fun skyHorizon(dir: Vector3, out: FloatArray) {
    val c = maxOf(dir.dot(lightDir), 0f)
    val c2 = c * c; val c4 = c2 * c2; val c8 = c4 * c4; val c16 = c8 * c8; val c32 = c16 * c16
    val g = 0.22f * c2 + 0.78f * c8
    out[0] = horizon.r + glow.r * g + aureole.r * c32
    out[1] = horizon.g + glow.g * g + aureole.g * c32
    out[2] = horizon.b + glow.b * g + aureole.b * c32
  }

  private fun mix(a: Color, b: Color, t: Float, out: Color) { out.set(a).lerp(b, t) }
  private fun lerp(c: Color, target: Color, t: Float) { c.lerp(target, t) }
  private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

  companion object {
    private val DAY_ZENITH = Color(0.20f, 0.35f, 0.60f, 1f)
    private val TWILIGHT_ZENITH = Color(0.14f, 0.13f, 0.27f, 1f)
    private val NIGHT_ZENITH = Color(0.004f, 0.007f, 0.022f, 1f)
    private val SUNSET_GLOW = Color(1.00f, 0.40f, 0.12f, 1f)
    private val DAY_GLOW = Color(0.16f, 0.14f, 0.10f, 1f)
    private val MOON_GLOW = Color(0.030f, 0.040f, 0.065f, 1f)
    private val MOON_AUREOLE = Color(0.10f, 0.12f, 0.17f, 1f)
    private val SUN_LOW = Color(1.00f, 0.50f, 0.22f, 1f)
    private val SUN_HIGH = Color(1.00f, 0.95f, 0.86f, 1f)
    private const val SUN_INTENSITY = 1.9f
    private val DAY_CLOUD_LIT = Color(0.93f, 0.92f, 0.90f, 1f)
    private val DAY_CLOUD_SHADOW = Color(0.50f, 0.55f, 0.62f, 1f)
    private val SUNSET_CLOUD_LIT = Color(1.00f, 0.60f, 0.30f, 1f)
    private val SUNSET_CLOUD_SHADOW = Color(0.30f, 0.21f, 0.27f, 1f)
    private val NIGHT_CLOUD_LIT = Color(0.075f, 0.090f, 0.13f, 1f)
    private val NIGHT_CLOUD_SHADOW = Color(0.012f, 0.016f, 0.028f, 1f)
  }
}

/** GLSL shared by every shader that fades into the sky: the haze colour along a view direction. */
object SkyGlow {
  /** Needs `u_glowDir`, `u_glowColor`, `u_aureole`; the fog colour is passed in (it is declared later in each shader). */
  const val GLSL = """
uniform mediump vec3 u_glowDir;
uniform mediump vec3 u_glowColor;
uniform mediump vec3 u_aureole;

mediump vec3 skyHorizon(mediump vec3 dir, mediump vec3 fogColor) {
    mediump float c = max(dot(dir, u_glowDir), 0.0);
    mediump float c2 = c * c;
    mediump float c4 = c2 * c2;
    mediump float c8 = c4 * c4;
    mediump float c16 = c8 * c8;
    return fogColor + u_glowColor * (0.22 * c2 + 0.78 * c8) + u_aureole * (c16 * c16);
}
"""

  /** Cached locations for one program. */
  class Locations(program: com.badlogic.gdx.graphics.glutils.ShaderProgram) {
    private val dir = program.getUniformLocation("u_glowDir")
    private val glow = program.getUniformLocation("u_glowColor")
    private val aureole = program.getUniformLocation("u_aureole")

    fun apply(program: com.badlogic.gdx.graphics.glutils.ShaderProgram, p: SkyPalette) {
      program.setUniformf(dir, p.lightDir)
      program.setUniformf(glow, p.glow.r, p.glow.g, p.glow.b)
      program.setUniformf(aureole, p.aureole.r, p.aureole.g, p.aureole.b)
    }
  }
}

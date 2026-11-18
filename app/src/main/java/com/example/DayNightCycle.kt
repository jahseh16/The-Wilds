package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** The four keyframes of the day. [keyTime] is where the phase is at its purest, in 0..1. */
enum class DayPhase(val label: String, val keyTime: Float) {
  DAWN("AMANECER", 0.00f),
  DAY("DIA", 0.25f),
  DUSK("ATARDECER", 0.50f),
  NIGHT("NOCHE", 0.75f);

  fun next(): DayPhase = ALL[(ordinal + 1) % ALL.size]

  companion object {
    /** Cached copy: values() allocates a new array on every call. */
    val ALL: Array<DayPhase> = values()
  }
}

/**
 * Procedural day/night clock. No textures: everything is derived from [timeOfDay] (0..1, one full
 * cycle every [cycleSeconds]) by smoothly blending four keyframes (dawn, day, dusk, night).
 *
 * It owns no GL state. [update] advances the clock and recomputes the public colours/direction;
 * [applyTo] pushes them into a libGDX [Environment] and the sun light, and the caller uses
 * [skyColor] for glClearColor and [fogDensity] for its shader.
 *
 * Sun path: rises in the east at DAWN, is highest at DAY, sets at DUSK. While it is below the
 * horizon the same directional light becomes a dim blue moon on the opposite side of the sky;
 * both fade to zero exactly at the horizon, so the switch is invisible.
 */
class DayNightCycle(
  startPhase: DayPhase = DayPhase.DAY,
  val cycleSeconds: Float = DEFAULT_CYCLE_SECONDS
) {

  var timeOfDay: Float = startPhase.keyTime
    private set

  var phase: DayPhase = startPhase
    private set

  /** Colour for glClearColor. */
  val skyColor = Color()
  val fogColor = Color()
  val ambientColor = Color()

  /** Colour of the single directional light (sun or moon, already scaled by its intensity). */
  val lightColor = Color()

  /** Direction the light travels (from the sky towards the ground; y is never positive). */
  val lightDirection = Vector3()

  /** Exponential-squared fog density, in 1/metre. */
  var fogDensity: Float = 0f
    private set

  /** Sun elevation: +1 straight up, 0 at the horizon, negative at night. */
  var sunElevation: Float = 0f
    private set

  /** Unit vector from the viewer towards the sun (pointing below the horizon at night). */
  val sunDirection = Vector3(0f, 1f, 0f)

  /** Unit vector towards the moon: always exactly opposite the sun. */
  val moonDirection = Vector3(0f, -1f, 0f)

  /** 0 = sun disc not drawn (well below the horizon), 1 = fully visible. */
  var sunVisibility: Float = 1f
    private set

  /** 0 by day, 1 at full night; fades the moon in as the sun sets and out at dawn. */
  var moonVisibility: Float = 0f
    private set

  /** 0 by day, 1 at full night; the stars fade in once the sun is below the horizon. */
  var starVisibility: Float = 0f
    private set

  /**
   * Rotation of the star dome around [CELESTIAL_AXIS] in radians: one full turn per cycle, in step
   * with the sun and the moon, so the stars wheel across the sky during the night.
   */
  val starAngle: Float
    get() = timeOfDay * 2f * PI.toFloat()

  private val scratch = Color()
  private val sunPos = Vector3()

  init {
    refresh()
  }

  fun update(delta: Float) {
    timeOfDay = (timeOfDay + delta / cycleSeconds) % 1f
    refresh()
  }

  /** Jumps straight to the keyframe of the next phase (handy for testing the lighting). */
  fun skipToNextPhase() {
    timeOfDay = phase.next().keyTime
    refresh()
  }

  /** Writes ambient, fog and the main directional light into the environment. */
  fun applyTo(environment: Environment, sun: DirectionalLight) {
    (environment.get(ColorAttribute.AmbientLight) as? ColorAttribute)?.color?.set(ambientColor)
    (environment.get(ColorAttribute.Fog) as? ColorAttribute)?.color?.set(fogColor)
    sun.set(lightColor, lightDirection)
  }

  private fun refresh() {
    phase = DayPhase.ALL[floor(((timeOfDay + 0.125f) % 1f) * 4f).toInt() % 4]

    // Blend between the two surrounding keyframes with a smoothstep so each phase "holds" a bit
    val position = timeOfDay * 4f
    val from = floor(position).toInt() % 4
    val to = (from + 1) % 4
    val t = smoothstep(position - floor(position))

    blend(SKY, from, to, t, skyColor)
    blend(FOG, from, to, t, fogColor)
    blend(AMBIENT, from, to, t, ambientColor)
    fogDensity = FOG_DENSITY[from] + (FOG_DENSITY[to] - FOG_DENSITY[from]) * t

    // Sun position on its arc (computed first: the twilight below needs the elevation) The 0.85 / 0.15 skew keeps the sun a little above the horizon at
    // the DAWN and DUSK keyframes (warm low light) instead of exactly on it.
    val angle = timeOfDay * 2f * PI.toFloat()
    val elevation = (0.85f * sin(angle) + 0.15f).coerceIn(-1f, 1f)
    val horizontal = sqrt(1f - elevation * elevation)
    sunPos.set(cos(angle) * horizontal, elevation, 0.35f * horizontal).nor()
    sunElevation = sunPos.y
    sunDirection.set(sunPos)
    moonDirection.set(sunPos).scl(-1f)
    sunVisibility = smoothstep((sunElevation + 0.12f) / 0.20f)
    moonVisibility = smoothstep((0.02f - sunElevation) / 0.25f)
    starVisibility = smoothstep((0.10f - sunElevation) / 0.32f)

    // Twilight follows the real sun: once it is well below the horizon the keyframe colours give way
    // to the night ones, so no warm band lingers at the horizon in the middle of the night
    val dark = smoothstep((-sunElevation - 0.10f) / 0.28f)
    if (dark > 0f) {
      skyColor.lerp(SKY[NIGHT_INDEX], dark)
      fogColor.lerp(FOG[NIGHT_INDEX], dark)
      ambientColor.lerp(AMBIENT[NIGHT_INDEX], dark)
      fogDensity += (FOG_DENSITY[NIGHT_INDEX] - fogDensity) * dark
    }

    if (sunElevation >= 0f) {
      val intensity = smoothstep(sunElevation / SUN_FULL_ELEVATION) * SUN_GAIN
      val whiteness = smoothstep(sunElevation / SUN_WHITE_ELEVATION)
      scratch.set(SUN_WARM).lerp(SUN_WHITE, whiteness)
      lightColor.set(scratch.r * intensity, scratch.g * intensity, scratch.b * intensity, 1f)
      lightDirection.set(sunPos).scl(-1f)
    } else {
      val intensity = smoothstep(-sunElevation / MOON_FULL_ELEVATION) * MOON_GAIN
      lightColor.set(MOON.r * intensity, MOON.g * intensity, MOON.b * intensity, 1f)
      // Moon sits opposite the sun, so its light travels along +sunPos (still pointing downwards)
      lightDirection.set(sunPos)
    }
  }

  private fun blend(keys: Array<Color>, from: Int, to: Int, t: Float, out: Color) {
    out.set(keys[from]).lerp(keys[to], t)
    out.a = 1f
  }

  private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
  }

  companion object {
    const val DEFAULT_CYCLE_SECONDS = 720f // 12 minutes
    private const val NIGHT_INDEX = 3

    /**
     * Axis the sun's arc turns around (perpendicular to its path: east -> zenith, with the slight
     * southward lean of the arc). The star dome rotates around it.
     */
    val CELESTIAL_AXIS: Vector3 = Vector3(-0.330f, 0f, 0.944f)

    private const val SUN_GAIN = 0.95f
    private const val SUN_FULL_ELEVATION = 0.30f
    private const val SUN_WHITE_ELEVATION = 0.55f
    private const val MOON_GAIN = 0.22f
    private const val MOON_FULL_ELEVATION = 0.30f

    private val SUN_WARM = Color(0.98f, 0.60f, 0.36f, 1f)
    private val SUN_WHITE = Color(0.96f, 0.93f, 0.86f, 1f)
    private val MOON = Color(0.50f, 0.62f, 0.95f, 1f)

    // Keyframes, indexed like DayPhase.ordinal: DAWN, DAY, DUSK, NIGHT
    // Natural, desaturated palette: a slightly overcast blue-grey day, muted amber at the horizon
    // at dawn and dusk. The fog is a touch lighter than the sky so the far land and sea read as haze.
    private val SKY = arrayOf(
      Color(0.76f, 0.58f, 0.47f, 1f),
      Color(0.43f, 0.55f, 0.68f, 1f),
      Color(0.72f, 0.45f, 0.33f, 1f),
      Color(0.015f, 0.025f, 0.06f, 1f)
    )
    private val FOG = arrayOf(
      Color(0.79f, 0.61f, 0.50f, 1f),
      Color(0.47f, 0.58f, 0.70f, 1f),
      Color(0.74f, 0.47f, 0.35f, 1f),
      Color(0.015f, 0.022f, 0.05f, 1f)
    )
    private val AMBIENT = arrayOf(
      Color(0.38f, 0.34f, 0.35f, 1f),
      Color(0.44f, 0.47f, 0.50f, 1f),
      Color(0.37f, 0.31f, 0.30f, 1f),
      Color(0.030f, 0.040f, 0.085f, 1f)
    )
    // Exponential-squared fog density (1/m). Lower than before so far hills and the sea horizon stay
    // readable: by day ~20 % haze at 200 m and ~70 % at 450 m; night keeps the closed-in feeling.
    private val FOG_DENSITY = floatArrayOf(0.0042f, 0.0020f, 0.0038f, 0.0085f)
  }
}

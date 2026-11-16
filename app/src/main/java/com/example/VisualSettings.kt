package com.example

import com.badlogic.gdx.graphics.glutils.ShaderProgram
import kotlin.math.max

/** One user-tunable visual parameter: its preference key, UI label, range and default. */
enum class VisualParam(
  val key: String,
  val label: String,
  val min: Float,
  val max: Float,
  val default: Float,
  /** Slider granularity (also the number of decimals shown: 0.01 -> 2). */
  val step: Float = 0.01f
) {
  BRIGHTNESS("brightness", "Brillo / Exposicion", 0.50f, 1.60f, 1.00f),
  CONTRAST("contrast", "Contraste general", 0.70f, 1.60f, 1.10f),
  SATURATION("saturation", "Saturacion", 0.30f, 1.50f, 0.95f),
  SUN_INTENSITY("sun_intensity", "Luz solar / ambiental", 0.50f, 1.80f, 1.00f),
  TERRAIN_CONTRAST("terrain_contrast", "Contraste del suelo", 0.60f, 1.80f, 1.00f),
  NORMAL_STRENGTH("normal_strength", "Relieve del suelo", 0.50f, 2.20f, 1.00f),
  WARMTH("warmth", "Temperatura (frio / calido)", -0.30f, 0.30f, 0.00f),
  FLASHLIGHT_INTENSITY("flashlight_intensity", "Potencia de linterna", 0.50f, 2.00f, 1.00f),
  FLASHLIGHT_SOFTNESS("flashlight_softness", "Suavizado del haz", 0.00f, 1.00f, 0.50f);

  fun clamp(v: Float): Float = if (v.isNaN()) default else v.coerceIn(min, max)
}

/**
 * The live visual calibration. A plain object (no GL): the shaders read it every frame in `begin()`,
 * the panel writes it, and [save] / [load] persist it through a key/value store (libGDX Preferences
 * in the game, a map in the tests).
 *
 * Everything here is applied with zero extra passes: grading is a handful of ALU ops at the end of
 * each existing fragment shader (see [VisualGrade]), light multipliers are folded into uniforms on
 * the CPU.
 */
class VisualSettings {

  private val values = FloatArray(VisualParam.values().size) { VisualParam.values()[it].default }

  /** Bumped on every change so listeners (and the autosave) can cheaply tell something moved. */
  var revision: Int = 0
    private set

  operator fun get(param: VisualParam): Float = values[param.ordinal]

  operator fun set(param: VisualParam, value: Float) {
    val v = param.clamp(value)
    if (values[param.ordinal] != v) {
      values[param.ordinal] = v
      revision++
    }
  }

  val brightness: Float get() = this[VisualParam.BRIGHTNESS]
  val contrast: Float get() = this[VisualParam.CONTRAST]
  val saturation: Float get() = this[VisualParam.SATURATION]
  val sunIntensity: Float get() = this[VisualParam.SUN_INTENSITY]
  val terrainContrast: Float get() = this[VisualParam.TERRAIN_CONTRAST]
  val normalStrength: Float get() = this[VisualParam.NORMAL_STRENGTH]
  val warmth: Float get() = this[VisualParam.WARMTH]
  val flashlightIntensity: Float get() = this[VisualParam.FLASHLIGHT_INTENSITY]
  val flashlightSoftness: Float get() = this[VisualParam.FLASHLIGHT_SOFTNESS]

  /** Per-channel multiplier for [warmth]: > 0 pushes towards golden, < 0 towards a cold blue. */
  val tintR: Float get() = 1f + WARM_R * warmth
  val tintG: Float get() = 1f + WARM_G * warmth
  val tintB: Float get() = 1f - WARM_B * warmth

  fun isDefault(): Boolean = VisualParam.values().all { this[it] == it.default }

  fun resetToDefaults() {
    for (p in VisualParam.values()) this[p] = p.default
  }

  /** Reads every parameter with [read] (key, default) -> value; missing or invalid values fall back to the default. */
  fun load(read: (key: String, default: Float) -> Float) {
    for (p in VisualParam.values()) this[p] = p.clamp(read(PREF_PREFIX + p.key, p.default))
  }

  fun save(write: (key: String, value: Float) -> Unit) {
    for (p in VisualParam.values()) write(PREF_PREFIX + p.key, this[p])
  }

  /**
   * CPU mirror of the shaders' `visualGrade()`: used for the clear colour so the sky matches the fogged,
   * graded geometry at the horizon. [out] receives r, g, b.
   */
  fun grade(r: Float, g: Float, b: Float, out: FloatArray) {
    val k = brightness
    var cr = r * k * tintR
    var cg = g * k * tintG
    var cb = b * k * tintB
    val c = contrast
    cr = max((cr - GRADE_PIVOT) * c + GRADE_PIVOT, 0f)
    cg = max((cg - GRADE_PIVOT) * c + GRADE_PIVOT, 0f)
    cb = max((cb - GRADE_PIVOT) * c + GRADE_PIVOT, 0f)
    val l = cr * 0.30f + cg * 0.59f + cb * 0.11f
    val s = saturation
    out[0] = l + (cr - l) * s
    out[1] = l + (cg - l) * s
    out[2] = l + (cb - l) * s
  }

  companion object {
    const val PREFS_NAME = "the_wilds_visual_prefs"
    const val PREF_PREFIX = "visual_"

    /** Mid-dark pivot: contrast deepens shadows without crushing the (already dark) night scenes. */
    const val GRADE_PIVOT = 0.22f
    const val WARM_R = 0.60f
    const val WARM_G = 0.10f
    const val WARM_B = 0.65f
  }
}

/**
 * GLSL shared by the terrain, foliage, water and sky programs, plus the uniform plumbing.
 * The uniform names follow the panel: `u_brightness`, `u_contrast`, `u_saturation`, `u_warmth`
 * (per-channel tint derived from the warmth slider) and, for the lit shaders, `u_flashlightSoftness`.
 */
object VisualGrade {

  /** Uniform declarations + `visualGrade()` (full grade) and `visualGradeLight()` (no contrast: for additive sky bodies). */
  const val GLSL = """
uniform mediump float u_brightness;
uniform mediump float u_contrast;
uniform mediump float u_saturation;
uniform mediump vec3 u_warmth;

mediump vec3 visualGrade(mediump vec3 c) {
    c *= u_brightness * u_warmth;
    c = max((c - 0.22) * u_contrast + 0.22, vec3(0.0));
    mediump float l = dot(c, vec3(0.30, 0.59, 0.11));
    return mix(vec3(l), c, u_saturation);
}

mediump vec3 visualGradeLight(mediump vec3 c) {
    c *= u_brightness * u_warmth;
    mediump float l = dot(c, vec3(0.30, 0.59, 0.11));
    return mix(vec3(l), c, u_saturation);
}
"""

  /** Flashlight cone profile. `cone` is 0 at the cutoff angle and 1 on the axis; softness 0..1 widens the edge ramp. */
  const val SPOT_GLSL = """
uniform mediump float u_flashlightSoftness;

mediump float spotShape(mediump float cone) {
    mediump float edge = mix(0.05, 0.85, u_flashlightSoftness);
    return smoothstep(0.0, edge, cone) * (0.40 + 0.60 * cone * cone);
}
"""

  /** Cached uniform locations of the grade (and optionally the spot softness) for one program. */
  class Locations(program: ShaderProgram, withSpot: Boolean) {
    private val brightness = program.getUniformLocation("u_brightness")
    private val contrast = program.getUniformLocation("u_contrast")
    private val saturation = program.getUniformLocation("u_saturation")
    private val warmth = program.getUniformLocation("u_warmth")
    private val softness = if (withSpot) program.getUniformLocation("u_flashlightSoftness") else -1

    /** The program must already be bound. */
    fun apply(program: ShaderProgram, s: VisualSettings) {
      program.setUniformf(brightness, s.brightness)
      program.setUniformf(contrast, s.contrast)
      program.setUniformf(saturation, s.saturation)
      program.setUniformf(warmth, s.tintR, s.tintG, s.tintB)
      if (softness >= 0) program.setUniformf(softness, s.flashlightSoftness)
    }
  }
}

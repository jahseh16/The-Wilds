package com.example

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The "feel the ground" layer of the first-person camera: everything that makes the player read as a
 * body with weight instead of a floating camera. Pure maths (no GL), driven once per frame by the
 * real movement, so it is unit tested.
 *
 *  - **Head bob** from the gait: one stride phase advances with the distance actually walked (so it
 *    stops when a wall stops you), stride length grows with speed (walk -> run). The vertical bob has
 *    a sharp low point when the foot plants and a soft high point mid-stride; the head also sways a
 *    little sideways (one cycle every two steps). Its envelope fades in / out smoothly.
 *  - **Footsteps**: one [StepListener] call per foot plant (the lowest point of the bob), so the sound
 *    is synchronised with what you see; landings from a fall trigger one too.
 *  - **Landing inertia**: a spring-damper dip (~0.12 m after a jump) with an elastic recovery.
 *  - **Strafe tilt**: a slight roll towards the side you are moving to.
 */
class PlayerController {

  fun interface StepListener {
    /** A foot touched the ground. [intensity] ~0.3 (slow walk) .. 1 (run), > 1 for hard landings. */
    fun onStep(intensity: Float, landing: Boolean)
  }

  var stepListener: StepListener? = null

  /** Camera offsets to add to the eye position (metres) and the roll (degrees, + = lean right). */
  var bobVertical = 0f
    private set
  var bobLateral = 0f
    private set
  var rollDegrees = 0f
    private set
  /** Landing spring offset (metres, negative = dipped). */
  var landingOffset = 0f
    private set

  /** Total eye height offset this frame. */
  val eyeOffset: Float get() = bobVertical + landingOffset

  /** Stride phase (radians): a foot plants every PI. Exposed for the viewmodel bob. */
  var phase = 0f
    private set

  /** 0..1 bob envelope (0 standing still). */
  var envelope = 0f
    private set

  var stepsTaken = 0
    private set

  private var smoothedSpeed = 0f
  private var landingVelocity = 0f
  private var strafeRoll = 0f
  private var lastPlant = 0

  /**
   * @param speed horizontal speed actually achieved this frame (m/s, after collisions)
   * @param strafe sideways input -1 (left) .. 1 (right)
   * @param grounded feet on the floor
   * @param crawling crawling stance (lower, slower, softer gait)
   * @param landingSpeed > 0 only on the frame the player lands: the downward speed (m/s)
   */
  fun update(dt: Float, speed: Float, strafe: Float, grounded: Boolean, crawling: Boolean, landingSpeed: Float = 0f) {
    if (dt <= 0f) return
    smoothedSpeed += (speed - smoothedSpeed) * min(1f, dt * SPEED_SMOOTHING)
    val moving = grounded && speed > MIN_GAIT_SPEED
    val targetEnvelope = if (moving) 1f else 0f
    val rate = if (moving) ENVELOPE_IN else ENVELOPE_OUT
    envelope += (targetEnvelope - envelope) * min(1f, dt * rate)

    // Gait: the phase follows the distance walked
    if (grounded) {
      val v = smoothedSpeed
      val stride = if (crawling) CRAWL_STRIDE else STRIDE_BASE + STRIDE_PER_SPEED * v
      phase += (PI.toFloat() * v / stride) * dt
      if (phase > 1000f * PI.toFloat()) phase -= 1000f * PI.toFloat()
      val plant = (phase / PI.toFloat()).toInt()
      if (plant != lastPlant) {
        lastPlant = plant
        if (moving) {
          stepsTaken++
          val intensity = if (crawling) 0.25f else (0.30f + 0.70f * (v / RUN_SPEED)).coerceIn(0.3f, 1f)
          stepListener?.onStep(intensity, false)
        }
      }
    } else {
      lastPlant = (phase / PI.toFloat()).toInt()
    }

    val amp = envelope * (if (crawling) CRAWL_AMPLITUDE else BOB_BASE + BOB_PER_SPEED * smoothedSpeed.coerceAtMost(RUN_SPEED))
    val s = abs(sin(phase))
    // sharp low point at the plant (s = 0), round crest mid-stride
    bobVertical = amp * (s * 2f - 1f) * 0.5f
    bobLateral = amp * LATERAL_RATIO * sin(phase)

    // Landing spring (x'' = -k x - c x')
    if (landingSpeed > LANDING_MIN_SPEED) {
      landingVelocity -= min(landingSpeed * LANDING_IMPULSE, LANDING_MAX_IMPULSE)
      stepsTaken++
      stepListener?.onStep((0.6f + landingSpeed / 8f).coerceAtMost(1.6f), true)
    }
    var left = dt
    while (left > 0f) {
      val h = min(left, 1f / 240f)
      left -= h
      val acc = -SPRING_K * landingOffset - SPRING_C * landingVelocity
      landingVelocity += acc * h
      landingOffset += landingVelocity * h
    }
    if (abs(landingOffset) < 1e-5f && abs(landingVelocity) < 1e-4f) { landingOffset = 0f; landingVelocity = 0f }

    // Roll: lean into strafes + a whisper of gait sway
    val targetRoll = strafe.coerceIn(-1f, 1f) * STRAFE_ROLL * (if (grounded) 1f else 0.5f)
    strafeRoll += (targetRoll - strafeRoll) * (1f - exp(-dt * ROLL_SMOOTHING))
    rollDegrees = strafeRoll + envelope * GAIT_ROLL * sin(phase)
  }

  companion object {
    const val RUN_SPEED = 5.5f
    const val MIN_GAIT_SPEED = 0.3f
    const val STRIDE_BASE = 0.60f
    const val STRIDE_PER_SPEED = 0.18f
    const val CRAWL_STRIDE = 0.45f
    const val BOB_BASE = 0.018f
    const val BOB_PER_SPEED = 0.008f
    const val CRAWL_AMPLITUDE = 0.012f
    const val LATERAL_RATIO = 0.55f
    const val SPEED_SMOOTHING = 8f
    const val ENVELOPE_IN = 6f
    const val ENVELOPE_OUT = 4f
    const val LANDING_MIN_SPEED = 2.5f
    const val LANDING_IMPULSE = 0.36f
    const val LANDING_MAX_IMPULSE = 3.6f
    const val SPRING_K = 170f
    const val SPRING_C = 11.7f
    const val STRAFE_ROLL = 1.6f
    const val GAIT_ROLL = 0.35f
    const val ROLL_SMOOTHING = 7f
  }
}

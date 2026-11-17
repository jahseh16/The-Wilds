package com.example

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * In-memory PCM synthesis used as a fallback when the real sound files are not in assets/sounds/.
 * Everything is deterministic (fixed seeds) and mono, 16-bit, [SAMPLE_RATE] Hz, so the fallback
 * sounds identical on every run and can be unit-tested without any audio device.
 */
object SynthSounds {

  const val SAMPLE_RATE = 22050

  /** Soft footstep on dirt: a short low-passed noise burst plus a low thump. */
  fun step(): ShortArray {
    val rng = Random(1234)
    val n = (SAMPLE_RATE * 0.20f).toInt()
    val out = ShortArray(n)
    var lowPass = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      val attack = (t / 0.003f).coerceAtMost(1f)
      lowPass += 0.30f * ((rng.nextFloat() * 2f - 1f) - lowPass)
      val crunch = lowPass * exp(-t * 26f) * 0.95f
      val thump = sin(2f * PI.toFloat() * 85f * t) * exp(-t * 42f) * 0.55f
      out[i] = toPcm((crunch + thump) * attack * 0.80f)
    }
    return out
  }

  /** Shovel hitting earth: a pitch-dropping thud with a gritty crunch right after the impact. */
  fun dig(): ShortArray {
    val rng = Random(4321)
    val n = (SAMPLE_RATE * 0.38f).toInt()
    val out = ShortArray(n)
    var phase = 0f
    var lowPass = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      val attack = (t / 0.002f).coerceAtMost(1f)
      val freq = 55f + 95f * exp(-t * 18f) // sweeps ~150 Hz -> 55 Hz
      phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
      val thud = sin(phase) * exp(-t * 11f) * 0.85f

      val noise = rng.nextFloat() * 2f - 1f
      lowPass += 0.55f * (noise - lowPass)
      val grit = (noise - lowPass) * exp(-t * 13f) * 0.50f // high-passed noise
      val crunchT = t - 0.07f
      val crunch = if (crunchT > 0f) lowPass * exp(-crunchT * 30f) * 0.55f else 0f

      out[i] = toPcm((thud + grit + crunch) * attack * 0.85f)
    }
    return out
  }

  /**
   * Soft wind loop of [seconds] seconds. Slowly breathing, low-passed noise. The loop point is made
   * seamless with a circular cross-fade, and the breathing uses a whole number of cycles.
   */
  fun wind(seconds: Float = 6f): ShortArray {
    val rng = Random(777)
    val n = (SAMPLE_RATE * seconds).toInt()
    val fade = (SAMPLE_RATE * 0.75f).toInt()
    val raw = FloatArray(n + fade)
    var a = 0f
    var b = 0f
    for (i in raw.indices) {
      val noise = rng.nextFloat() * 2f - 1f
      a += 0.020f * (noise - a)
      b += 0.060f * (a - b)
      raw[i] = b
    }

    val loop = FloatArray(n)
    for (i in 0 until n) {
      loop[i] = if (i < fade) {
        // Head blends from the extra tail material into the true start: the last sample (raw[n-1])
        // is followed by loop[0] == raw[n], which is the natural continuation of the noise
        val w = i.toFloat() / fade
        val s = w * w * (3f - 2f * w)
        raw[n + i] * (1f - s) + raw[i] * s
      } else {
        raw[i]
      }
    }

    var peak = 1e-6f
    for (v in loop) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
    val out = ShortArray(n)
    for (i in 0 until n) {
      val t = i.toFloat() / n // 0..1 across the loop
      val breathing = 0.65f + 0.25f * sin(2f * PI.toFloat() * 2f * t) + 0.10f * sin(2f * PI.toFloat() * 5f * t)
      out[i] = toPcm(loop[i] / peak * breathing * 0.9f)
    }
    return out
  }

  /** What the player is walking on: picks the timbre of the footstep. */
  enum class Surface { FOREST, GRASS, SAND, ROCK }

  /**
   * One footstep on [surface]; [variant] (0..2) changes the seed and the tuning slightly so a walk
   * never repeats the exact same sample.
   *  - FOREST: dry crunch of needles and twigs (sparse high-passed crackles) over a soft heel thump.
   *  - GRASS: muffled earth thump with a light rustle.
   *  - SAND: soft, slower "shh" (low-passed noise swell), barely any thump.
   *  - ROCK: hard click and a short knock resonance, a little grit.
   */
  fun footstep(surface: Surface, variant: Int): ShortArray {
    val rng = Random(9001 + surface.ordinal * 31 + variant * 7)
    val tune = 1f + (variant - 1) * 0.07f
    val dur = when (surface) { Surface.SAND -> 0.26f; Surface.FOREST -> 0.22f; Surface.ROCK -> 0.16f; Surface.GRASS -> 0.20f }
    val n = (SAMPLE_RATE * dur).toInt()
    val out = ShortArray(n)
    var lp = 0f; var lp2 = 0f; var phase = 0f; var crackle = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      val noise = rng.nextFloat() * 2f - 1f
      lp += 0.25f * (noise - lp)
      lp2 += 0.08f * (noise - lp2)
      val sample = when (surface) {
        Surface.FOREST -> {
          // sparse crackles: random impulses that ring for a few samples
          if (rng.nextFloat() < 0.020f * exp(-t * 9f) + 0.002f) crackle = (rng.nextFloat() * 0.8f + 0.4f) * (if (rng.nextBoolean()) 1f else -1f)
          crackle *= 0.80f
          val hp = noise - lp
          val thump = sin(2f * PI.toFloat() * 70f * tune * t) * exp(-t * 38f) * 0.30f
          crackle * 1.0f + hp * exp(-t * 20f) * 0.30f + thump
        }
        Surface.GRASS -> {
          val thump = sin(2f * PI.toFloat() * 78f * tune * t) * exp(-t * 34f) * 0.60f
          thump + lp * exp(-t * 22f) * 0.35f
        }
        Surface.SAND -> {
          val swell = (t / 0.03f).coerceAtMost(1f) * exp(-t * 13f)
          lp * swell * 0.15f + lp2 * swell * 1.3f + sin(2f * PI.toFloat() * 60f * t) * exp(-t * 45f) * 0.15f
        }
        Surface.ROCK -> {
          phase += 2f * PI.toFloat() * (260f * tune) / SAMPLE_RATE
          val knock = sin(phase) * exp(-t * 60f) * 0.45f + sin(phase * 2.71f) * exp(-t * 80f) * 0.30f
          val click = (noise - lp) * exp(-t * 200f) * 1.3f
          knock + click + (noise - lp) * exp(-t * 30f) * 0.08f
        }
      }
      val attack = (t / 0.002f).coerceAtMost(1f)
      out[i] = toPcm(sample * attack * 0.85f)
    }
    return out
  }

  /**
   * Axe / tool strike on a pine trunk: sharp transient, a few damped wood modes (hollow "tok") and a
   * fibrous noise tail. [variant] detunes the modes (the game also varies the playback pitch per strike).
   */
  fun chop(variant: Int): ShortArray {
    val rng = Random(555 + variant * 13)
    val n = (SAMPLE_RATE * 0.34f).toInt()
    val out = ShortArray(n)
    val k = 1f + (variant - 1) * 0.06f
    val modes = floatArrayOf(330f * k, 610f * k, 1180f * k, 2350f * k)
    val decay = floatArrayOf(22f, 30f, 45f, 70f)
    val gain = floatArrayOf(0.50f, 0.33f, 0.20f, 0.10f)
    var lp = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      var tone = 0f
      for (m in modes.indices) tone += sin(2f * PI.toFloat() * modes[m] * t) * exp(-t * decay[m]) * gain[m]
      val noise = rng.nextFloat() * 2f - 1f
      lp += 0.4f * (noise - lp)
      val crack = (noise - lp) * exp(-t * 140f) * 0.9f
      val fibre = lp * exp(-t * 18f) * 0.18f
      val attack = (t / 0.0008f).coerceAtMost(1f)
      out[i] = toPcm((tone + crack + fibre) * attack * 0.85f)
    }
    return out
  }

  /**
   * The trunk giving way (~1.7 s): wood fibres tearing as a stick-slip series of pulses that speed up,
   * through a low resonance, with sharp snaps scattered on top.
   */
  fun creak(): ShortArray {
    val rng = Random(808)
    val n = (SAMPLE_RATE * 1.7f).toInt()
    val out = ShortArray(n)
    var nextPulse = 0f
    var res1 = 0f; var res1v = 0f
    var snap = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      var excite = 0f
      if (t >= nextPulse) {
        excite = 0.6f + 0.4f * rng.nextFloat()
        val rate = 18f + 70f * (t / 1.7f) * (t / 1.7f) + rng.nextFloat() * 10f
        nextPulse = t + 1f / rate
      }
      if (rng.nextFloat() < 0.0009f) snap = 0.9f
      snap *= 0.93f
      // two-pole resonator ~ 140-190 Hz (the trunk)
      val f = 140f + 50f * (t / 1.7f)
      val w = 2f * PI.toFloat() * f / SAMPLE_RATE
      res1v += (excite - res1) * w * w - res1v * 0.012f
      res1 += res1v
      val env = (t / 0.08f).coerceAtMost(1f) * (1f - ((t - 1.45f) / 0.25f).coerceIn(0f, 1f))
      val noise = rng.nextFloat() * 2f - 1f
      out[i] = toPcm((res1 * 2.2f + snap * noise * 0.8f) * env * 0.8f)
    }
    normalize(out, 0.85f)
    return out
  }

  /** A whole pine hitting the ground: deep falling thump, rumble, and the crown's branches crashing. */
  fun thud(): ShortArray {
    val rng = Random(31337)
    val n = (SAMPLE_RATE * 1.1f).toInt()
    val out = ShortArray(n)
    var phase = 0f; var lp = 0f; var lp2 = 0f
    for (i in 0 until n) {
      val t = i.toFloat() / SAMPLE_RATE
      val freq = 34f + 52f * exp(-t * 9f)
      phase += 2f * PI.toFloat() * freq / SAMPLE_RATE
      val boom = sin(phase) * exp(-t * 4.5f) * 0.95f
      val noise = rng.nextFloat() * 2f - 1f
      lp += 0.05f * (noise - lp)
      lp2 += 0.45f * (noise - lp2)
      val rumble = lp * exp(-t * 5f) * 1.6f
      val crash = (noise - lp2 * 0.5f) * exp(-(t - 0.04f).coerceAtLeast(0f) * 7f) * 0.32f * (t / 0.03f).coerceAtMost(1f)
      val attack = (t / 0.004f).coerceAtMost(1f)
      out[i] = toPcm((boom + rumble + crash) * attack * 0.9f)
    }
    return out
  }

  private fun normalize(pcm: ShortArray, peak: Float) {
    var m = 1
    for (v in pcm) m = maxOf(m, kotlin.math.abs(v.toInt()))
    val g = peak * Short.MAX_VALUE / m
    for (i in pcm.indices) pcm[i] = (pcm[i] * g).toInt().coerceIn(-32767, 32767).toShort()
  }

  /** Wraps mono 16-bit PCM into a minimal WAV file image. */
  fun toWav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
    val dataBytes = samples.size * 2
    val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16) // PCM header size
    buffer.putShort(1) // PCM
    buffer.putShort(1) // mono
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * 2) // byte rate
    buffer.putShort(2) // block align
    buffer.putShort(16) // bits per sample
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataBytes)
    for (s in samples) buffer.putShort(s)
    return buffer.array()
  }

  private fun toPcm(sample: Float): Short =
    (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
}

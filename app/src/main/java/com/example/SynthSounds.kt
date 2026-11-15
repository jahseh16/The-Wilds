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

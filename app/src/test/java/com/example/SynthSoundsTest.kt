package com.example

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SynthSoundsTest {

  private fun peak(pcm: ShortArray): Int = pcm.maxOf { abs(it.toInt()) }

  @Test
  fun stepAndDigAreAudibleUnclippedAndDeterministic() {
    for ((name, make) in listOf<Pair<String, () -> ShortArray>>(
      "step" to SynthSounds::step,
      "dig" to SynthSounds::dig
    )) {
      val pcm = make()
      assertTrue("$name is not empty", pcm.isNotEmpty())
      assertTrue("$name is audible (peak=${peak(pcm)})", peak(pcm) > 4000)
      assertTrue("$name does not clip (peak=${peak(pcm)})", peak(pcm) < Short.MAX_VALUE.toInt())
      assertArrayEquals("$name is deterministic", pcm, make())
    }
    assertTrue("step is short", SynthSounds.step().size < SynthSounds.SAMPLE_RATE / 2)
    assertTrue("dig is short", SynthSounds.dig().size < SynthSounds.SAMPLE_RATE)
  }

  @Test
  fun windLoopsWithoutAClickAtTheSeam() {
    val wind = SynthSounds.wind(seconds = 6f)
    assertEquals(SynthSounds.SAMPLE_RATE * 6, wind.size)
    assertTrue("wind is audible", peak(wind) > 4000)

    var maxAdjacentStep = 0
    for (i in 1 until wind.size) {
      maxAdjacentStep = maxOf(maxAdjacentStep, abs(wind[i] - wind[i - 1]))
    }
    val seamStep = abs(wind[0] - wind[wind.size - 1])
    assertTrue(
      "loop seam step $seamStep is within the signal's own max sample-to-sample step $maxAdjacentStep",
      seamStep <= maxAdjacentStep
    )
  }

  @Test
  fun wavHeaderDescribesMono16BitPcm() {
    val pcm = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
    val wav = SynthSounds.toWav(pcm)
    assertEquals(44 + pcm.size * 2, wav.size)

    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    fun tag(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
    assertEquals("RIFF", tag(0))
    assertEquals(wav.size - 8, b.getInt(4))
    assertEquals("WAVE", tag(8))
    assertEquals("fmt ", tag(12))
    assertEquals(1, b.getShort(20).toInt()) // PCM
    assertEquals(1, b.getShort(22).toInt()) // mono
    assertEquals(SynthSounds.SAMPLE_RATE, b.getInt(24))
    assertEquals(16, b.getShort(34).toInt())
    assertEquals("data", tag(36))
    assertEquals(pcm.size * 2, b.getInt(40))
    for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + i * 2))
  }
}

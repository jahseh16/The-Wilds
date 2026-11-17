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

  private fun rms(pcm: ShortArray) = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

  /** RMS of the first difference / RMS of the signal: grows with the high-frequency content. */
  private fun brightness(pcm: ShortArray): Double {
    var d = 0.0; var a = 0.0
    for (i in 1 until pcm.size) { val x = (pcm[i] - pcm[i - 1]).toDouble(); d += x * x; a += pcm[i].toDouble() * pcm[i] }
    return kotlin.math.sqrt(d / a)
  }

  @Test
  fun footstepsDifferPerSurfaceAndVariant() {
    for (surface in SynthSounds.Surface.values()) {
      val a = SynthSounds.footstep(surface, 0); val b = SynthSounds.footstep(surface, 1)
      assertTrue("$surface audible", rms(a) > 500)
      assertTrue("$surface short", a.size < SynthSounds.SAMPLE_RATE / 3)
      assertTrue("$surface variants differ", !a.contentEquals(b))
    }
    val forest = brightness(SynthSounds.footstep(SynthSounds.Surface.FOREST, 0))
    val sand = brightness(SynthSounds.footstep(SynthSounds.Surface.SAND, 0))
    val rock = brightness(SynthSounds.footstep(SynthSounds.Surface.ROCK, 0))
    println("brightness forest=$forest sand=$sand rock=$rock")
    assertTrue("dry litter crunch is brighter than soft sand", forest > sand * 1.3)
    assertTrue("rock click is brighter than sand", rock > sand * 1.3)
  }

  @Test
  fun fellingSoundsAreShapedRight() {
    val chop = SynthSounds.chop(0)
    assertTrue(rms(chop) > 800 && chop.size < SynthSounds.SAMPLE_RATE / 2)
    assertTrue(!chop.contentEquals(SynthSounds.chop(2)))
    val creak = SynthSounds.creak()
    assertTrue("creak lasts ${creak.size / SynthSounds.SAMPLE_RATE.toFloat()} s", creak.size > SynthSounds.SAMPLE_RATE)
    assertTrue(rms(creak) > 500)
    val thud = SynthSounds.thud()
    assertTrue(rms(thud) > 1500)
    // the thud is dark (mostly low frequencies), the chop bright
    assertTrue(brightness(thud) < brightness(chop))
  }
}

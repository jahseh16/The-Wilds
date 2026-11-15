package com.example

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyRendererTest {

  private val floats = SkyRenderer.STAR_VERTEX_FLOATS
  private val vertices = SkyRenderer.starVertices()

  @Test
  fun hundredsOfStarsAsQuads() {
    assertTrue(SkyRenderer.STAR_COUNT >= 300)
    assertEquals(SkyRenderer.STAR_COUNT * 4 * floats, vertices.size)
    val indices = SkyRenderer.starIndices()
    assertEquals(SkyRenderer.STAR_COUNT * 6, indices.size)
    // 16-bit indices: every vertex of the mesh must be addressable
    assertTrue(SkyRenderer.STAR_COUNT * 4 < Short.MAX_VALUE)
    assertEquals(3, indices[5].toInt())
    assertEquals((SkyRenderer.STAR_COUNT * 4 - 1), indices.last().toInt())
  }

  @Test
  fun everyStarIsAUnitDirectionWithFourCorners() {
    for (i in 0 until SkyRenderer.STAR_COUNT) {
      val first = i * 4 * floats
      val x = vertices[first]; val y = vertices[first + 1]; val z = vertices[first + 2]
      assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
      for (c in 1 until 4) {
        val o = first + c * floats
        assertEquals(x, vertices[o], 0f); assertEquals(y, vertices[o + 1], 0f); assertEquals(z, vertices[o + 2], 0f)
      }
      val brightness = vertices[first + 5]
      val phase = vertices[first + 6]
      assertTrue(brightness in 0f..1f && phase in 0f..1f)
    }
  }

  @Test
  fun starsCoverTheWholeSphereEvenly() {
    var sx = 0.0; var sy = 0.0; var sz = 0.0; var upper = 0
    for (i in 0 until SkyRenderer.STAR_COUNT) {
      val o = i * 4 * floats
      sx += vertices[o]; sy += vertices[o + 1]; sz += vertices[o + 2]
      if (vertices[o + 1] > 0f) upper++
    }
    val n = SkyRenderer.STAR_COUNT.toDouble()
    // Uniform on a sphere: the mean direction is ~0 (std of the mean ~ 0.58/sqrt(n))
    assertTrue("mean ${sx / n}, ${sy / n}, ${sz / n}", kotlin.math.abs(sx / n) < 0.08 && kotlin.math.abs(sy / n) < 0.08 && kotlin.math.abs(sz / n) < 0.08)
    assertTrue("hemisphere split $upper / ${SkyRenderer.STAR_COUNT}", upper in (SkyRenderer.STAR_COUNT * 0.44).toInt()..(SkyRenderer.STAR_COUNT * 0.56).toInt())
  }

  @Test
  fun theSkyIsTheSameEveryRunAndMostStarsAreFaint() {
    assertTrue(vertices.contentEquals(SkyRenderer.starVertices()))
    var faint = 0
    for (i in 0 until SkyRenderer.STAR_COUNT) if (vertices[i * 4 * floats + 5] < 0.2f) faint++
    assertTrue("faint stars: $faint", faint > SkyRenderer.STAR_COUNT / 2)
  }
}

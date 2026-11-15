package com.example

import com.badlogic.gdx.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarchingCubesTablesTest {

  private val cornerX = floatArrayOf(0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f)
  private val cornerY = floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
  private val cornerZ = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f)
  private val edgeA = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3)
  private val edgeB = intArrayOf(1, 2, 3, 0, 5, 6, 7, 4, 4, 5, 6, 7)

  @Test
  fun verifyAll256EdgeAndTriTableCases() {
    assertEquals(256, VoxelVolumeMesh.EDGE_TABLE.size)
    assertEquals(256, VoxelVolumeMesh.TRI_TABLE.size)

    for (mask in 0 until 256) {
      var expectedEdgeMask = 0
      for (e in 0 until 12) {
        val solidA = (mask and (1 shl edgeA[e])) != 0
        val solidB = (mask and (1 shl edgeB[e])) != 0
        if (solidA != solidB) {
          expectedEdgeMask = expectedEdgeMask or (1 shl e)
        }
      }
      val actualEdgeMask = VoxelVolumeMesh.EDGE_TABLE[mask]
      assertEquals("EDGE_TABLE mismatch at mask=$mask", expectedEdgeMask, actualEdgeMask)

      val triRow = VoxelVolumeMesh.TRI_TABLE[mask]
      assertEquals(16, triRow.size)

      var count = 0
      while (count < 16 && triRow[count] != -1) {
        val edge = triRow[count]
        assertTrue("Invalid edge $edge at mask=$mask", edge in 0..11)
        assertTrue(
          "Edge $edge not in EDGE_TABLE at mask=$mask",
          (actualEdgeMask and (1 shl edge)) != 0
        )
        count++
      }
      assertEquals("Tri count not multiple of 3 at mask=$mask", 0, count % 3)

      // Verify outward normal orientation relative to solid->air gradient for each triangle
      for (t in 0 until count step 3) {
        val p0 = edgeMidpoint(triRow[t])
        val p1 = edgeMidpoint(triRow[t + 1])
        val p2 = edgeMidpoint(triRow[t + 2])

        val e1 = Vector3(p1).sub(p0)
        val e2 = Vector3(p2).sub(p0)
        val triNormal = e1.crs(e2)
        assertTrue("Degenerate triangle at mask=$mask", triNormal.len2() > 1e-6f)

        // Check that triangle normal aligns positively with edge-average solid->air vector
        val expectedDir = Vector3()
        for (k in 0..2) {
          val e = triRow[t + k]
          val cA = edgeA[e]
          val cB = edgeB[e]
          val solidA = (mask and (1 shl cA)) != 0
          val solidIdx = if (solidA) cA else cB
          val airIdx = if (solidA) cB else cA
          expectedDir.add(
            cornerX[airIdx] - cornerX[solidIdx],
            cornerY[airIdx] - cornerY[solidIdx],
            cornerZ[airIdx] - cornerZ[solidIdx]
          )
        }
        assertTrue(
          "Inverted winding at mask=$mask tri=$t: dot=${triNormal.dot(expectedDir)}",
          triNormal.dot(expectedDir) > 0f
        )
      }
    }
  }

  private fun edgeMidpoint(edge: Int): Vector3 {
    val a = edgeA[edge]
    val b = edgeB[edge]
    return Vector3(
      (cornerX[a] + cornerX[b]) * 0.5f,
      (cornerY[a] + cornerY[b]) * 0.5f,
      (cornerZ[a] + cornerZ[b]) * 0.5f
    )
  }
}

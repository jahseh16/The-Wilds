package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjModelTest {

  @Test
  fun parsesATriangleWithGroupsAndFlipsV() {
    val m = ObjModel.parse(
      """
      v 0 0 0
      v 1 0 0
      v 0 1 0
      vt 0 0
      vt 1 0
      vt 0 1
      vn 0 0 1
      usemtl a
      f 1/1/1 2/2/1 3/3/1
      """.trimIndent()
    )
    assertEquals(1, m.triangleCount)
    assertEquals(3, m.vertexCount)
    assertEquals(listOf("a"), m.groups.keys.toList())
    // image space: v measured from the top, so OBJ v=1 -> 0
    assertEquals(0f, m.uvs[5], 0f)
    assertEquals(1f, m.uvs[1], 0f)
    assertEquals(1f, m.height, 0f)
  }

  @Test
  fun quadsAreFanTriangulatedAndSeparateIndicesBecomeSeparateVertices() {
    val m = ObjModel.parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvn 0 0 1\nvn 0 1 0\nf 1//1 2//1 3//1 4//2\n")
    assertEquals(2, m.triangleCount)
    assertEquals(4, m.vertexCount)
  }

  @Test
  fun everyShippedModelParsesWithinItsTriangleBudget() {
    val budgets = mapOf(
      "models/trees/tree_01.obj" to 2500, "models/props/rock_boulder_01.obj" to 1200,
      "models/props/pebble_patch_01.obj" to 1200, "models/props/pebble_01.obj" to 200,
      "models/props/pebble_02.obj" to 200, "models/props/pebble_03.obj" to 200,
      "models/props/stick_01.obj" to 200, "models/trees/tree_01_lod1.obj" to 600, "models/props/rock_boulder_01_lod1.obj" to 300, "models/props/stick_02.obj" to 200, "models/props/stick_03.obj" to 200
    )
    for ((path, budget) in budgets) {
      val m = ObjModel.parse(TestAssets.read(path))
      assertTrue("$path has ${m.triangleCount} triangles (budget $budget)", m.triangleCount in 1..budget)
      assertTrue("$path UVs in range", m.uvs.all { it in -0.01f..1.01f })
    }
    for (name in listOf("grass_01", "grass_02", "grass_03", "fern_01", "dandelion_01", "weed_01", "shrub_01")) {
      val m = ObjModel.parse(TestAssets.read("models/foliage/$name.obj"))
      assertTrue("$name: ${m.triangleCount} triangles", m.triangleCount in 10..80)
      assertTrue(m.groups.containsKey("card") || m.groups.containsKey("card".let { "card" }))
    }
  }
}

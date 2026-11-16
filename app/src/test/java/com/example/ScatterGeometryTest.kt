package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScatterGeometryTest {

  private val models by lazy { TestAssets.models() }

  @Test
  fun atlasCellsDoNotOverlapAndFitTheAtlas() {
    for (layer in Layer.values()) {
      val cells = AtlasLayout.cells(layer).values.toList()
      for (c in cells) assertTrue(c.x >= 0 && c.y >= 0 && c.x + c.size <= AtlasLayout.SIZE && c.y + c.size <= AtlasLayout.SIZE)
      for (i in cells.indices) for (j in i + 1 until cells.size) {
        val a = cells[i]; val b = cells[j]
        val overlap = a.x < b.x + b.size && b.x < a.x + a.size && a.y < b.y + b.size && b.y < a.y + a.size
        assertTrue("cells ${a.file} / ${b.file} overlap", !overlap)
      }
    }
  }

  @Test
  fun everyKindHasItsPartsAndUvsLandInsideTheirAtlasCell() {
    for (kind in Kind.ALL) {
      val parts = models.partsOf(kind)
      assertEquals(kind.parts.size, parts.size)
      for ((part, spec) in parts.zip(kind.parts)) {
        val cell = AtlasLayout.cells(spec.layer).getValue(spec.cell)
        val u0 = cell.x.toFloat() / AtlasLayout.SIZE; val u1 = (cell.x + cell.size).toFloat() / AtlasLayout.SIZE
        val v0 = cell.y.toFloat() / AtlasLayout.SIZE; val v1 = (cell.y + cell.size).toFloat() / AtlasLayout.SIZE
        for (k in 0 until part.vertexCount) {
          assertTrue("$kind u", part.data[k * 8 + 6] in u0..u1)
          assertTrue("$kind v", part.data[k * 8 + 7] in v0..v1)
        }
        assertTrue(part.indices.all { it in 0 until part.vertexCount })
      }
    }
  }

  @Test
  fun colliderNumbersAreSane() {
    assertTrue("trunk radius ${models.trunkRadius}", models.trunkRadius in 0.2f..1.2f)
    assertTrue("rock radius ${models.rockRadius}", models.rockRadius in 0.5f..1.5f)
    assertTrue("rock height ${models.rockHeight}", models.rockHeight in 0.5f..1.5f)
  }

  @Test
  fun aFullForestChunkIsTwoMeshesWithinTheVertexAndTriangleBudget() {
    val terrain = IslandTerrain(IslandTerrain.DEFAULT_SEED, 512f, 512f, 500f, 0f)
    var worstTris = 0; var worstVerts = 0; var forestChunks = 0
    for (cz in 20..44 step 2) for (cx in 20..44 step 2) {
      val list = ArrayList<Placement>()
      WorldScatter.planFar(terrain, cx, cz, 16f, emptyList(), list)
      WorldScatter.planNear(terrain, cx, cz, 16f, emptyList(), list)
      if (list.none { it.kind == Kind.TREE }) continue
      forestChunks++
      val (solid, cutout) = ChunkScatterMesh.build(list, models)
      val verts = (solid?.vertexCount ?: 0) + (cutout?.vertexCount ?: 0)
      val tris = ((solid?.indexCount ?: 0) + (cutout?.indexCount ?: 0)) / 3
      worstTris = maxOf(worstTris, tris); worstVerts = maxOf(worstVerts, verts)
      for (layer in listOfNotNull(solid, cutout)) {
        assertTrue("layer vertices ${layer.vertexCount}", layer.vertexCount < ChunkScatterMesh.MAX_VERTICES)
        assertTrue(layer.minX <= layer.maxX && layer.minY <= layer.maxY)
        for (k in 0 until layer.indexCount) assertTrue(layer.indices[k].toInt() and 0xFFFF < layer.vertexCount)
      }
      assertNotNull(cutout)
    }
    println("forest chunks=$forestChunks worst triangles/chunk=$worstTris vertices=$worstVerts")
    assertTrue("sampled forest chunks: $forestChunks", forestChunks > 3)
    assertTrue("worst chunk $worstTris triangles", worstTris < 25000)
  }

  @Test
  fun windAmplitudeIsZeroAtTheBaseAndGrowsTowardsTheTips() {
    val list = listOf(Placement(Kind.GRASS_3, 10f, 5f, 10f, 0f, 1f))
    val (_, cutout) = ChunkScatterMesh.build(list, models)
    val layer = cutout!!
    var lowSway = 0f; var highSway = 0f
    for (k in 0 until layer.vertexCount) {
      val y = layer.vertices[k * ChunkScatterMesh.FLOATS + 1] - 5f
      val sway = layer.vertices[k * ChunkScatterMesh.FLOATS + 8]
      if (y < 0.01f) lowSway = maxOf(lowSway, sway) else highSway = maxOf(highSway, sway)
      assertEquals(1f, layer.vertices[k * ChunkScatterMesh.FLOATS + 9], 0f) // grass fades with distance
    }
    assertEquals(0f, lowSway, 1e-6f)
    assertTrue(highSway > 0.02f)
  }
}

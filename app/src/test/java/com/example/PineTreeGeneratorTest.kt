package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PineTreeGeneratorTest {

  private fun tris(p: PartMesh) = p.indices.size / 3

  @Test
  fun everyVariantIsATallPineWithinTheTriangleBudget() {
    for (v in PineTreeGenerator.SPECS.indices) {
      val parts = PineTreeGenerator.parts(v, lod = false)
      val total = parts.values.sumOf { tris(it) }
      println("pine $v: ${parts.mapValues { tris(it.value) }} total=$total")
      assertTrue("variant $v has $total triangles", total in 900..1400)
      val spec = PineTreeGenerator.SPECS[v]
      assertTrue(spec.height * PineTreeGenerator.MIN_SCALE >= 8f && spec.height * PineTreeGenerator.MAX_SCALE <= 14f)
      assertTrue("5-7 whorls", spec.tiers in 5..7)
      val lod = PineTreeGenerator.parts(v, lod = true).values.sumOf { tris(it) }
      assertTrue("LOD $lod triangles", lod in 120..320)
    }
  }

  @Test
  fun stumpIsBuriedAndTrunkAndCrownAreFellable() {
    val parts = PineTreeGenerator.parts(1, false)
    val stump = parts.getValue(PineTreeGenerator.Piece.STUMP)
    val trunk = parts.getValue(PineTreeGenerator.Piece.TRUNK)
    val crown = parts.getValue(PineTreeGenerator.Piece.CROWN)
    assertTrue(!stump.fellable && trunk.fellable && crown.fellable)
    assertEquals(Layer.BARK, stump.layer); assertEquals(Layer.BARK, trunk.layer); assertEquals(Layer.CUTOUT, crown.layer)
    var minY = 0f
    for (k in 0 until stump.vertexCount) minY = minOf(minY, stump.data[k * 8 + 1])
    assertEquals(-0.35f, minY, 1e-5f)
    for (k in 0 until trunk.vertexCount) assertTrue(trunk.data[k * 8 + 1] >= PineTreeGenerator.STUMP_TOP - 1e-5f)
    assertTrue("trunk and stump never sway", trunk.swayWeight.all { it == 0f } && stump.swayWeight.all { it == 0f })
  }

  /** "0 floating leaves": every connected piece of the crown touches the trunk. */
  @Test
  fun noCrownPieceFloats() {
    for (v in PineTreeGenerator.SPECS.indices) for (lod in listOf(false, true)) {
      val spec = PineTreeGenerator.SPECS[v]
      val crown = PineTreeGenerator.parts(v, lod).getValue(PineTreeGenerator.Piece.CROWN)
      val parent = IntArray(crown.vertexCount) { it }
      fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
      for (t in crown.indices.indices step 3) {
        val a = find(crown.indices[t]); val b = find(crown.indices[t + 1]); val c = find(crown.indices[t + 2])
        parent[b] = a; parent[find(c)] = a
      }
      val attached = HashSet<Int>()
      val all = HashSet<Int>()
      for (k in 0 until crown.vertexCount) {
        val x = crown.data[k * 8]; val y = crown.data[k * 8 + 1]; val z = crown.data[k * 8 + 2]
        val r = find(k)
        all.add(r)
        if (sqrt(x * x + z * z) <= spec.radiusAt(y) + 0.05f && y <= spec.height + 0.1f) attached.add(r)
      }
      assertEquals("variant $v lod=$lod: ${all.size - attached.size} floating pieces", all.size, attached.size)
    }
  }

  @Test
  fun crownInteriorIsDarkerThanTheTips() {
    val crown = PineTreeGenerator.parts(2, false).getValue(PineTreeGenerator.Piece.CROWN)
    val ao = crown.ao!!
    var inner = 0f; var ni = 0; var outer = 0f; var no = 0
    for (k in 0 until crown.vertexCount) {
      val x = crown.data[k * 8]; val z = crown.data[k * 8 + 2]
      val d = sqrt(x * x + z * z)
      if (d < 0.6f) { inner += ao[k]; ni++ } else if (d > 1.8f) { outer += ao[k]; no++ }
    }
    assertTrue("inner ao ${inner / ni} < outer ${outer / no}", inner / ni < outer / no - 0.2f)
    assertTrue(ao.all { it in 0.05f..1f })
  }

  @Test
  fun crownUvsStayInTheNeedleCell() {
    val cell = AtlasLayout.CUTOUT.getValue("needles")
    val u0 = cell.x.toFloat() / AtlasLayout.SIZE; val u1 = (cell.x + cell.size).toFloat() / AtlasLayout.SIZE
    val v0 = cell.y.toFloat() / AtlasLayout.SIZE; val v1 = (cell.y + cell.size).toFloat() / AtlasLayout.SIZE
    for (v in PineTreeGenerator.SPECS.indices) {
      val crown = PineTreeGenerator.parts(v, false).getValue(PineTreeGenerator.Piece.CROWN)
      for (k in 0 until crown.vertexCount) {
        assertTrue(crown.data[k * 8 + 6] in u0..u1); assertTrue(crown.data[k * 8 + 7] in v0..v1)
      }
    }
  }

  @Test
  fun needleTextureHasCutoutCardAndOpaqueMat() {
    val size = 128
    val px = PineTreeGenerator.needleTexture(size)
    assertEquals(size * size, px.size)
    var cardOpaque = 0; var cardClear = 0
    for (y in 0 until (size * 0.74f).toInt()) for (x in 0 until size) if ((px[y * size + x] and 0xFF) > 127) cardOpaque++ else cardClear++
    assertTrue("card has needles ($cardOpaque) and holes ($cardClear)", cardOpaque > size * 8 && cardClear > size * 8)
    for (y in (size * 0.77f).toInt() until size) for (x in 0 until size) assertEquals(0xFF, px[y * size + x] and 0xFF)
    // colour bleeding: transparent texels are not black
    val clear = px.first { (it and 0xFF) == 0 }
    assertTrue(((clear ushr 16) and 0xFF) > 10)
  }

  @Test
  fun logHasBarkAndTwoCutFaces() {
    val log = PineTreeGenerator.log(2f, 0.3f)
    var caps = 0
    for (k in 0 until log.vertexCount) if (log.data[k * 8 + 6] > 3.5f) caps++
    assertEquals(2 * 9, caps)
    var minY = 0f; var maxY = 0f
    for (k in 0 until log.vertexCount) { minY = minOf(minY, log.data[k * 8 + 1]); maxY = maxOf(maxY, log.data[k * 8 + 1]) }
    assertEquals(-1f, minY, 1e-5f); assertEquals(1f, maxY, 1e-5f)
  }
}

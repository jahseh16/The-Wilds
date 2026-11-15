package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkKeyTest {

  @Test
  fun keyRoundTripsEveryCoordinateOfTheKilometreWorld() {
    for (cx in 0 until 64 step 7) {
      for (cz in 0 until 64 step 5) {
        val key = Chunk.key(cx, cz)
        assertEquals(cx, Chunk.keyX(key))
        assertEquals(cz, Chunk.keyZ(key))
      }
    }
  }

  @Test
  fun keysAreUniquePerChunk() {
    val seen = HashSet<Long>()
    for (cx in -2..70) for (cz in -2..70) assertTrue("($cx,$cz)", seen.add(Chunk.key(cx, cz)))
    assertNotEquals(Chunk.key(1, 2), Chunk.key(2, 1))
    // negative coordinates must not bleed into the other half
    assertEquals(-1, Chunk.keyZ(Chunk.key(5, -1)))
    assertEquals(5, Chunk.keyX(Chunk.key(5, -1)))
  }

  @Test
  fun deltaStoreIsSparseAndKeepsEditsPerChunk() {
    val store = ChunkDeltaStore()
    assertTrue(store.get(Chunk.key(40, 50), 7).isNaN())
    store.put(Chunk.key(40, 50), 7, 1.5f)
    store.put(Chunk.key(41, 50), 7, -2f)
    assertEquals(1.5f, store.get(Chunk.key(40, 50), 7), 0f)
    assertEquals(-2f, store.get(Chunk.key(41, 50), 7), 0f)
    assertTrue(store.get(Chunk.key(40, 50), 8).isNaN())
    assertEquals(2, store.editedChunkCount)

    val density = FloatArray(16) { 9f }
    store.applyTo(Chunk.key(40, 50), density)
    assertEquals(1.5f, density[7], 0f)
    assertEquals(9f, density[6], 0f)
  }
}

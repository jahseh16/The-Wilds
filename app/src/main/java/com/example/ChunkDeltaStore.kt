package com.example

import com.badlogic.gdx.utils.IntFloatMap

/**
 * Sparse, in-memory record of every density sample the player has changed, keyed by chunk.
 *
 * Chunks are pure functions of the terrain generator plus these deltas, so a chunk can be disposed
 * when it leaves the streaming radius and regenerated later with all its excavation intact. Only
 * edited samples are stored (final absolute density), so memory grows with what was dug, not with
 * how much world has been visited. This is also the natural thing to serialise for a save file.
 */
class ChunkDeltaStore {

  private val perChunk = HashMap<Int, IntFloatMap>()

  val editedChunkCount: Int
    get() = perChunk.size

  /** Final density stored for [localIndex] in chunk [chunkIndex], or NaN if it was never edited. */
  fun get(chunkIndex: Int, localIndex: Int): Float {
    val map = perChunk[chunkIndex] ?: return Float.NaN
    return map.get(localIndex, Float.NaN)
  }

  fun put(chunkIndex: Int, localIndex: Int, density: Float) {
    perChunk.getOrPut(chunkIndex) { IntFloatMap() }.put(localIndex, density)
  }

  /** Overwrites the freshly generated [density] samples of chunk [chunkIndex] with the saved edits. */
  fun applyTo(chunkIndex: Int, density: FloatArray) {
    val map = perChunk[chunkIndex] ?: return
    for (entry in map.entries()) {
      density[entry.key] = entry.value
    }
  }
}

package com.example

import com.badlogic.gdx.utils.IntFloatMap

/**
 * Sparse, in-memory record of every density sample the player has changed, keyed by chunk (see [Chunk.key]).
 *
 * Chunks are pure functions of the terrain generator plus these deltas, so a chunk can be disposed
 * when it leaves the streaming radius and regenerated later with all its excavation intact. Only
 * edited samples are stored (final absolute density), so memory grows with what was dug, not with
 * how much world has been visited. This is also the natural thing to serialise for a save file.
 */
class ChunkDeltaStore {

  private val perChunk = HashMap<Long, IntFloatMap>()

  val editedChunkCount: Int
    get() = perChunk.size

  /** Final density stored for [localIndex] in chunk [chunkKey], or NaN if it was never edited. */
  fun get(chunkKey: Long, localIndex: Int): Float {
    val map = perChunk[chunkKey] ?: return Float.NaN
    return map.get(localIndex, Float.NaN)
  }

  fun put(chunkKey: Long, localIndex: Int, density: Float) {
    perChunk.getOrPut(chunkKey) { IntFloatMap() }.put(localIndex, density)
  }

  /** Overwrites the freshly generated [density] samples of chunk [chunkKey] with the saved edits. */
  fun applyTo(chunkKey: Long, density: FloatArray) {
    val map = perChunk[chunkKey] ?: return
    for (entry in map.entries()) {
      density[entry.key] = entry.value
    }
  }
}

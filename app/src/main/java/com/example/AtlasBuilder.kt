package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture

/**
 * Packs the separate asset images into the two runtime atlases described by [AtlasLayout]. The
 * images are drawn into a 1024 x 1024 RGBA pixmap (alpha copied, not blended), then uploaded once
 * with mipmaps: 4 MB per atlas (5.3 MB with mips), far less than the 15 separate textures it replaces
 * and, more importantly, one texture bind for every object of a layer.
 */
object AtlasBuilder {

  fun build(layer: Layer): Texture {
    val atlas = Pixmap(AtlasLayout.SIZE, AtlasLayout.SIZE, Pixmap.Format.RGBA8888)
    atlas.blending = Pixmap.Blending.None
    atlas.setColor(0f, 0f, 0f, 0f)
    atlas.fill()
    for (cell in AtlasLayout.cells(layer).values) {
      val source = cell.file?.let { Pixmap(Gdx.files.internal(it)) } ?: generated(cell)
      atlas.drawPixmap(source, 0, 0, source.width, source.height, cell.x, cell.y, cell.size, cell.size)
      source.dispose()
    }
    val texture = Texture(atlas, Pixmap.Format.RGBA8888, true)
    atlas.dispose()
    texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
    texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge)
    return texture
  }

  /** Cells without a file are drawn procedurally (the pine needles). */
  private fun generated(cell: AtlasLayout.Cell): Pixmap {
    val pixels = PineTreeGenerator.needleTexture(cell.size)
    val pixmap = Pixmap(cell.size, cell.size, Pixmap.Format.RGBA8888)
    val buffer = pixmap.pixels
    buffer.clear()
    for (p in pixels) {
      buffer.put((p ushr 24).toByte()); buffer.put((p ushr 16).toByte()); buffer.put((p ushr 8).toByte()); buffer.put(p.toByte())
    }
    buffer.flip()
    return pixmap
  }
}

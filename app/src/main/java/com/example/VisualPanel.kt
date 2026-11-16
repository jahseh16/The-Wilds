package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Slider
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import java.util.Locale

/**
 * The visual calibration window: a dark, semi-transparent panel with one touch slider per
 * [VisualParam] (value shown next to it), "Restaurar valores por defecto" and "Cerrar".
 *
 * It is a plain Scene2D [Table] (no extra render pass, no framebuffer): the world keeps rendering
 * behind it, so every slider acts live on the scene. Every touch that lands on the panel is
 * swallowed here, so dragging a slider (or the panel background) never turns the camera.
 *
 *  - [onChanged] fires on every slider movement (cheap: the shaders read [settings] each frame).
 *  - [onSettled] fires when a drag ends, on reset and on close: this is where the game saves to
 *    Preferences (autosave without writing to disk 60 times a second).
 */
class VisualPanel(
  private val settings: VisualSettings,
  private val onChanged: () -> Unit,
  private val onSettled: () -> Unit
) : Table(), Disposable {

  private val textures = ArrayList<Texture>()
  private val font = BitmapFont().apply { data.setScale(1.08f) }
  private val titleFont = BitmapFont().apply { data.setScale(1.30f) }

  private class Row(val param: VisualParam, val slider: Slider, val value: Label)

  private val rows = ArrayList<Row>()

  /** True while a finger is dragging one of the sliders. */
  var isDraggingSlider: Boolean = false
    private set

  private var activePointers = 0

  init {
    touchable = Touchable.enabled
    isVisible = false
    // Swallow every touch inside the panel so the look-around gesture never sees it
    addListener(object : InputListener() {
      override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) = true
    })

    background = NinePatchDrawable(NinePatch(roundedTexture(48, Color(0.04f, 0.06f, 0.08f, 0.64f), Color(0.60f, 0.70f, 0.80f, 0.35f)), 18, 18, 18, 18))
    pad(18f, 22f, 16f, 22f)

    val trackStyle = Slider.SliderStyle(
      NinePatchDrawable(NinePatch(pillTexture(24, 8, Color(0.30f, 0.36f, 0.42f, 0.90f)), 4, 4, 0, 0)),
      TextureRegionDrawable(TextureRegion(circleTexture(34, Color(0.88f, 0.93f, 0.98f, 1f), Color(0.20f, 0.28f, 0.36f, 1f))))
    ).apply {
      knobBefore = NinePatchDrawable(NinePatch(pillTexture(24, 8, Color(0.46f, 0.74f, 0.58f, 1f)), 4, 4, 0, 0))
      knobDown = TextureRegionDrawable(TextureRegion(circleTexture(34, Color(0.66f, 0.96f, 0.80f, 1f), Color(0.20f, 0.28f, 0.36f, 1f))))
    }

    val labelStyle = Label.LabelStyle(font, Color(0.90f, 0.94f, 0.98f, 1f))
    val valueStyle = Label.LabelStyle(font, Color(0.66f, 0.92f, 0.78f, 1f))

    add(Label("Calibracion visual", Label.LabelStyle(titleFont, Color.WHITE))).colspan(3).left().padBottom(8f).row()

    for (param in VisualParam.values()) {
      val slider = Slider(param.min, param.max, param.step, false, trackStyle)
      slider.value = settings[param]
      val value = Label(format(param, settings[param]), valueStyle).apply { setAlignment(com.badlogic.gdx.utils.Align.right) }
      slider.addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
          settings[param] = slider.value
          value.setText(format(param, settings[param]))
          onChanged()
        }
      })
      // Drag bookkeeping + autosave when the finger lifts
      slider.addListener(object : InputListener() {
        override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
          activePointers++
          isDraggingSlider = true
          return true
        }

        override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
          activePointers = (activePointers - 1).coerceAtLeast(0)
          isDraggingSlider = activePointers > 0
          onSettled()
        }
      })
      rows += Row(param, slider, value)
      add(Label(param.label, labelStyle)).left().width(LABEL_WIDTH).padRight(10f)
      add(slider).width(SLIDER_WIDTH).height(ROW_HEIGHT)
      add(value).width(VALUE_WIDTH).padLeft(10f).right().row()
    }

    val buttonStyle = TextButton.TextButtonStyle(
      NinePatchDrawable(NinePatch(roundedTexture(32, Color(0.10f, 0.14f, 0.18f, 0.85f), Color(0.62f, 0.72f, 0.82f, 0.85f)), 12, 12, 12, 12)),
      NinePatchDrawable(NinePatch(roundedTexture(32, Color(0.30f, 0.42f, 0.54f, 0.95f), Color(0.90f, 0.96f, 1f, 1f)), 12, 12, 12, 12)),
      null,
      font
    ).apply {
      fontColor = Color(0.92f, 0.96f, 1f, 1f)
      downFontColor = Color.WHITE
    }

    val resetButton = TextButton("Restaurar valores por defecto", buttonStyle).apply {
      addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
          resetToDefaults()
        }
      })
    }
    val closeButton = TextButton("Cerrar", buttonStyle).apply {
      addListener(object : ChangeListener() {
        override fun changed(event: ChangeEvent?, actor: com.badlogic.gdx.scenes.scene2d.Actor?) {
          close()
        }
      })
    }
    val buttons = Table().apply {
      add(resetButton).height(BUTTON_HEIGHT).expandX().fillX().padRight(12f)
      add(closeButton).height(BUTTON_HEIGHT).width(130f)
    }
    add(buttons).colspan(3).fillX().padTop(12f)
    pack()
  }

  val isOpen: Boolean get() = isVisible

  fun open() {
    syncFromSettings()
    isVisible = true
  }

  fun close() {
    isVisible = false
    isDraggingSlider = false
    activePointers = 0
    onSettled()
  }

  fun toggle() {
    if (isVisible) close() else open()
  }

  fun resetToDefaults() {
    settings.resetToDefaults()
    syncFromSettings()
    onChanged()
    onSettled()
  }

  /** Re-reads every slider / value label from [settings] (after a reset or a preference load). */
  fun syncFromSettings() {
    for (row in rows) {
      row.slider.value = settings[row.param]
      row.value.setText(format(row.param, settings[row.param]))
    }
  }

  override fun dispose() {
    font.dispose()
    titleFont.dispose()
    textures.forEach { it.dispose() }
    textures.clear()
  }

  // --- procedural UI textures (no asset files) -------------------------------------------------

  private fun track(pixmap: Pixmap): Texture {
    val t = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    pixmap.dispose()
    textures += t
    return t
  }

  private fun pillTexture(w: Int, h: Int, color: Color): Texture {
    val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
    p.setColor(color)
    val r = h / 2
    p.fillCircle(r, r, r - 1)
    p.fillCircle(w - r - 1, r, r - 1)
    p.fillRectangle(r, 0, w - 2 * r, h)
    return track(p)
  }

  private fun circleTexture(size: Int, fill: Color, ring: Color): Texture {
    val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
    val c = size / 2
    p.setColor(ring)
    p.fillCircle(c, c, c - 1)
    p.setColor(fill)
    p.fillCircle(c, c, c - 4)
    return track(p)
  }

  /** Rounded rectangle with a 2 px border; stretched as a 9-patch with the corner radius as the split. */
  private fun roundedTexture(size: Int, fill: Color, border: Color): Texture {
    val p = Pixmap(size, size, Pixmap.Format.RGBA8888)
    val r = (size / 4).coerceAtLeast(6)
    fun shape(inset: Int) {
      p.fillCircle(inset + r, inset + r, r)
      p.fillCircle(size - inset - r - 1, inset + r, r)
      p.fillCircle(inset + r, size - inset - r - 1, r)
      p.fillCircle(size - inset - r - 1, size - inset - r - 1, r)
      p.fillRectangle(inset + r, inset, size - 2 * (inset + r), size - 2 * inset)
      p.fillRectangle(inset, inset + r, size - 2 * inset, size - 2 * (inset + r))
    }
    p.setColor(border)
    shape(0)
    p.setBlending(Pixmap.Blending.None)
    p.setColor(fill)
    shape(2)
    return track(p)
  }

  companion object {
    const val LABEL_WIDTH = 210f
    const val SLIDER_WIDTH = 190f
    const val VALUE_WIDTH = 58f
    const val ROW_HEIGHT = 38f
    const val BUTTON_HEIGHT = 44f

    /** "1.10", with an explicit sign for the warmth slider (+0.12 / -0.20). */
    fun format(param: VisualParam, value: Float): String =
      if (param == VisualParam.WARMTH) String.format(Locale.US, "%+.2f", value)
      else String.format(Locale.US, "%.2f", value)
  }
}

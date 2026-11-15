package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.viewport.ExtendViewport

class HudStage(
  private val onLookDelta: (deltaX: Float, deltaY: Float) -> Unit
) : Stage(ExtendViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT)) {

  private val backgroundTexture: Texture = createRingTexture(
    size = 256,
    fillColor = Color(0.08f, 0.14f, 0.12f, 0.42f),
    ringColor = Color(0.45f, 0.85f, 0.60f, 0.72f),
    ringThickness = 4
  )

  private val knobTexture: Texture = createCircleTexture(
    size = 96,
    fillColor = Color(0.45f, 0.85f, 0.60f, 0.88f)
  )

  private val jumpUpTexture: Texture = createRingTexture(
    size = 160,
    fillColor = Color(0.10f, 0.18f, 0.14f, 0.52f),
    ringColor = Color(0.45f, 0.85f, 0.60f, 0.82f),
    ringThickness = 4
  )

  private val jumpDownTexture: Texture = createRingTexture(
    size = 160,
    fillColor = Color(0.32f, 0.62f, 0.44f, 0.78f),
    ringColor = Color(0.70f, 0.96f, 0.80f, 0.95f),
    ringThickness = 4
  )

  private val crawlOffTexture: Texture = createRingTexture(
    size = 150,
    fillColor = Color(0.10f, 0.15f, 0.20f, 0.54f),
    ringColor = Color(0.42f, 0.72f, 0.92f, 0.80f),
    ringThickness = 4
  )

  private val crawlOnTexture: Texture = createRingTexture(
    size = 150,
    fillColor = Color(0.16f, 0.45f, 0.62f, 0.80f),
    ringColor = Color(0.58f, 0.90f, 1.0f, 0.96f),
    ringThickness = 4
  )

  private val digUpTexture: Texture = createRingTexture(
    size = 160,
    fillColor = Color(0.24f, 0.16f, 0.08f, 0.58f),
    ringColor = Color(0.95f, 0.70f, 0.30f, 0.88f),
    ringThickness = 4
  )

  private val digDownTexture: Texture = createRingTexture(
    size = 160,
    fillColor = Color(0.68f, 0.45f, 0.15f, 0.82f),
    ringColor = Color(1.0f, 0.86f, 0.48f, 0.98f),
    ringThickness = 4
  )

  private val lightOffTexture: Texture = createRingTexture(
    size = 140,
    fillColor = Color(0.12f, 0.14f, 0.18f, 0.55f),
    ringColor = Color(0.55f, 0.62f, 0.72f, 0.75f),
    ringThickness = 4
  )

  private val lightOnTexture: Texture = createRingTexture(
    size = 140,
    fillColor = Color(0.65f, 0.56f, 0.18f, 0.75f),
    ringColor = Color(1.0f, 0.92f, 0.45f, 0.95f),
    ringThickness = 4
  )

  private val buttonFont: BitmapFont = BitmapFont().apply {
    data.setScale(1.32f)
  }

  private val lightOffDrawable = TextureRegionDrawable(TextureRegion(lightOffTexture))
  private val lightOnDrawable = TextureRegionDrawable(TextureRegion(lightOnTexture))
  private val crawlOffDrawable = TextureRegionDrawable(TextureRegion(crawlOffTexture))
  private val crawlOnDrawable = TextureRegionDrawable(TextureRegion(crawlOnTexture))

  private val touchpad: Touchpad
  private val jumpButton: TextButton
  private val crawlButton: TextButton
  private val crawlButtonStyle: TextButton.TextButtonStyle
  private val digButton: TextButton
  private val lightButton: TextButton
  private val lightButtonStyle: TextButton.TextButtonStyle

  @Volatile
  private var jumpRequested: Boolean = false

  @Volatile
  private var digRequested: Boolean = false

  @Volatile
  var isCrawling: Boolean = false
    private set

  @Volatile
  var isFlashlightOn: Boolean = true
    private set

  private var lookPointer: Int = -1
  private var lastTouchX: Int = 0
  private var lastTouchY: Int = 0

  private val lookInputAdapter = object : InputAdapter() {
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
      if (lookPointer == -1 && screenX >= Gdx.graphics.width * 0.5f) {
        lookPointer = pointer
        lastTouchX = screenX
        lastTouchY = screenY
        return true
      }
      return false
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
      if (pointer == lookPointer) {
        val deltaX = (screenX - lastTouchX).toFloat()
        val deltaY = (screenY - lastTouchY).toFloat()
        lastTouchX = screenX
        lastTouchY = screenY
        onLookDelta(deltaX, deltaY)
        return true
      }
      return false
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
      if (pointer == lookPointer) {
        lookPointer = -1
        return true
      }
      return false
    }

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
      if (pointer == lookPointer) {
        lookPointer = -1
        return true
      }
      return false
    }
  }

  val inputProcessor: InputProcessor = InputMultiplexer(this, lookInputAdapter)

  val moveX: Float
    get() = touchpad.knobPercentX

  val moveY: Float
    get() = touchpad.knobPercentY

  init {
    val touchpadStyle = Touchpad.TouchpadStyle(
      TextureRegionDrawable(TextureRegion(backgroundTexture)),
      TextureRegionDrawable(TextureRegion(knobTexture))
    )
    touchpad = Touchpad(JOYSTICK_DEADZONE, touchpadStyle)

    val jumpButtonStyle = TextButton.TextButtonStyle(
      TextureRegionDrawable(TextureRegion(jumpUpTexture)),
      TextureRegionDrawable(TextureRegion(jumpDownTexture)),
      null,
      buttonFont
    ).apply {
      fontColor = Color.WHITE
      downFontColor = Color(0.92f, 1f, 0.94f, 1f)
    }

    jumpButton = TextButton("SALTAR", jumpButtonStyle).apply {
      addListener(object : InputListener() {
        override fun touchDown(
          event: InputEvent?,
          x: Float,
          y: Float,
          pointer: Int,
          button: Int
        ): Boolean {
          jumpRequested = true
          return true
        }
      })
    }

    crawlButtonStyle = TextButton.TextButtonStyle(
      crawlOffDrawable,
      crawlOnDrawable,
      null,
      buttonFont
    ).apply {
      fontColor = Color(0.85f, 0.94f, 1f, 1f)
    }

    crawlButton = TextButton("GATEAR", crawlButtonStyle).apply {
      addListener(object : InputListener() {
        override fun touchDown(
          event: InputEvent?,
          x: Float,
          y: Float,
          pointer: Int,
          button: Int
        ): Boolean {
          isCrawling = !isCrawling
          updateCrawlButtonVisuals()
          return true
        }
      })
    }
    updateCrawlButtonVisuals()

    val digButtonStyle = TextButton.TextButtonStyle(
      TextureRegionDrawable(TextureRegion(digUpTexture)),
      TextureRegionDrawable(TextureRegion(digDownTexture)),
      null,
      buttonFont
    ).apply {
      fontColor = Color(1f, 0.95f, 0.82f, 1f)
      downFontColor = Color.WHITE
    }

    digButton = TextButton("CAVAR", digButtonStyle).apply {
      addListener(object : InputListener() {
        override fun touchDown(
          event: InputEvent?,
          x: Float,
          y: Float,
          pointer: Int,
          button: Int
        ): Boolean {
          digRequested = true
          return true
        }
      })
    }

    lightButtonStyle = TextButton.TextButtonStyle(
      lightOnDrawable,
      lightOffDrawable,
      null,
      buttonFont
    ).apply {
      fontColor = Color.WHITE
    }

    lightButton = TextButton("LUZ", lightButtonStyle).apply {
      addListener(object : InputListener() {
        override fun touchDown(
          event: InputEvent?,
          x: Float,
          y: Float,
          pointer: Int,
          button: Int
        ): Boolean {
          isFlashlightOn = !isFlashlightOn
          updateLightButtonVisuals()
          return true
        }
      })
    }
    updateLightButtonVisuals()

    val bottomRightRow = Table().apply {
      add(crawlButton).size(CRAWL_BUTTON_SIZE, CRAWL_BUTTON_SIZE).padRight(16f).bottom()
      add(jumpButton).size(JUMP_BUTTON_SIZE, JUMP_BUTTON_SIZE).bottom()
    }

    val rightActionsTable = Table().apply {
      add(lightButton).size(LIGHT_BUTTON_SIZE, LIGHT_BUTTON_SIZE).right().padBottom(14f).row()
      add(digButton).size(DIG_BUTTON_SIZE, DIG_BUTTON_SIZE).right().padBottom(14f).row()
      add(bottomRightRow).right()
    }

    val rootTable = Table().apply {
      setFillParent(true)
      bottom()
      pad(28f)
      add(touchpad).size(JOYSTICK_SIZE, JOYSTICK_SIZE).left().bottom()
      add().expandX()
      add(rightActionsTable).right().bottom()
    }
    addActor(rootTable)
  }

  private fun updateLightButtonVisuals() {
    lightButtonStyle.up = if (isFlashlightOn) lightOnDrawable else lightOffDrawable
    lightButtonStyle.fontColor = if (isFlashlightOn) Color.WHITE else Color(0.75f, 0.80f, 0.86f, 1f)
  }

  private fun updateCrawlButtonVisuals() {
    crawlButtonStyle.up = if (isCrawling) crawlOnDrawable else crawlOffDrawable
    crawlButtonStyle.fontColor = if (isCrawling) Color.WHITE else Color(0.80f, 0.90f, 0.98f, 1f)
  }

  fun consumeJumpRequest(): Boolean {
    if (!jumpRequested) return false
    jumpRequested = false
    return true
  }

  fun consumeDigRequest(): Boolean {
    if (!digRequested) return false
    digRequested = false
    return true
  }

  fun resize(width: Int, height: Int) {
    viewport.update(width, height, true)
  }

  override fun dispose() {
    backgroundTexture.dispose()
    knobTexture.dispose()
    jumpUpTexture.dispose()
    jumpDownTexture.dispose()
    crawlOffTexture.dispose()
    crawlOnTexture.dispose()
    digUpTexture.dispose()
    digDownTexture.dispose()
    lightOffTexture.dispose()
    lightOnTexture.dispose()
    buttonFont.dispose()
    super.dispose()
  }

  private fun createRingTexture(
    size: Int,
    fillColor: Color,
    ringColor: Color,
    ringThickness: Int
  ): Texture {
    val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
    val center = size / 2
    val radius = center - 2

    pixmap.setColor(fillColor)
    pixmap.fillCircle(center, center, radius)

    pixmap.setColor(ringColor)
    for (offset in 0 until ringThickness) {
      pixmap.drawCircle(center, center, radius - offset)
    }

    return Texture(pixmap).apply {
      setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
      pixmap.dispose()
    }
  }

  private fun createCircleTexture(size: Int, fillColor: Color): Texture {
    val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
    val center = size / 2
    val radius = center - 2

    pixmap.setColor(fillColor)
    pixmap.fillCircle(center, center, radius)

    return Texture(pixmap).apply {
      setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
      pixmap.dispose()
    }
  }

  private companion object {
    const val VIRTUAL_WIDTH = 1280f
    const val VIRTUAL_HEIGHT = 720f
    const val JOYSTICK_SIZE = 220f
    const val JOYSTICK_DEADZONE = 12f
    const val JUMP_BUTTON_SIZE = 132f
    const val CRAWL_BUTTON_SIZE = 114f
    const val DIG_BUTTON_SIZE = 116f
    const val LIGHT_BUTTON_SIZE = 100f
  }
}

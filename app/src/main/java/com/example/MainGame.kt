package com.example

import com.badlogic.gdx.Game

class MainGame : Game() {
  override fun create() {
    setScreen(GameScreen())
  }

  override fun dispose() {
    screen?.dispose()
    super.dispose()
  }
}

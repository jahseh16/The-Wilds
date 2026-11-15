package com.example

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration

class AndroidLauncher : AndroidApplication() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val config = AndroidApplicationConfiguration().apply {
      useGL30 = true
      useAccelerometer = false
      useCompass = false
      useGyroscope = false
      useImmersiveMode = true
      numSamples = 2
    }

    initialize(MainGame(), config)
  }
}

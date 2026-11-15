package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Disposable
import kotlin.random.Random

/**
 * Footsteps, shovel digs and ambient wind.
 *
 * Each sound is loaded from assets/sounds/{step,dig,wind}.ogg when the file exists. Otherwise a
 * short sound is synthesised in memory ([SynthSounds]) and handed to the audio backend through a
 * small generated WAV, so the game has working audio before any real asset is added. Loading never
 * throws: a sound that cannot be created simply stays silent.
 */
class SoundManager(
  private val soundsDir: String = "sounds",
  private val generatedDir: String = "generated_audio"
) : Disposable {

  private val stepSound: Sound? = loadSound("step") { SynthSounds.step() }
  private val digSound: Sound? = loadSound("dig") { SynthSounds.dig() }
  private val wind: AmbientLoop? = loadWind()

  private val random = Random(System.nanoTime())
  private var stepTimer = STEP_IDLE_DELAY
  private var windStarted = false

  /** Master switch, e.g. for a future mute button. */
  var enabled: Boolean = true
    set(value) {
      field = value
      if (value) resumeAmbient() else pauseAmbient()
    }

  /**
   * Call once per frame. A footstep plays every [STEP_INTERVAL] seconds (slower and quieter when
   * crawling) while the player is walking on the ground; stopping resets the stride.
   */
  fun update(delta: Float, walkingOnGround: Boolean, crawling: Boolean) {
    if (!enabled) return
    if (!windStarted) startAmbient()

    if (!walkingOnGround) {
      stepTimer = STEP_IDLE_DELAY
      return
    }
    stepTimer -= delta
    if (stepTimer <= 0f) {
      playStep(crawling)
      stepTimer += if (crawling) CRAWL_STEP_INTERVAL else STEP_INTERVAL
    }
  }

  fun playStep(crawling: Boolean = false) {
    if (!enabled) return
    val pitch = (if (crawling) 0.80f else 0.92f) + random.nextFloat() * 0.20f
    val volume = (if (crawling) 0.28f else 0.55f) * (0.9f + random.nextFloat() * 0.2f)
    safely { stepSound?.play(volume, pitch, 0f) }
  }

  fun playDig() {
    if (!enabled) return
    val pitch = 0.90f + random.nextFloat() * 0.20f
    safely { digSound?.play(0.85f, pitch, 0f) }
  }

  fun pauseAmbient() {
    safely { wind?.pause() }
  }

  fun resumeAmbient() {
    if (!enabled) return
    if (!windStarted) startAmbient() else safely { wind?.resume() }
  }

  private fun startAmbient() {
    windStarted = true
    safely { wind?.start(WIND_VOLUME) }
  }

  override fun dispose() {
    safely { wind?.dispose() }
    safely { stepSound?.dispose() }
    safely { digSound?.dispose() }
  }

  // --- Loading ---------------------------------------------------------------------------------

  private fun loadSound(name: String, synthesize: () -> ShortArray): Sound? {
    val audio = Gdx.audio ?: return null
    try {
      val file = Gdx.files.internal("$soundsDir/$name.ogg")
      if (file.exists()) return audio.newSound(file)
    } catch (t: Throwable) {
      log("Could not load $soundsDir/$name.ogg, using synthesized fallback", t)
    }
    return try {
      audio.newSound(writeGeneratedWav(name, synthesize()))
    } catch (t: Throwable) {
      log("Could not synthesize '$name', it will stay silent", t)
      null
    }
  }

  private fun loadWind(): AmbientLoop? {
    val audio = Gdx.audio ?: return null
    try {
      val file = Gdx.files.internal("$soundsDir/wind.ogg")
      if (file.exists()) {
        return MusicLoop(audio.newMusic(file).apply { isLooping = true })
      }
    } catch (t: Throwable) {
      log("Could not load $soundsDir/wind.ogg, using synthesized fallback", t)
    }
    return try {
      SoundLoop(audio.newSound(writeGeneratedWav("wind", SynthSounds.wind())))
    } catch (t: Throwable) {
      log("Could not synthesize wind, it will stay silent", t)
      null
    }
  }

  private fun writeGeneratedWav(name: String, pcm: ShortArray): FileHandle {
    val handle = Gdx.files.local("$generatedDir/$name.wav")
    handle.writeBytes(SynthSounds.toWav(pcm), false)
    return handle
  }

  private inline fun safely(block: () -> Unit) {
    try {
      block()
    } catch (t: Throwable) {
      log("Audio call failed", t)
    }
  }

  private fun log(message: String, t: Throwable) {
    Gdx.app?.error(TAG, message, t)
  }

  // --- Ambient loop abstraction (streamed Music for real files, looped Sound for the synth) -----

  private interface AmbientLoop {
    fun start(volume: Float)
    fun pause()
    fun resume()
    fun dispose()
  }

  private class MusicLoop(private val music: Music) : AmbientLoop {
    override fun start(volume: Float) {
      music.volume = volume
      music.play()
    }
    override fun pause() = music.pause()
    override fun resume() = music.play()
    override fun dispose() = music.dispose()
  }

  private class SoundLoop(private val sound: Sound) : AmbientLoop {
    private var id = -1L
    override fun start(volume: Float) {
      id = sound.loop(volume)
    }
    override fun pause() {
      if (id != -1L) sound.pause(id)
    }
    override fun resume() {
      if (id != -1L) sound.resume(id)
    }
    override fun dispose() = sound.dispose()
  }

  private companion object {
    const val TAG = "SoundManager"
    const val STEP_INTERVAL = 0.43f
    const val CRAWL_STEP_INTERVAL = 0.78f
    const val STEP_IDLE_DELAY = 0.10f
    const val WIND_VOLUME = 0.30f
  }
}

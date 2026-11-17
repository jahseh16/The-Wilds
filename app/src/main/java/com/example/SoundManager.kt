package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Disposable
import kotlin.random.Random

/**
 * Footsteps (per ground surface, synchronised with the head bob by [PlayerController]), shovel digs,
 * tree felling (strikes, creak, thud, log pickup) and ambient wind.
 *
 * Each sound is loaded from assets/sounds/{step,dig,wind}.ogg when the file exists. Otherwise a
 * short sound is synthesised in memory ([SynthSounds]) and handed to the audio backend through a
 * small generated WAV, so the game has working audio before any real asset is added. Loading never
 * throws: a sound that cannot be created simply stays silent.
 */
class SoundManager(
  private val soundsDir: String = "sounds",
  private val generatedDir: String = "generated_audio"
) : Disposable, FellingSounds {

  private val stepSounds: Map<SynthSounds.Surface, List<Sound>> = SynthSounds.Surface.values().associateWith { surface ->
    (0 until STEP_VARIANTS).mapNotNull { v ->
      loadSound("step_${surface.name.lowercase()}_$v") { SynthSounds.footstep(surface, v) }
    }
  }
  private val chopSounds: List<Sound> = (0 until 3).mapNotNull { v -> loadSound("chop_$v") { SynthSounds.chop(v) } }
  private val creakSound: Sound? = loadSound("creak") { SynthSounds.creak() }
  private val thudSound: Sound? = loadSound("tree_thud") { SynthSounds.thud() }
  private val digSound: Sound? = loadSound("dig") { SynthSounds.dig() }
  private val wind: AmbientLoop? = loadWind()

  private val random = Random(System.nanoTime())
  private var windStarted = false
  private var lastStepVariant = -1

  /** Where the listener (the camera) is: world-space sounds are attenuated with the distance to it. */
  private var listenerX = 0f
  private var listenerY = 0f
  private var listenerZ = 0f

  /** Master switch, e.g. for a future mute button. */
  var enabled: Boolean = true
    set(value) {
      field = value
      if (value) resumeAmbient() else pauseAmbient()
    }

  /** Call once per frame: starts the ambient loop and moves the listener. */
  fun update(listener: com.badlogic.gdx.math.Vector3) {
    listenerX = listener.x; listenerY = listener.y; listenerZ = listener.z
    if (!enabled) return
    if (!windStarted) startAmbient()
  }

  /** One footstep on [surface]; [intensity] from [PlayerController] (0.3 walk .. 1 run, more for landings). */
  fun playFootstep(surface: SynthSounds.Surface, intensity: Float, crawling: Boolean) {
    if (!enabled) return
    val list = stepSounds[surface].orEmpty()
    if (list.isEmpty()) return
    // never the same variant twice in a row
    var v = random.nextInt(list.size)
    if (list.size > 1 && v == lastStepVariant) v = (v + 1) % list.size
    lastStepVariant = v
    val pitch = (if (crawling) 0.85f else 0.94f) + random.nextFloat() * 0.12f
    val volume = ((if (crawling) 0.30f else 0.32f + 0.38f * intensity) * (0.9f + random.nextFloat() * 0.2f)).coerceAtMost(1f)
    safely { list[v].play(volume, pitch, 0f) }
  }

  // --- FellingSounds ------------------------------------------------------------------------

  override fun chop(x: Float, y: Float, z: Float, hitIndex: Int) {
    if (!enabled || chopSounds.isEmpty()) return
    val pitch = 0.88f + 0.05f * (hitIndex % 4) + random.nextFloat() * 0.10f
    safely { chopSounds[(hitIndex + random.nextInt(2)) % chopSounds.size].play(0.9f * attenuation(x, y, z), pitch, 0f) }
  }

  override fun creak(x: Float, y: Float, z: Float) {
    if (!enabled) return
    safely { creakSound?.play(0.75f * attenuation(x, y, z), 0.95f + random.nextFloat() * 0.1f, 0f) }
  }

  override fun thud(x: Float, y: Float, z: Float, strength: Float) {
    if (!enabled) return
    safely { thudSound?.play((0.45f + 0.55f * strength) * attenuation(x, y, z), 0.9f + random.nextFloat() * 0.15f, 0f) }
  }

  override fun pickup(x: Float, y: Float, z: Float) {
    if (!enabled || chopSounds.isEmpty()) return
    safely { chopSounds[0].play(0.45f, 1.35f, 0f) }
  }

  /** 1 within 4 m, falling off as 4/d (never below 0.12 so a nearby fall is always heard). */
  private fun attenuation(x: Float, y: Float, z: Float): Float {
    val dx = x - listenerX; val dy = y - listenerY; val dz = z - listenerZ
    val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    return (4f / d.coerceAtLeast(4f)).coerceAtLeast(0.12f)
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
    for (list in stepSounds.values) for (snd in list) safely { snd.dispose() }
    for (snd in chopSounds) safely { snd.dispose() }
    safely { creakSound?.dispose() }
    safely { thudSound?.dispose() }
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
    const val STEP_VARIANTS = 3
    const val WIND_VOLUME = 0.30f
  }
}

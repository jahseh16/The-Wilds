package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Disposable
import kotlin.random.Random

/**
 * All the game's audio: footsteps per surface (synchronised with the head bob by [PlayerController]),
 * landings, splashes and wading, crouching through vegetation, shovel digs, tree felling (strikes,
 * creak, thud, log pickup) and the ambient layers (wind, surf, lake, birds by day, crickets by night),
 * whose volumes follow the player's position and the time of day through [AmbienceState].
 *
 * Every sound is loaded from assets/sounds/<name>.ogg when the file exists (one-shots as [Sound],
 * loops as streamed [Music]). Otherwise it is synthesised in memory ([SynthSounds]) and handed to the
 * audio backend through a small generated WAV, so the game always has audio. Loading never throws:
 * a sound that cannot be created simply stays silent.
 *
 * Expected names: step_{forest,grass,sand,rock,water}_0..2, chop_0..2, creak, tree_thud, dig_0..2,
 * pickup_log, land_0..1, splash_0..2, leaves_rustle_0..1 and the loops wind, waves_loop, lake_loop,
 * birds_loop, crickets_loop.
 */
class SoundManager(
  private val soundsDir: String = "sounds",
  private val generatedDir: String = "generated_audio"
) : Disposable, FellingSounds {

  private val stepSounds: Map<SynthSounds.Surface, List<Sound>> = SynthSounds.Surface.values().associateWith { surface ->
    loadVariants("step_${surface.name.lowercase()}", STEP_VARIANTS) { v -> SynthSounds.footstep(surface, v) }
  }
  private val chopSounds: List<Sound> = loadVariants("chop", 3) { SynthSounds.chop(it) }
  private val creakSound: Sound? = loadSound("creak") { SynthSounds.creak() }
  private val thudSound: Sound? = loadSound("tree_thud") { SynthSounds.thud() }
  private val digSounds: List<Sound> = loadVariants("dig", 3) { SynthSounds.dig() }
  private val pickupSound: Sound? = loadSound("pickup_log") { SynthSounds.pickupLog() }
  private val landSounds: List<Sound> = loadVariants("land", 2) { SynthSounds.land(it) }
  private val splashSounds: List<Sound> = loadVariants("splash", 3) { SynthSounds.splash(it) }
  private val rustleSounds: List<Sound> = loadVariants("leaves_rustle", 2) { SynthSounds.leavesRustle(it) }

  private val windLayer = Layer(loadLoop("wind") { SynthSounds.wind() })
  private val wavesLayer = Layer(loadLoop("waves_loop") { SynthSounds.waves() })
  private val lakeLayer = Layer(loadLoop("lake_loop") { SynthSounds.lake() })
  private val birdsLayer = Layer(loadLoop("birds_loop") { SynthSounds.birds() })
  private val cricketsLayer = Layer(loadLoop("crickets_loop") { SynthSounds.crickets() })
  private val layers = listOf(windLayer, wavesLayer, lakeLayer, birdsLayer, cricketsLayer)

  private val random = Random(System.nanoTime())
  private var lastStepVariant = -1
  private var clock = 0f
  private var lastRustleAt = -10f
  private var lastSplashAt = -10f
  private var ambientSuspended = false

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

  /**
   * Call once per frame: moves the listener and fades the ambient layers towards the volumes that
   * [ambience] asks for. A layer that has faded to silence is paused so it costs nothing.
   */
  fun update(listener: com.badlogic.gdx.math.Vector3, dt: Float, ambience: AmbienceState) {
    listenerX = listener.x; listenerY = listener.y; listenerZ = listener.z
    clock += dt
    windLayer.target = AmbienceMix.wind(ambience)
    wavesLayer.target = AmbienceMix.waves(ambience)
    lakeLayer.target = AmbienceMix.lake(ambience)
    birdsLayer.target = AmbienceMix.birds(ambience)
    cricketsLayer.target = AmbienceMix.crickets(ambience)
    if (!enabled || ambientSuspended) return
    for (layer in layers) safely { layer.update(dt) }
  }

  /** One footstep on [surface]; [intensity] from [PlayerController] (0.3 walk .. 1 run). */
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
    if (crawling && (surface == SynthSounds.Surface.FOREST || surface == SynthSounds.Surface.GRASS)) rustle(0.35f)
  }

  /** The player drops into a crouch / crawl: vegetation brushes the body when on forest floor or meadow. */
  fun playCrouch(surface: SynthSounds.Surface) {
    if (surface == SynthSounds.Surface.FOREST || surface == SynthSounds.Surface.GRASS) rustle(0.55f)
  }

  private fun rustle(volume: Float) {
    if (!enabled || rustleSounds.isEmpty() || clock - lastRustleAt < RUSTLE_MIN_INTERVAL) return
    lastRustleAt = clock
    val snd = rustleSounds[random.nextInt(rustleSounds.size)]
    safely { snd.play(volume * (0.85f + random.nextFloat() * 0.3f), 0.92f + random.nextFloat() * 0.16f, 0f) }
  }

  /** Landing from a jump or fall on dry ground; [intensity] as in [PlayerController.StepListener.onStep]. */
  fun playLand(intensity: Float) {
    if (!enabled || landSounds.isEmpty()) return
    val snd = landSounds[random.nextInt(landSounds.size)]
    safely { snd.play((0.45f + 0.35f * intensity).coerceAtMost(1f), 0.92f + random.nextFloat() * 0.12f, 0f) }
  }

  /** Entering (or landing in) the water; [intensity] ~0.4 wading in .. 1.5 a hard fall. */
  fun playSplash(intensity: Float) {
    if (!enabled || splashSounds.isEmpty() || clock - lastSplashAt < SPLASH_MIN_INTERVAL) return
    lastSplashAt = clock
    val snd = splashSounds[random.nextInt(splashSounds.size)]
    safely { snd.play((0.40f + 0.40f * intensity).coerceAtMost(1f), 1.05f - 0.2f * intensity.coerceAtMost(1f) + random.nextFloat() * 0.1f, 0f) }
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
    if (!enabled) return
    safely { pickupSound?.play(0.7f, 0.95f + random.nextFloat() * 0.1f, 0f) }
  }

  /** 1 within 4 m, falling off as 4/d (never below 0.12 so a nearby fall is always heard). */
  private fun attenuation(x: Float, y: Float, z: Float): Float {
    val dx = x - listenerX; val dy = y - listenerY; val dz = z - listenerZ
    val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    return (4f / d.coerceAtLeast(4f)).coerceAtLeast(0.12f)
  }

  fun playDig() {
    if (!enabled || digSounds.isEmpty()) return
    val pitch = 0.90f + random.nextFloat() * 0.20f
    safely { digSounds[random.nextInt(digSounds.size)].play(0.85f, pitch, 0f) }
  }

  /** App went to the background (or the game was paused): silence every loop. */
  fun pauseAmbient() {
    ambientSuspended = true
    for (layer in layers) safely { layer.suspend() }
  }

  fun resumeAmbient() {
    ambientSuspended = false
  }

  override fun dispose() {
    for (layer in layers) safely { layer.dispose() }
    for (list in stepSounds.values) for (snd in list) safely { snd.dispose() }
    for (list in listOf(chopSounds, digSounds, landSounds, splashSounds, rustleSounds)) for (snd in list) safely { snd.dispose() }
    safely { creakSound?.dispose() }
    safely { thudSound?.dispose() }
    safely { pickupSound?.dispose() }
  }

  // --- Loading ---------------------------------------------------------------------------------

  private fun loadVariants(prefix: String, count: Int, synthesize: (Int) -> ShortArray): List<Sound> =
    (0 until count).mapNotNull { v -> loadSound("${prefix}_$v") { synthesize(v) } }

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

  /** A loop: streamed [Music] from the real file, or a looped [Sound] of the synthesised fallback. */
  private fun loadLoop(name: String, synthesize: () -> ShortArray): AmbientLoop? {
    val audio = Gdx.audio ?: return null
    try {
      val file = Gdx.files.internal("$soundsDir/$name.ogg")
      if (file.exists()) {
        return MusicLoop(audio.newMusic(file).apply { isLooping = true })
      }
    } catch (t: Throwable) {
      log("Could not load $soundsDir/$name.ogg, using synthesized fallback", t)
    }
    return try {
      SoundLoop(audio.newSound(writeGeneratedWav(name, synthesize())))
    } catch (t: Throwable) {
      log("Could not synthesize '$name', it will stay silent", t)
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
    fun setVolume(volume: Float)
    fun pause()
    fun resume()
    fun dispose()
  }

  private class MusicLoop(private val music: Music) : AmbientLoop {
    override fun start(volume: Float) {
      music.volume = volume
      music.play()
    }
    override fun setVolume(volume: Float) { music.volume = volume }
    override fun pause() = music.pause()
    override fun resume() = music.play()
    override fun dispose() = music.dispose()
  }

  private class SoundLoop(private val sound: Sound) : AmbientLoop {
    private var id = -1L
    override fun start(volume: Float) {
      id = sound.loop(volume)
    }
    override fun setVolume(volume: Float) {
      if (id != -1L) sound.setVolume(id, volume)
    }
    override fun pause() {
      if (id != -1L) sound.pause(id)
    }
    override fun resume() {
      if (id != -1L) sound.resume(id)
    }
    override fun dispose() = sound.dispose()
  }

  /** One ambient loop with a smoothly following volume; it is only playing while it is audible. */
  private class Layer(private val loop: AmbientLoop?) {
    var target = 0f
    private var volume = 0f
    private var started = false
    private var playing = false

    fun update(dt: Float) {
      val l = loop ?: return
      val rate = if (target > volume) FADE_IN else FADE_OUT
      volume += (target - volume) * (rate * dt).coerceAtMost(1f)
      if (volume < SILENT && target < SILENT) {
        if (playing) { l.pause(); playing = false }
        return
      }
      if (!started) {
        l.start(volume); started = true; playing = true
      } else if (!playing) {
        l.setVolume(volume); l.resume(); playing = true
      } else {
        l.setVolume(volume)
      }
    }

    fun suspend() {
      if (playing) { loop?.pause(); playing = false }
    }

    fun dispose() { loop?.dispose() }
  }

  private companion object {
    const val TAG = "SoundManager"
    const val STEP_VARIANTS = 3
    const val FADE_IN = 0.8f
    const val FADE_OUT = 0.6f
    const val SILENT = 0.004f
    const val RUSTLE_MIN_INTERVAL = 0.55f
    const val SPLASH_MIN_INTERVAL = 0.35f
  }
}

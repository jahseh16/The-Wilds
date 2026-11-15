package com.example

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.environment.SpotLight
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GameScreen : Screen {

  private val camera: PerspectiveCamera
  private val modelBatch: ModelBatch = ModelBatch(
    DefaultShaderProvider(
      DefaultShader.Config().apply {
        numDirectionalLights = 1
        numSpotLights = 1
      }
    )
  )
  private val environment: Environment
  private val flashlight: SpotLight
  private val volumeMesh: VoxelVolumeMesh
  private val hudStage: HudStage

  // Anti-pixelation terrain textures (diffuse + normal maps) & tri-planar shader
  private val grassColorTex: Texture
  private val grassNormalTex: Texture
  private val dirtColorTex: Texture
  private val dirtNormalTex: Texture
  private val rockColorTex: Texture
  private val rockNormalTex: Texture
  private val terrainShader: TerrainShader

  // First-person 3D shovel viewmodel
  private val shovelModel: Model
  private val shovelInstance: ModelInstance
  private val cameraWorldMatrix = Matrix4()
  private val shovelLocalMatrix = Matrix4()
  private var swingTimer: Float = -1f
  private var walkBobTime: Float = 0f

  // Player spatial, stance & vertical physics state
  private var playerX: Float
  private var playerZ: Float
  private var playerFeetY: Float
  private var verticalVelocity: Float = 0f
  private var isGrounded: Boolean = false
  private var currentEyeHeight: Float = STAND_EYE_HEIGHT
  private var isActuallyCrawling: Boolean = false

  // First-person camera orientation in degrees
  private var yawDegrees: Float = 0f
  private var pitchDegrees: Float = -18f

  // Aim state within 3.5 meters (throttled to max 20 Hz)
  private val aimHitPoint = Vector3()
  private var hasAimHit: Boolean = false
  private var aimRaycastTimer: Float = AIM_RAYCAST_INTERVAL

  // Reusable movement vectors to avoid per-frame allocations
  private val forwardXZ = Vector3()
  private val rightXZ = Vector3()

  init {
    volumeMesh = VoxelVolumeMesh()
    playerX = (volumeMesh.minX + volumeMesh.maxX) * 0.5f
    playerZ = (volumeMesh.minZ + volumeMesh.maxZ) * 0.5f
    playerFeetY = volumeMesh.findSpawnY(playerX, playerZ)

    flashlight = SpotLight().apply {
      set(
        Color(1.0f, 0.94f, 0.80f, 1f),
        Vector3(0f, 0f, 0f),
        Vector3(0f, 0f, -1f),
        FLASHLIGHT_INTENSITY,
        FLASHLIGHT_CUTOFF_ANGLE,
        FLASHLIGHT_EXPONENT
      )
    }

    environment = Environment().apply {
      set(ColorAttribute.createAmbientLight(0.5f, 0.5f, 0.5f, 1f))
      add(DirectionalLight().set(0.95f, 0.95f, 0.90f, -0.5f, -1f, -0.3f))
      add(flashlight)
    }

    camera = PerspectiveCamera(
      FIELD_OF_VIEW,
      Gdx.graphics.width.toFloat(),
      Gdx.graphics.height.toFloat()
    ).apply {
      position.set(playerX, playerFeetY + currentEyeHeight, playerZ)
      near = 0.05f
      far = 160f
    }
    updateCameraOrientation()

    shovelModel = createShovelModel()
    shovelInstance = ModelInstance(shovelModel)

    grassColorTex = loadTerrainTexture("textures/grass_color.png")
    grassNormalTex = loadTerrainTexture("textures/grass_normal.png")
    dirtColorTex = loadTerrainTexture("textures/dirt_color.png")
    dirtNormalTex = loadTerrainTexture("textures/dirt_normal.png")
    rockColorTex = loadTerrainTexture("textures/rock_color.png")
    rockNormalTex = loadTerrainTexture("textures/rock_normal.png")

    terrainShader = TerrainShader(
      grassColor = grassColorTex,
      grassNormal = grassNormalTex,
      dirtColor = dirtColorTex,
      dirtNormal = dirtNormalTex,
      rockColor = rockColorTex,
      rockNormal = rockNormalTex
    ).apply {
      init()
    }

    hudStage = HudStage { deltaX, deltaY ->
      rotateCamera(deltaX, deltaY)
    }
  }

  private fun loadTerrainTexture(internalPath: String): Texture =
    Texture(Gdx.files.internal(internalPath), true).apply {
      setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.MipMapLinearLinear)
      setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat)
    }

  override fun show() {
    Gdx.input.inputProcessor = hudStage.inputProcessor
  }

  override fun render(delta: Float) {
    val dt = delta.coerceAtMost(0.05f)

    updatePlayerPhysics(dt)
    camera.update()
    updateFlashlight()
    updateAimAndExcavation(dt)
    updateShovelViewmodel(dt)

    Gdx.gl.glViewport(0, 0, Gdx.graphics.width, Gdx.graphics.height)
    Gdx.gl.glClearColor(0.45f, 0.65f, 0.85f, 1f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

    // Render 3D world with tri-planar normal-mapped shader & ground aim ring
    modelBatch.begin(camera)
    for (i in 0 until volumeMesh.chunkCount) {
      val chunkInstance = volumeMesh.getVisibleChunkInstance(i)
      if (chunkInstance != null) {
        modelBatch.render(chunkInstance, environment, terrainShader)
      }
    }
    if (hasAimHit) {
      modelBatch.render(volumeMesh.aimIndicatorInstance)
    }
    modelBatch.end()

    // Clear only depth buffer so the 3D shovel viewmodel never clips into tunnel walls
    Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT)
    modelBatch.begin(camera)
    modelBatch.render(shovelInstance, environment)
    modelBatch.end()

    hudStage.act(dt)
    hudStage.draw()
  }

  private fun updatePlayerPhysics(dt: Float) {
    // Update posture: allow standing up only when ceiling clearance above 0.85m is free
    val wantsCrawl = hudStage.isCrawling
    isActuallyCrawling = wantsCrawl || !canStandUp(playerX, playerFeetY, playerZ)

    val activeHeadHeight = if (isActuallyCrawling) CRAWL_BODY_HEIGHT else STAND_HEAD_HEIGHT
    val activeMoveSpeed = if (isActuallyCrawling) MOVE_SPEED * CRAWL_SPEED_MULTIPLIER else MOVE_SPEED

    // 1. Horizontal movement with independent X/Z volumetric collision
    val moveX = hudStage.moveX
    val moveY = hudStage.moveY
    val isMoving = moveX != 0f || moveY != 0f
    if (isMoving) {
      forwardXZ.set(camera.direction.x, 0f, camera.direction.z)
      if (forwardXZ.len2() > 1e-6f) {
        forwardXZ.nor()
      } else {
        forwardXZ.set(0f, 0f, -1f)
      }
      rightXZ.set(forwardXZ).crs(Vector3.Y).nor()

      val stepX = (forwardXZ.x * moveY + rightXZ.x * moveX) * activeMoveSpeed * dt
      val stepZ = (forwardXZ.z * moveY + rightXZ.z * moveX) * activeMoveSpeed * dt

      tryMoveHorizontalAxis(deltaX = stepX, deltaZ = 0f, bodyHeight = activeHeadHeight)
      tryMoveHorizontalAxis(deltaX = 0f, deltaZ = stepZ, bodyHeight = activeHeadHeight)

      if (isGrounded) {
        walkBobTime += dt * (if (isActuallyCrawling) 7.5f else 10.5f)
      }
    }

    // 2. Jump trigger when grounded and overhead clearance is free
    if (hudStage.consumeJumpRequest() && isGrounded) {
      if (!collidesBodyCylinder(playerX, playerFeetY + 0.18f, playerZ, activeHeadHeight, includeFeet = false)) {
        verticalVelocity = JUMP_VELOCITY
        isGrounded = false
      }
    }

    // 3. Vertical gravity, tunnel floor support & cave ceiling collision
    if (isGrounded) {
      val floorY = findFloorNear(playerX, playerFeetY, playerZ)
      if (floorY != null) {
        playerFeetY = floorY
        verticalVelocity = 0f
      } else {
        isGrounded = false
      }
    } else {
      verticalVelocity += GRAVITY * dt
      val nextFeetY = playerFeetY + verticalVelocity * dt

      if (verticalVelocity > 0f) {
        if (collidesBodyCylinder(playerX, nextFeetY, playerZ, activeHeadHeight, includeFeet = false)) {
          verticalVelocity = 0f
        } else {
          playerFeetY = nextFeetY
        }
      } else {
        if (isSolidFeet(playerX, nextFeetY, playerZ)) {
          playerFeetY = resolveLandingY(playerX, playerFeetY, nextFeetY, playerZ)
          verticalVelocity = 0f
          isGrounded = true
        } else {
          playerFeetY = nextFeetY.coerceAtLeast(volumeMesh.minY + 0.5f)
        }
      }
    }

    // 4. Smoothly interpolate camera eye height between standing (1.75m) and crawling (0.65m)
    val targetEyeHeight = if (isActuallyCrawling) CRAWL_EYE_HEIGHT else STAND_EYE_HEIGHT
    val lerpAlpha = (EYE_HEIGHT_LERP_SPEED * dt).coerceIn(0f, 1f)
    currentEyeHeight += (targetEyeHeight - currentEyeHeight) * lerpAlpha

    camera.position.set(playerX, playerFeetY + currentEyeHeight, playerZ)
  }

  private fun tryMoveHorizontalAxis(deltaX: Float, deltaZ: Float, bodyHeight: Float) {
    if (deltaX == 0f && deltaZ == 0f) return

    val targetX = (playerX + deltaX).coerceIn(
      volumeMesh.minX + TERRAIN_EDGE_MARGIN,
      volumeMesh.maxX - TERRAIN_EDGE_MARGIN
    )
    val targetZ = (playerZ + deltaZ).coerceIn(
      volumeMesh.minZ + TERRAIN_EDGE_MARGIN,
      volumeMesh.maxZ - TERRAIN_EDGE_MARGIN
    )

    if (!isGrounded) {
      if (!collidesBodyCylinder(targetX, playerFeetY, targetZ, bodyHeight, includeFeet = true)) {
        playerX = targetX
        playerZ = targetZ
      }
      return
    }

    val targetFloorY = findFloorNear(targetX, playerFeetY, targetZ)
    val candidateY = targetFloorY ?: playerFeetY
    if (!collidesBodyCylinder(targetX, candidateY, targetZ, bodyHeight, includeFeet = false)) {
      playerX = targetX
      playerZ = targetZ
      if (targetFloorY != null) {
        playerFeetY = targetFloorY
      }
    }
  }

  private fun canStandUp(x: Float, feetY: Float, z: Float): Boolean {
    return !collidesBodyCylinder(x, feetY, z, STAND_HEAD_HEIGHT, includeFeet = false)
  }

  private fun isSolidPoint(x: Float, y: Float, z: Float): Boolean =
    volumeMesh.getDensityAt(x, y, z) > VoxelVolumeMesh.ISO_LEVEL

  private fun isSolidFeet(x: Float, y: Float, z: Float): Boolean {
    if (isSolidPoint(x, y, z)) return true
    val r = PLAYER_RADIUS * 0.55f
    return isSolidPoint(x + r, y, z) ||
      isSolidPoint(x - r, y, z) ||
      isSolidPoint(x, y, z + r) ||
      isSolidPoint(x, y, z - r)
  }

  /**
   * Checks horizontal capsule/cylinder clearance for either Standing (2-point feet/head = 1.85m)
   * or Crawling (reduced height = 0.85m) against cave walls and low tunnel ceilings.
   */
  private fun collidesBodyCylinder(
    x: Float,
    feetY: Float,
    z: Float,
    bodyHeight: Float,
    includeFeet: Boolean
  ): Boolean {
    val lowOffset = if (includeFeet) 0.15f else STEP_UP_HEIGHT.coerceAtMost(bodyHeight * 0.6f)
    val hLow = feetY + lowOffset
    val hTop = feetY + bodyHeight
    val checkMid = bodyHeight > 1.2f
    val hMid = feetY + bodyHeight * 0.6f

    val r = PLAYER_RADIUS
    val diag = r * 0.7071f

    if (isSolidPoint(x, hLow, z) || isSolidPoint(x, hTop, z) || (checkMid && isSolidPoint(x, hMid, z))) {
      return true
    }

    val offsetsX = floatArrayOf(r, -r, 0f, 0f, diag, -diag, diag, -diag)
    val offsetsZ = floatArrayOf(0f, 0f, r, -r, diag, diag, -diag, -diag)
    for (i in 0 until 8) {
      val sx = x + offsetsX[i]
      val sz = z + offsetsZ[i]
      if (isSolidPoint(sx, hLow, sz) ||
        isSolidPoint(sx, hTop, sz) ||
        (checkMid && isSolidPoint(sx, hMid, sz))
      ) {
        return true
      }
    }
    return false
  }

  private fun findFloorNear(x: Float, currentFeetY: Float, z: Float): Float? {
    val stepLimit = if (isActuallyCrawling) CRAWL_STEP_UP_HEIGHT else STEP_UP_HEIGHT
    val topScan = currentFeetY + stepLimit
    val bottomScan = currentFeetY - GROUND_SNAP_DISTANCE
    val step = 0.08f

    var prevY = topScan
    if (isSolidFeet(x, prevY, z)) {
      var upY = topScan + step
      while (upY <= currentFeetY + stepLimit + 0.20f) {
        if (!isSolidFeet(x, upY, z)) {
          return refineSurfaceY(x, prevY, upY, z)
        }
        prevY = upY
        upY += step
      }
      return null
    }

    var y = topScan - step
    while (y >= bottomScan) {
      if (isSolidFeet(x, y, z)) {
        return refineSurfaceY(x, y, prevY, z)
      }
      prevY = y
      y -= step
    }
    return null
  }

  private fun resolveLandingY(x: Float, airY: Float, solidY: Float, z: Float): Float {
    return refineSurfaceY(x, solidY, airY.coerceAtLeast(solidY), z)
  }

  private fun refineSurfaceY(x: Float, solidY: Float, airY: Float, z: Float): Float {
    var low = solidY
    var high = airY
    repeat(6) {
      val mid = (low + high) * 0.5f
      if (isSolidFeet(x, mid, z)) low = mid else high = mid
    }
    return high
  }

  private fun updateFlashlight() {
    flashlight.position.set(camera.position)
    flashlight.direction.set(camera.direction)
    flashlight.intensity = if (hudStage.isFlashlightOn) FLASHLIGHT_INTENSITY else 0f
  }

  private fun updateAimAndExcavation(dt: Float) {
    aimRaycastTimer += dt
    val digPressed = hudStage.consumeDigRequest()

    if (aimRaycastTimer >= AIM_RAYCAST_INTERVAL || digPressed) {
      aimRaycastTimer = 0f
      hasAimHit = volumeMesh.raycast(
        origin = camera.position,
        direction = camera.direction,
        maxDistance = MAX_DIG_RANGE,
        outHit = aimHitPoint
      )
      if (hasAimHit) {
        volumeMesh.updateAimIndicator(aimHitPoint)
      }
    }

    if (digPressed) {
      // Trigger smooth shovel swing animation immediately on dig tap
      swingTimer = 0f

      if (hasAimHit) {
        volumeMesh.excavateSphere(
          center = aimHitPoint,
          radius = DIG_SPHERE_RADIUS,
          strength = DIG_SPHERE_STRENGTH
        )

        if (isGrounded && !isSolidFeet(playerX, playerFeetY - 0.15f, playerZ)) {
          isGrounded = false
        }

        hasAimHit = volumeMesh.raycast(
          origin = camera.position,
          direction = camera.direction,
          maxDistance = MAX_DIG_RANGE,
          outHit = aimHitPoint
        )
        if (hasAimHit) {
          volumeMesh.updateAimIndicator(aimHitPoint)
        }
      }
    }
  }

  private fun updateShovelViewmodel(dt: Float) {
    var swingForward = 0f
    var swingDown = 0f
    var swingPitch = 0f
    var swingYaw = 0f

    if (swingTimer >= 0f) {
      swingTimer += dt
      val progress = (swingTimer / SHOVEL_SWING_DURATION).coerceIn(0f, 1f)
      // Fast strike peak around t=0.32, smooth recovery back to 0 at t=1.0
      val strikeCurve = sin(progress * PI).toFloat()
      val snapFactor = if (progress < 0.35f) {
        sin((progress / 0.35f) * (PI * 0.5)).toFloat()
      } else {
        cos(((progress - 0.35f) / 0.65f) * (PI * 0.5)).toFloat()
      }

      swingForward = -0.18f * snapFactor
      swingDown = -0.11f * strikeCurve
      swingPitch = -42f * snapFactor
      swingYaw = 18f * strikeCurve

      if (swingTimer >= SHOVEL_SWING_DURATION) {
        swingTimer = -1f
      }
    }

    val bobX = sin(walkBobTime) * 0.012f
    val bobY = cos(walkBobTime * 2f) * 0.010f

    // Place shovel in bottom-right corner of camera view space
    cameraWorldMatrix.set(camera.view).inv()
    shovelLocalMatrix.idt()
      .translate(0.30f + bobX, -0.34f + bobY + swingDown, -0.50f + swingForward)
      .rotate(Vector3.Y, 16f + swingYaw)
      .rotate(Vector3.X, -58f + swingPitch)
      .rotate(Vector3.Z, 12f)

    shovelInstance.transform.set(cameraWorldMatrix).mul(shovelLocalMatrix)
  }

  private fun createShovelModel(): Model {
    val attrs = (VertexAttributes.Usage.Position or VertexAttributes.Usage.Normal).toLong()
    val modelBuilder = ModelBuilder()
    modelBuilder.begin()

    val gripMaterial = Material(
      ColorAttribute.createDiffuse(Color(0.18f, 0.20f, 0.22f, 1f))
    )
    val woodMaterial = Material(
      ColorAttribute.createDiffuse(Color(0.46f, 0.29f, 0.16f, 1f))
    )
    val steelMaterial = Material(
      ColorAttribute.createDiffuse(Color(0.74f, 0.78f, 0.82f, 1f)),
      ColorAttribute.createSpecular(Color(0.90f, 0.92f, 0.95f, 1f)),
      FloatAttribute.createShininess(24f)
    )

    // Hand grip pommel
    modelBuilder.node().apply {
      id = "grip"
      translation.set(0f, -0.10f, 0f)
    }
    modelBuilder.part("grip", GL20.GL_TRIANGLES, attrs, gripMaterial)
      .cylinder(0.036f, 0.13f, 0.036f, 24)

    // Smooth wooden shaft
    modelBuilder.node().apply {
      id = "shaft"
      translation.set(0f, 0.22f, 0f)
    }
    modelBuilder.part("shaft", GL20.GL_TRIANGLES, attrs, woodMaterial)
      .cylinder(0.026f, 0.56f, 0.026f, 24)

    // Metallic ferrule socket
    modelBuilder.node().apply {
      id = "collar"
      translation.set(0f, 0.51f, 0f)
    }
    modelBuilder.part("collar", GL20.GL_TRIANGLES, attrs, steelMaterial)
      .cylinder(0.034f, 0.08f, 0.034f, 24)

    // Curved metallic spade blade (high-subdivision flattened cone)
    modelBuilder.node().apply {
      id = "blade"
      translation.set(0f, 0.66f, 0.01f)
      rotation.setFromAxis(Vector3.X, -8f)
    }
    modelBuilder.part("blade", GL20.GL_TRIANGLES, attrs, steelMaterial)
      .cone(0.18f, 0.26f, 0.020f, 28)

    return modelBuilder.end()
  }

  private fun rotateCamera(deltaX: Float, deltaY: Float) {
    yawDegrees = (yawDegrees + deltaX * LOOK_SENSITIVITY) % 360f
    pitchDegrees = (pitchDegrees - deltaY * LOOK_SENSITIVITY).coerceIn(MIN_PITCH, MAX_PITCH)
    updateCameraOrientation()
  }

  private fun updateCameraOrientation() {
    val yawRad = Math.toRadians(yawDegrees.toDouble())
    val pitchRad = Math.toRadians(pitchDegrees.toDouble())

    camera.direction.set(
      (cos(pitchRad) * sin(yawRad)).toFloat(),
      sin(pitchRad).toFloat(),
      (-cos(pitchRad) * cos(yawRad)).toFloat()
    ).nor()
    camera.up.set(Vector3.Y)
    camera.update()
  }

  override fun resize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    camera.viewportWidth = width.toFloat()
    camera.viewportHeight = height.toFloat()
    camera.update()
    hudStage.resize(width, height)
  }

  override fun pause() = Unit

  override fun resume() = Unit

  override fun hide() {
    if (Gdx.input.inputProcessor == hudStage.inputProcessor) {
      Gdx.input.inputProcessor = null
    }
  }

  override fun dispose() {
    modelBatch.dispose()
    terrainShader.dispose()
    grassColorTex.dispose()
    grassNormalTex.dispose()
    dirtColorTex.dispose()
    dirtNormalTex.dispose()
    rockColorTex.dispose()
    rockNormalTex.dispose()
    shovelModel.dispose()
    volumeMesh.dispose()
    hudStage.dispose()
  }

  private companion object {
    const val FIELD_OF_VIEW = 67f

    // Stance heights & speeds
    const val STAND_EYE_HEIGHT = 1.75f
    const val STAND_HEAD_HEIGHT = 1.85f
    const val CRAWL_EYE_HEIGHT = 0.65f
    const val CRAWL_BODY_HEIGHT = 0.85f
    const val CRAWL_SPEED_MULTIPLIER = 0.5f
    const val EYE_HEIGHT_LERP_SPEED = 10f

    const val PLAYER_RADIUS = 0.34f
    const val STEP_UP_HEIGHT = 0.55f
    const val CRAWL_STEP_UP_HEIGHT = 0.42f
    const val GROUND_SNAP_DISTANCE = 0.45f
    const val MOVE_SPEED = 5.5f
    const val GRAVITY = -22f
    const val JUMP_VELOCITY = 8.2f
    const val TERRAIN_EDGE_MARGIN = 1.0f
    const val LOOK_SENSITIVITY = 0.22f
    const val MIN_PITCH = -85f
    const val MAX_PITCH = 85f

    // Narrow 0.95m sphere dig radius requires two-height digging (head + feet) to walk standing
    const val MAX_DIG_RANGE = 3.5f
    const val AIM_RAYCAST_INTERVAL = 0.05f
    const val DIG_SPHERE_RADIUS = 0.95f
    const val DIG_SPHERE_STRENGTH = 4.2f

    const val SHOVEL_SWING_DURATION = 0.32f

    const val FLASHLIGHT_INTENSITY = 32f
    const val FLASHLIGHT_CUTOFF_ANGLE = 30f
    const val FLASHLIGHT_EXPONENT = 6f
  }
}

package com.example

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.RenderableProvider
import com.badlogic.gdx.graphics.g3d.Shader
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.Pool
import com.badlogic.gdx.utils.Array as GdxArray
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Where the felling sounds go (implemented by [SoundManager]; no-op in tests). Positions are world metres. */
interface FellingSounds {
  /** A strike on the trunk; [hitIndex] counts the strikes on this tree (pitch varies with it). */
  fun chop(x: Float, y: Float, z: Float, hitIndex: Int) {}
  /** The trunk gives way: long cracking creak while it starts to lean. */
  fun creak(x: Float, y: Float, z: Float) {}
  /** The trunk hits the ground; [strength] 0..1 from the impact speed. */
  fun thud(x: Float, y: Float, z: Float, strength: Float) {}
  /** A log was picked up. */
  fun pickup(x: Float, y: Float, z: Float) {}
}

/**
 * Pure physics of a felled trunk pivoting on its stump: a rigid rod (centre of mass a little above
 * mid-height because of the crown) whose tilt [angle] (radians from vertical, towards [dirX], [dirZ])
 * accelerates as `alpha = 1.5 g / L * sin(angle)`. Contact is tested along the trunk line against
 * [ground] (the analytic terrain): the first impact bounces back a little, then it settles.
 */
class TreeFall(
  val pivotX: Float, val pivotY: Float, val pivotZ: Float,
  val dirX: Float, val dirZ: Float,
  /** Trunk length above the stump (m). */
  val length: Float,
  /** Trunk radius at the cut (m). */
  val radius: Float,
  /** Fraction of [length] where the crown starts (its branches hold the trunk off the ground). */
  val crownStart: Float,
  private val ground: (Float, Float) -> Float
) {
  enum class Event { NONE, IMPACT, SETTLED }

  var angle = START_ANGLE
    private set
  var angularVelocity = START_SPEED
    private set
  var impacts = 0
    private set
  var settled = false
    private set
  /** Impact speed of the first contact (rad/s), for the thud volume. */
  var impactSpeed = 0f
    private set
  /** Where along the trunk (m from the pivot) the last contact happened. */
  var contactAt = 0f
    private set

  fun step(dt: Float): Event {
    if (settled) return Event.NONE
    var event = Event.NONE
    var left = dt
    while (left > 1e-6f && !settled) {
      val h = min(left, MAX_SUBSTEP)
      left -= h
      val alpha = 1.5f * GRAVITY / length * sin(angle) - AIR_DAMPING * angularVelocity
      angularVelocity += alpha * h
      val next = angle + angularVelocity * h
      if (touches(next) || next > MAX_ANGLE) {
        if (angularVelocity > SETTLE_SPEED && impacts < MAX_BOUNCES && next <= MAX_ANGLE) {
          if (impacts == 0) impactSpeed = angularVelocity
          impacts++
          angularVelocity = -angularVelocity * RESTITUTION
          event = Event.IMPACT
        } else {
          angularVelocity = 0f
          settled = true
          event = Event.SETTLED
        }
      } else {
        angle = next
      }
    }
    return event
  }

  /** Point along the trunk at [s] metres from the pivot for the current angle. */
  fun pointAt(s: Float, out: Vector3): Vector3 =
    out.set(pivotX + dirX * s * sin(angle), pivotY + s * cos(angle), pivotZ + dirZ * s * sin(angle))

  private fun touches(a: Float): Boolean {
    val sa = sin(a); val ca = cos(a)
    for (k in 1..SAMPLES) {
      val s = length * k / SAMPLES
      val x = pivotX + dirX * s * sa
      val z = pivotZ + dirZ * s * sa
      val y = pivotY + s * ca
      val clearance = radius * (1f - 0.8f * k / SAMPLES) + if (s > length * crownStart) CROWN_LIFT else 0f
      if (y - clearance < ground(x, z)) { contactAt = s; return true }
    }
    return false
  }

  companion object {
    const val GRAVITY = 9.81f
    const val START_ANGLE = 0.035f
    const val START_SPEED = 0.09f
    const val AIR_DAMPING = 0.08f
    const val RESTITUTION = 0.20f
    const val SETTLE_SPEED = 0.35f
    const val MAX_BOUNCES = 2
    const val MAX_ANGLE = 1.95f
    const val MAX_SUBSTEP = 1f / 120f
    const val SAMPLES = 8
    const val CROWN_LIFT = 0.35f
  }
}

/** Where the logs of a fallen trunk end up: [count] pieces along the trunk line, lying on the ground. */
object LogLayout {
  class Piece(val cx: Float, val cy: Float, val cz: Float, val ax: Float, val ay: Float, val az: Float, val length: Float, val radius: Float)

  /** 3 logs for a trunk of 10 m or more, else 2. */
  fun count(trunkLength: Float): Int = if (trunkLength >= 10f) 3 else 2

  fun layout(fall: TreeFall, spec: PineSpec, scale: Float, ground: (Float, Float) -> Float): List<Piece> {
    val n = count(fall.length)
    val s0 = 0.25f
    val s1 = min(fall.length * 0.66f, fall.length - 1.2f)
    val seg = (s1 - s0) / n
    val sa = sin(fall.angle).coerceAtLeast(0.3f)
    val out = ArrayList<Piece>(n)
    for (i in 0 until n) {
      val a = s0 + seg * i + 0.06f
      val b = s0 + seg * (i + 1) - 0.06f
      val mid = (a + b) * 0.5f
      val r = spec.radiusAt(PineTreeGenerator.STUMP_TOP + mid / scale) * scale
      val xa = fall.pivotX + fall.dirX * a * sa; val za = fall.pivotZ + fall.dirZ * a * sa
      val xb = fall.pivotX + fall.dirX * b * sa; val zb = fall.pivotZ + fall.dirZ * b * sa
      val ya = ground(xa, za) + r * 0.85f; val yb = ground(xb, zb) + r * 0.85f
      var ax = xb - xa; var ay = yb - ya; var az = zb - za
      val len = sqrt(ax * ax + ay * ay + az * az)
      ax /= len; ay /= len; az /= len
      out.add(Piece((xa + xb) * 0.5f, (ya + yb) * 0.5f, (za + zb) * 0.5f, ax, ay, az, len, r))
    }
    return out
  }
}

/**
 * Chopping pines down with the hand tool.
 *
 *  - Every strike ([chop]) plays a dry wood impact (pitch varies per strike) and throws wood chips.
 *    A pine needs 4 or 5 strikes (fixed per tree).
 *  - On the last strike the stump stays in the chunk mesh ([WorldPopulator.fell]) and the trunk with
 *    its crown is rebuilt as a separate object that falls away from the player with [TreeFall]
 *    physics: creak when it goes, heavy thud (plus chips) when it hits the ground, a small bounce.
 *  - Once it rests it turns into 2-3 logs ([LogLayout]) that the player picks up ([collect]).
 *
 * Everything is drawn with [FoliageShader] (bark / cutout materials), so it shares the fog, the
 * day-night light and the flashlight. Cost: a few draw calls while something is falling or lying around.
 */
class TreeFellingSystem(
  private val populator: WorldPopulator,
  private val models: ScatterModels,
  private val cutoutMaterial: Material,
  private val ground: (Float, Float) -> Float,
  private val sounds: FellingSounds
) : RenderableProvider, Disposable {

  private class Falling(
    val tree: WorldPopulator.Tree, val fall: TreeFall,
    val meshes: List<Mesh>, val renderables: List<Renderable>
  ) {
    var restTime = 0f
  }

  private class Log(val piece: LogLayout.Piece, val mesh: Mesh, val renderable: Renderable)

  private val hits = HashMap<Long, Int>()
  private val falling = ArrayList<Falling>()
  private val logs = ArrayList<Log>()
  private val random = Random(97)
  private val tmp = Vector3()
  private val quat = Quaternion()

  /** Logs picked up so far. */
  var wood = 0
    private set

  val fallingCount: Int get() = falling.size
  val logCount: Int get() = logs.size

  // --- Chips ---------------------------------------------------------------------------------

  private val chipPos = FloatArray(MAX_CHIPS * 3)
  private val chipVel = FloatArray(MAX_CHIPS * 3)
  private val chipLife = FloatArray(MAX_CHIPS)
  private val chipSize = FloatArray(MAX_CHIPS)
  private var chipMesh: Mesh? = null
  private val chipRenderable = Renderable()
  private val chipVertices = FloatArray(MAX_CHIPS * 4 * ChunkScatterMesh.FLOATS)
  private var chipCount = 0

  /** Strikes needed for this tree: 4 or 5, stable per tree. */
  fun strikesNeeded(tree: WorldPopulator.Tree): Int = 4 + ((tree.placement.id xor (tree.placement.id ushr 29)).toInt() and 1)

  fun strikesTaken(tree: WorldPopulator.Tree): Int = hits[tree.placement.id] ?: 0

  /**
   * One strike on [tree] at [hit] by a player standing at ([playerX], [playerZ]). Returns true when
   * this strike felled it.
   */
  fun chop(tree: WorldPopulator.Tree, hit: Vector3, playerX: Float, playerZ: Float): Boolean {
    if (tree.placement.felled) return false
    val id = tree.placement.id
    val n = (hits[id] ?: 0) + 1
    hits[id] = n
    sounds.chop(hit.x, hit.y, hit.z, n)
    // Chips fly back towards the player
    var bx = playerX - tree.x; var bz = playerZ - tree.z
    val bl = sqrt(bx * bx + bz * bz).coerceAtLeast(1e-3f)
    bx /= bl; bz /= bl
    spawnChips(hit.x + bx * 0.05f, hit.y, hit.z + bz * 0.05f, bx, bz, 7)
    if (n < strikesNeeded(tree)) return false

    hits.remove(id)
    // Fall away from the player
    val dirX = -bx; val dirZ = -bz
    populator.fell(tree)
    val stumpTop = PineTreeGenerator.STUMP_TOP * tree.scale
    val fall = TreeFall(
      tree.x, tree.y + stumpTop, tree.z, dirX, dirZ,
      length = tree.height - stumpTop,
      radius = tree.trunkRadius,
      crownStart = (tree.spec.crownBase - PineTreeGenerator.STUMP_TOP) / (tree.spec.height - PineTreeGenerator.STUMP_TOP),
      ground = ground
    )
    falling.add(buildFalling(tree, fall))
    sounds.creak(tree.x, tree.y + 1.5f, tree.z)
    return true
  }

  private fun buildFalling(tree: WorldPopulator.Tree, fall: TreeFall): Falling {
    val built = ChunkScatterMesh.build(
      listOf(Placement(tree.placement.kind, 0f, 0f, 0f, tree.placement.yaw, tree.scale)),
      models, lod = false, fellableOnly = true, wind = false
    )
    val meshes = ArrayList<Mesh>(); val rends = ArrayList<Renderable>()
    for ((layer, data) in built.layers) {
      if (data == null) continue
      val mesh = toMesh(data)
      meshes.add(mesh)
      rends.add(Renderable().apply {
        material = if (layer == Layer.BARK) populator.barkMaterial else cutoutMaterial
        meshPart.set("falling", mesh, 0, data.indexCount, GL20.GL_TRIANGLES)
      })
    }
    return Falling(tree, fall, meshes, rends).also { updateTransform(it) }
  }

  private fun updateTransform(f: Falling) {
    val t = f.fall
    val stumpTop = t.pivotY - f.tree.y
    // pivot at the top of the stump: tip local +Y towards the fall direction
    for (r in f.renderables) {
      r.worldTransform.idt()
        .translate(t.pivotX, t.pivotY, t.pivotZ)
        .rotateRad(tmp.set(t.dirZ, 0f, -t.dirX), t.angle)
        .translate(0f, -stumpTop, 0f)
      r.worldTransform.translate(0f, 0f, 0f)
    }
  }

  /** Physics, chips and the fall -> logs conversion. [eye] is the camera (chips face it). */
  fun update(dt: Float, eye: Vector3) {
    val it = falling.iterator()
    while (it.hasNext()) {
      val f = it.next()
      when (f.fall.step(dt)) {
        TreeFall.Event.IMPACT -> if (f.fall.impacts == 1) {
          f.fall.pointAt(f.fall.contactAt, tmp)
          val strength = (f.fall.impactSpeed / 2.2f).coerceIn(0.25f, 1f)
          sounds.thud(tmp.x, tmp.y, tmp.z, strength)
          spawnChips(tmp.x, tmp.y + 0.3f, tmp.z, 0f, 0f, 10)
        }
        else -> Unit
      }
      updateTransform(f)
      if (f.fall.settled) {
        f.restTime += dt
        if (f.restTime > REST_BEFORE_LOGS) {
          for (piece in LogLayout.layout(f.fall, f.tree.spec, f.tree.scale, ground)) addLog(piece)
          f.fall.pointAt(f.fall.length * 0.4f, tmp)
          spawnChips(tmp.x, tmp.y + 0.2f, tmp.z, 0f, 0f, 12)
          f.meshes.forEach { m -> m.dispose() }
          it.remove()
        }
      }
    }
    updateChips(dt, eye)
  }

  private fun addLog(piece: LogLayout.Piece) {
    if (logs.size >= MAX_LOGS) {
      val old = logs.removeAt(0); old.mesh.dispose()
    }
    val data = ChunkScatterMesh.single(PineTreeGenerator.log(piece.length, piece.radius))
    val mesh = toMesh(data)
    val r = Renderable().apply {
      material = populator.barkMaterial
      meshPart.set("log", mesh, 0, data.indexCount, GL20.GL_TRIANGLES)
      quat.setFromCross(Vector3.Y, tmp.set(piece.ax, piece.ay, piece.az))
      worldTransform.idt().translate(piece.cx, piece.cy, piece.cz).rotate(quat)
    }
    logs.add(Log(piece, mesh, r))
  }

  /** Nearest log the ray points at within [maxDistance] (distance along the ray), or -1. */
  fun raycastLog(origin: Vector3, dir: Vector3, maxDistance: Float): Float {
    var best = -1f
    for (l in logs) {
      val t = rayToSegment(origin, dir, l.piece)
      if (t in 0f..maxDistance && (best < 0f || t < best)) best = t
    }
    return best
  }

  /** Picks up the log the ray points at. Returns true if one was collected. */
  fun collect(origin: Vector3, dir: Vector3, maxDistance: Float): Boolean {
    var bestI = -1; var bestT = maxDistance
    for ((i, l) in logs.withIndex()) {
      val t = rayToSegment(origin, dir, l.piece)
      if (t in 0f..bestT) { bestT = t; bestI = i }
    }
    if (bestI < 0) return false
    val l = logs.removeAt(bestI)
    l.mesh.dispose()
    wood++
    sounds.pickup(l.piece.cx, l.piece.cy, l.piece.cz)
    spawnChips(l.piece.cx, l.piece.cy + 0.1f, l.piece.cz, 0f, 0f, 5)
    return true
  }

  /** Ray parameter of the closest approach to the log's axis if within its radius (+ some slack), else -1. */
  private fun rayToSegment(o: Vector3, d: Vector3, p: LogLayout.Piece): Float {
    val h = p.length * 0.5f
    val ax = p.cx - p.ax * h; val ay = p.cy - p.ay * h; val az = p.cz - p.az * h
    val ux = p.ax * p.length; val uy = p.ay * p.length; val uz = p.az * p.length
    val wx = o.x - ax; val wy = o.y - ay; val wz = o.z - az
    val a = d.x * d.x + d.y * d.y + d.z * d.z
    val b = d.x * ux + d.y * uy + d.z * uz
    val c = ux * ux + uy * uy + uz * uz
    val dd = d.x * wx + d.y * wy + d.z * wz
    val e = ux * wx + uy * wy + uz * wz
    val den = a * c - b * b
    var s = if (den > 1e-6f) (b * e - c * dd) / den else 0f
    var t = if (den > 1e-6f) (a * e - b * dd) / den else e / c
    t = t.coerceIn(0f, 1f)
    s = ((t * b - dd) / a)
    if (s < 0f) return -1f
    val px = o.x + d.x * s - (ax + ux * t); val py = o.y + d.y * s - (ay + uy * t); val pz = o.z + d.z * s - (az + uz * t)
    val dist = sqrt(px * px + py * py + pz * pz)
    return if (dist <= p.radius + 0.25f) s else -1f
  }

  // --- chips ---------------------------------------------------------------------------------

  private fun spawnChips(x: Float, y: Float, z: Float, bx: Float, bz: Float, n: Int) {
    repeat(n) {
      val i = if (chipCount < MAX_CHIPS) chipCount++ else random.nextInt(MAX_CHIPS)
      chipPos[i * 3] = x; chipPos[i * 3 + 1] = y; chipPos[i * 3 + 2] = z
      val spread = 1.6f
      chipVel[i * 3] = bx * 2.2f + (random.nextFloat() - 0.5f) * spread * 2f
      chipVel[i * 3 + 1] = 1.2f + random.nextFloat() * 2.4f
      chipVel[i * 3 + 2] = bz * 2.2f + (random.nextFloat() - 0.5f) * spread * 2f
      chipLife[i] = 0.9f + random.nextFloat() * 0.8f
      chipSize[i] = 0.025f + random.nextFloat() * 0.035f
    }
  }

  private fun updateChips(dt: Float, eye: Vector3) {
    var i = 0
    while (i < chipCount) {
      chipLife[i] -= dt
      if (chipLife[i] <= 0f) {
        // swap-remove
        val last = chipCount - 1
        for (c in 0..2) { chipPos[i * 3 + c] = chipPos[last * 3 + c]; chipVel[i * 3 + c] = chipVel[last * 3 + c] }
        chipLife[i] = chipLife[last]; chipSize[i] = chipSize[last]
        chipCount--
        continue
      }
      chipVel[i * 3 + 1] -= TreeFall.GRAVITY * dt
      for (c in 0..2) chipPos[i * 3 + c] += chipVel[i * 3 + c] * dt
      val gy = ground(chipPos[i * 3], chipPos[i * 3 + 2])
      if (chipPos[i * 3 + 1] < gy + 0.02f) {
        chipPos[i * 3 + 1] = gy + 0.02f
        chipVel[i * 3 + 1] = abs(chipVel[i * 3 + 1]) * 0.25f
        chipVel[i * 3] *= 0.5f; chipVel[i * 3 + 2] *= 0.5f
      }
      i++
    }
    if (chipCount == 0) return
    // Camera-facing quads; cut-wood UVs (procedural rings in the bark shader)
    val F = ChunkScatterMesh.FLOATS
    for (k in 0 until chipCount) {
      val px = chipPos[k * 3]; val py = chipPos[k * 3 + 1]; val pz = chipPos[k * 3 + 2]
      var fx = eye.x - px; var fz = eye.z - pz
      val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-3f)
      fx /= fl; fz /= fl
      val rx = -fz; val rz = fx
      val s = chipSize[k] * min(1f, chipLife[k] * 3f)
      val tilt = (k % 3 - 1) * 0.4f
      for (c in 0 until 4) {
        val sx = if (c == 0 || c == 3) -1f else 1f
        val sy = if (c < 2) -1f else 1f
        val o = (k * 4 + c) * F
        chipVertices[o] = px + rx * s * sx
        chipVertices[o + 1] = py + s * sy + tilt * s * sx
        chipVertices[o + 2] = pz + rz * s * sx
        chipVertices[o + 3] = fx; chipVertices[o + 4] = 0.3f; chipVertices[o + 5] = fz
        chipVertices[o + 6] = PineTreeGenerator.CUT_U + sx * 0.45f; chipVertices[o + 7] = sy * 0.45f
        chipVertices[o + 8] = 0f; chipVertices[o + 9] = 0f; chipVertices[o + 10] = 1f
      }
    }
    val mesh = chipMesh ?: Mesh(false, MAX_CHIPS * 4, MAX_CHIPS * 6, *attributes()).also { m ->
      val idx = ShortArray(MAX_CHIPS * 6)
      for (q in 0 until MAX_CHIPS) {
        val b = q * 4; val o = q * 6
        idx[o] = b.toShort(); idx[o + 1] = (b + 1).toShort(); idx[o + 2] = (b + 2).toShort()
        idx[o + 3] = b.toShort(); idx[o + 4] = (b + 2).toShort(); idx[o + 5] = (b + 3).toShort()
      }
      m.setIndices(idx)
      chipMesh = m
      chipRenderable.material = populator.barkMaterial
    }
    mesh.setVertices(chipVertices, 0, chipCount * 4 * F)
    chipRenderable.meshPart.set("chips", mesh, 0, chipCount * 6, GL20.GL_TRIANGLES)
    chipRenderable.worldTransform.idt()
  }

  val chipsAlive: Int get() = chipCount

  // --- rendering ------------------------------------------------------------------------------

  override fun getRenderables(renderables: GdxArray<Renderable>, pool: Pool<Renderable>) {
    for (f in falling) for (r in f.renderables) renderables.add(r)
    for (l in logs) renderables.add(l.renderable)
    if (chipCount > 0 && chipMesh != null) renderables.add(chipRenderable)
  }

  fun render(batch: ModelBatch, environment: Environment, shader: Shader) {
    if (falling.isEmpty() && logs.isEmpty() && chipCount == 0) return
    batch.render(this, environment, shader)
  }

  private fun attributes() = arrayOf(
    VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0),
    VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_sway"),
    VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_near"),
    VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_ao")
  )

  private fun toMesh(data: LayerData): Mesh {
    val mesh = Mesh(true, data.vertexCount, data.indexCount, *attributes())
    mesh.setVertices(data.vertices, 0, data.vertexCount * ChunkScatterMesh.FLOATS)
    mesh.setIndices(data.indices, 0, data.indexCount)
    return mesh
  }

  override fun dispose() {
    for (f in falling) f.meshes.forEach { it.dispose() }
    falling.clear()
    for (l in logs) l.mesh.dispose()
    logs.clear()
    chipMesh?.dispose(); chipMesh = null
  }

  companion object {
    const val MAX_CHIPS = 64
    const val MAX_LOGS = 30
    const val REST_BEFORE_LOGS = 0.7f
  }
}

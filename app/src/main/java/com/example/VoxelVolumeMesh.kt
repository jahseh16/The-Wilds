package com.example

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.model.MeshPart
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class VoxelVolumeMesh(
  val cellsX: Int = 32,
  val cellsY: Int = 24,
  val cellsZ: Int = 32,
  val voxelSize: Float = 1.0f
) : Disposable {

  val minX: Float = 0.0f
  val maxX: Float = cellsX * voxelSize
  val minY: Float = -8.0f
  val maxY: Float = minY + cellsY * voxelSize
  val minZ: Float = 0.0f
  val maxZ: Float = cellsZ * voxelSize

  private val sizeX: Int = cellsX + 1
  private val sizeY: Int = cellsY + 1
  private val sizeZ: Int = cellsZ + 1

  // 3D scalar density field: > 0 is solid earth, <= 0 is air
  private val densityField = FloatArray(sizeX * sizeY * sizeZ)

  private val chunksX: Int = (cellsX + CHUNK_CELLS_X - 1) / CHUNK_CELLS_X
  private val chunksZ: Int = (cellsZ + CHUNK_CELLS_Z - 1) / CHUNK_CELLS_Z
  private val chunks: Array<TerrainChunk>

  private val terrainMaterial = Material(
    ColorAttribute.createDiffuse(Color(0.32f, 0.45f, 0.24f, 1f)),
    IntAttribute.createCullFace(GL20.GL_NONE)
  )

  private val aimModel: Model
  val aimIndicatorInstance: ModelInstance

  // Reusable scratch buffers for zero-allocation Marching Cubes & raycasting
  private val cornerDensities = FloatArray(8)
  private val cornerPositionsX = FloatArray(8)
  private val cornerPositionsY = FloatArray(8)
  private val cornerPositionsZ = FloatArray(8)
  private val cornerNormalsX = FloatArray(8)
  private val cornerNormalsY = FloatArray(8)
  private val cornerNormalsZ = FloatArray(8)
  private val edgeVertexIndices = ShortArray(12)

  private val scratchVertexBuffer = FloatArray(MAX_CHUNK_VERTICES * VERTEX_FLOATS)
  private val scratchIndexBuffer = ShortArray(MAX_CHUNK_INDICES)

  private val tempNormal = Vector3()
  private val tempPos = Vector3()
  private val tempQuat = Quaternion()

  private class TerrainChunk(
    val startCellX: Int,
    val endCellX: Int,
    val startCellZ: Int,
    val endCellZ: Int,
    val mesh: Mesh,
    val model: Model,
    val meshPart: MeshPart,
    val instance: ModelInstance,
    var indexCount: Int = 0
  ) : Disposable {
    override fun dispose() {
      model.dispose()
    }
  }

  init {
    initializeProceduralTerrain()

    val attributes = VertexAttributes(
      VertexAttribute.Position(),
      VertexAttribute.Normal()
    )
    val modelBuilder = ModelBuilder()
    val chunkList = ArrayList<TerrainChunk>(chunksX * chunksZ)

    for (cz in 0 until chunksZ) {
      val startCellZ = cz * CHUNK_CELLS_Z
      val endCellZ = (startCellZ + CHUNK_CELLS_Z).coerceAtMost(cellsZ)
      for (cx in 0 until chunksX) {
        val startCellX = cx * CHUNK_CELLS_X
        val endCellX = (startCellX + CHUNK_CELLS_X).coerceAtMost(cellsX)

        val mesh = Mesh(false, MAX_CHUNK_VERTICES, MAX_CHUNK_INDICES, attributes)
        modelBuilder.begin()
        modelBuilder.part(
          "chunk_${cx}_$cz",
          mesh,
          GL20.GL_TRIANGLES,
          0,
          0,
          terrainMaterial
        )
        val model = modelBuilder.end()
        val instance = ModelInstance(model)
        val instanceMeshPart = instance.nodes.first().parts.first().meshPart

        val chunk = TerrainChunk(
          startCellX = startCellX,
          endCellX = endCellX,
          startCellZ = startCellZ,
          endCellZ = endCellZ,
          mesh = mesh,
          model = model,
          meshPart = instanceMeshPart,
          instance = instance
        )
        rebuildChunk(chunk)
        chunkList.add(chunk)
      }
    }
    chunks = chunkList.toTypedArray()

    aimModel = createAimIndicatorModel()
    aimIndicatorInstance = ModelInstance(aimModel)
  }

  private fun gridIndex(gx: Int, gy: Int, gz: Int): Int =
    (gy * sizeZ + gz) * sizeX + gx

  private fun initializeProceduralTerrain() {
    for (gz in 0 until sizeZ) {
      val wz = minZ + gz * voxelSize
      for (gx in 0 until sizeX) {
        val wx = minX + gx * voxelSize
        val surfaceHeight = sampleProceduralHeight(wx, wz)
        for (gy in 0 until sizeY) {
          val wy = minY + gy * voxelSize
          // Positive inside earth (below surface), negative in air (above surface)
          densityField[gridIndex(gx, gy, gz)] = surfaceHeight - wy
        }
      }
    }
  }

  private fun sampleProceduralHeight(x: Float, z: Float): Float {
    val h1 = sin(x * 0.14f + 0.4f) * cos(z * 0.12f - 0.3f) * 2.2f
    val h2 = sin((x * 0.7f + z * 0.7f) * 0.22f) * 1.0f
    val h3 = cos((x * 0.6f - z * 0.8f) * 0.29f + 1.1f) * 0.55f
    return 1.2f + h1 + h2 + h3
  }

  private fun sampleGridDensity(gx: Int, gy: Int, gz: Int): Float {
    val cx = gx.coerceIn(0, cellsX)
    val cy = gy.coerceIn(0, cellsY)
    val cz = gz.coerceIn(0, cellsZ)
    return densityField[gridIndex(cx, cy, cz)]
  }

  private fun computeGridGradientNormal(gx: Int, gy: Int, gz: Int, outIndex: Int) {
    // Outward normal points from solid (>0) to air (<=0), i.e., -grad(density)
    val nx = sampleGridDensity(gx - 1, gy, gz) - sampleGridDensity(gx + 1, gy, gz)
    val ny = sampleGridDensity(gx, gy - 1, gz) - sampleGridDensity(gx, gy + 1, gz)
    val nz = sampleGridDensity(gx, gy, gz - 1) - sampleGridDensity(gx, gy, gz + 1)
    val lenSq = nx * nx + ny * ny + nz * nz
    if (lenSq > 1e-8f) {
      val invLen = 1f / sqrt(lenSq)
      cornerNormalsX[outIndex] = nx * invLen
      cornerNormalsY[outIndex] = ny * invLen
      cornerNormalsZ[outIndex] = nz * invLen
    } else {
      cornerNormalsX[outIndex] = 0f
      cornerNormalsY[outIndex] = 1f
      cornerNormalsZ[outIndex] = 0f
    }
  }

  private fun rebuildChunk(chunk: TerrainChunk) {
    var vertCount = 0
    var idxCount = 0

    for (gy in 0 until cellsY) {
      val wy0 = minY + gy * voxelSize
      val wy1 = wy0 + voxelSize

      for (gz in chunk.startCellZ until chunk.endCellZ) {
        val wz0 = minZ + gz * voxelSize
        val wz1 = wz0 + voxelSize

        for (gx in chunk.startCellX until chunk.endCellX) {
          val wx0 = minX + gx * voxelSize
          val wx1 = wx0 + voxelSize

          // Paul Bourke cube corner layout:
          // 0:(0,0,0), 1:(1,0,0), 2:(1,0,1), 3:(0,0,1),
          // 4:(0,1,0), 5:(1,1,0), 6:(1,1,1), 7:(0,1,1)
          cornerDensities[0] = densityField[gridIndex(gx, gy, gz)]
          cornerDensities[1] = densityField[gridIndex(gx + 1, gy, gz)]
          cornerDensities[2] = densityField[gridIndex(gx + 1, gy, gz + 1)]
          cornerDensities[3] = densityField[gridIndex(gx, gy, gz + 1)]
          cornerDensities[4] = densityField[gridIndex(gx, gy + 1, gz)]
          cornerDensities[5] = densityField[gridIndex(gx + 1, gy + 1, gz)]
          cornerDensities[6] = densityField[gridIndex(gx + 1, gy + 1, gz + 1)]
          cornerDensities[7] = densityField[gridIndex(gx, gy + 1, gz + 1)]

          var cubeIndex = 0
          if (cornerDensities[0] > ISO_LEVEL) cubeIndex = cubeIndex or 1
          if (cornerDensities[1] > ISO_LEVEL) cubeIndex = cubeIndex or 2
          if (cornerDensities[2] > ISO_LEVEL) cubeIndex = cubeIndex or 4
          if (cornerDensities[3] > ISO_LEVEL) cubeIndex = cubeIndex or 8
          if (cornerDensities[4] > ISO_LEVEL) cubeIndex = cubeIndex or 16
          if (cornerDensities[5] > ISO_LEVEL) cubeIndex = cubeIndex or 32
          if (cornerDensities[6] > ISO_LEVEL) cubeIndex = cubeIndex or 64
          if (cornerDensities[7] > ISO_LEVEL) cubeIndex = cubeIndex or 128

          val edgeMask = EDGE_TABLE[cubeIndex]
          if (edgeMask == 0) continue

          // Stop cleanly if chunk approaches buffer capacity
          if (vertCount + 12 >= MAX_CHUNK_VERTICES || idxCount + 15 >= MAX_CHUNK_INDICES) {
            break
          }

          cornerPositionsX[0] = wx0; cornerPositionsY[0] = wy0; cornerPositionsZ[0] = wz0
          cornerPositionsX[1] = wx1; cornerPositionsY[1] = wy0; cornerPositionsZ[1] = wz0
          cornerPositionsX[2] = wx1; cornerPositionsY[2] = wy0; cornerPositionsZ[2] = wz1
          cornerPositionsX[3] = wx0; cornerPositionsY[3] = wy0; cornerPositionsZ[3] = wz1
          cornerPositionsX[4] = wx0; cornerPositionsY[4] = wy1; cornerPositionsZ[4] = wz0
          cornerPositionsX[5] = wx1; cornerPositionsY[5] = wy1; cornerPositionsZ[5] = wz0
          cornerPositionsX[6] = wx1; cornerPositionsY[6] = wy1; cornerPositionsZ[6] = wz1
          cornerPositionsX[7] = wx0; cornerPositionsY[7] = wy1; cornerPositionsZ[7] = wz1

          computeGridGradientNormal(gx, gy, gz, 0)
          computeGridGradientNormal(gx + 1, gy, gz, 1)
          computeGridGradientNormal(gx + 1, gy, gz + 1, 2)
          computeGridGradientNormal(gx, gy, gz + 1, 3)
          computeGridGradientNormal(gx, gy + 1, gz, 4)
          computeGridGradientNormal(gx + 1, gy + 1, gz, 5)
          computeGridGradientNormal(gx + 1, gy + 1, gz + 1, 6)
          computeGridGradientNormal(gx, gy + 1, gz + 1, 7)

          for (edge in 0 until 12) {
            if ((edgeMask and (1 shl edge)) != 0) {
              val c0 = EDGE_CORNER_A[edge]
              val c1 = EDGE_CORNER_B[edge]
              val d0 = cornerDensities[c0]
              val d1 = cornerDensities[c1]
              val denom = d1 - d0
              val t = if (kotlin.math.abs(denom) > 1e-6f) {
                ((ISO_LEVEL - d0) / denom).coerceIn(0f, 1f)
              } else {
                0.5f
              }

              val px = cornerPositionsX[c0] + t * (cornerPositionsX[c1] - cornerPositionsX[c0])
              val py = cornerPositionsY[c0] + t * (cornerPositionsY[c1] - cornerPositionsY[c0])
              val pz = cornerPositionsZ[c0] + t * (cornerPositionsZ[c1] - cornerPositionsZ[c0])

              var nx = cornerNormalsX[c0] + t * (cornerNormalsX[c1] - cornerNormalsX[c0])
              var ny = cornerNormalsY[c0] + t * (cornerNormalsY[c1] - cornerNormalsY[c0])
              var nz = cornerNormalsZ[c0] + t * (cornerNormalsZ[c1] - cornerNormalsZ[c0])
              val nLenSq = nx * nx + ny * ny + nz * nz
              if (nLenSq > 1e-8f) {
                val inv = 1f / sqrt(nLenSq)
                nx *= inv
                ny *= inv
                nz *= inv
              } else {
                nx = 0f; ny = 1f; nz = 0f
              }

              val base = vertCount * VERTEX_FLOATS
              scratchVertexBuffer[base] = px
              scratchVertexBuffer[base + 1] = py
              scratchVertexBuffer[base + 2] = pz
              scratchVertexBuffer[base + 3] = nx
              scratchVertexBuffer[base + 4] = ny
              scratchVertexBuffer[base + 5] = nz

              edgeVertexIndices[edge] = vertCount.toShort()
              vertCount++
            }
          }

          val triRow = TRI_TABLE[cubeIndex]
          var i = 0
          while (triRow[i] != -1) {
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i]]
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i + 1]]
            scratchIndexBuffer[idxCount++] = edgeVertexIndices[triRow[i + 2]]
            i += 3
          }
        }
      }
    }

    chunk.indexCount = idxCount
    if (idxCount > 0 && vertCount > 0) {
      chunk.mesh.setVertices(scratchVertexBuffer, 0, vertCount * VERTEX_FLOATS)
      chunk.mesh.setIndices(scratchIndexBuffer, 0, idxCount)
      chunk.meshPart.offset = 0
      chunk.meshPart.size = idxCount
    } else {
      chunk.meshPart.offset = 0
      chunk.meshPart.size = 0
    }
  }

  /**
   * Subtracts density inside a 3D sphere around [center] to carve caves and horizontal tunnels,
   * rebuilding only the affected volume chunks.
   */
  fun excavateSphere(center: Vector3, radius: Float, strength: Float) {
    if (radius <= 0f || strength <= 0f) return

    // Leave gy=0 intact as bedrock floor and keep outer boundary sealed
    val minGx = floor((center.x - radius - minX) / voxelSize).toInt().coerceIn(1, cellsX - 1)
    val maxGx = ceil((center.x + radius - minX) / voxelSize).toInt().coerceIn(1, cellsX - 1)
    val minGy = floor((center.y - radius - minY) / voxelSize).toInt().coerceIn(1, cellsY - 1)
    val maxGy = ceil((center.y + radius - minY) / voxelSize).toInt().coerceIn(1, cellsY - 1)
    val minGz = floor((center.z - radius - minZ) / voxelSize).toInt().coerceIn(1, cellsZ - 1)
    val maxGz = ceil((center.z + radius - minZ) / voxelSize).toInt().coerceIn(1, cellsZ - 1)

    val radiusSq = radius * radius
    val piFloat = PI.toFloat()
    var modified = false

    for (gy in minGy..maxGy) {
      val wy = minY + gy * voxelSize
      val dy = wy - center.y
      val dySq = dy * dy

      for (gz in minGz..maxGz) {
        val wz = minZ + gz * voxelSize
        val dz = wz - center.z
        val dyzSq = dySq + dz * dz

        for (gx in minGx..maxGx) {
          val wx = minX + gx * voxelSize
          val dx = wx - center.x
          val distSq = dx * dx + dyzSq

          if (distSq < radiusSq) {
            val dist = sqrt(distSq)
            val falloff = 0.5f * (1f + cos(piFloat * (dist / radius)))
            val idx = gridIndex(gx, gy, gz)
            densityField[idx] = (densityField[idx] - strength * falloff).coerceAtLeast(-MAX_DENSITY)
            modified = true
          }
        }
      }
    }

    if (!modified) return

    // Expand dirty cell bounds by 1 voxel for shared central-difference normals
    val dirtyMinCellX = (minGx - 2).coerceAtLeast(0)
    val dirtyMaxCellX = (maxGx + 1).coerceAtMost(cellsX - 1)
    val dirtyMinCellZ = (minGz - 2).coerceAtLeast(0)
    val dirtyMaxCellZ = (maxGz + 1).coerceAtMost(cellsZ - 1)

    val minCx = (dirtyMinCellX / CHUNK_CELLS_X).coerceIn(0, chunksX - 1)
    val maxCx = (dirtyMaxCellX / CHUNK_CELLS_X).coerceIn(0, chunksX - 1)
    val minCz = (dirtyMinCellZ / CHUNK_CELLS_Z).coerceIn(0, chunksZ - 1)
    val maxCz = (dirtyMaxCellZ / CHUNK_CELLS_Z).coerceIn(0, chunksZ - 1)

    for (cz in minCz..maxCz) {
      for (cx in minCx..maxCx) {
        rebuildChunk(chunks[cz * chunksX + cx])
      }
    }
  }

  /**
   * Trilinear O(1) density interpolation at world coordinates (x, y, z).
   * Returns > 0 for solid ground, <= 0 for air.
   */
  fun getDensityAt(x: Float, y: Float, z: Float): Float {
    val lx = ((x - minX) / voxelSize).coerceIn(0f, cellsX - 0.0001f)
    val ly = ((y - minY) / voxelSize).coerceIn(0f, cellsY - 0.0001f)
    val lz = ((z - minZ) / voxelSize).coerceIn(0f, cellsZ - 0.0001f)

    val gx = lx.toInt()
    val gy = ly.toInt()
    val gz = lz.toInt()

    val tx = lx - gx
    val ty = ly - gy
    val tz = lz - gz

    val d000 = densityField[gridIndex(gx, gy, gz)]
    val d100 = densityField[gridIndex(gx + 1, gy, gz)]
    val d010 = densityField[gridIndex(gx, gy + 1, gz)]
    val d110 = densityField[gridIndex(gx + 1, gy + 1, gz)]
    val d001 = densityField[gridIndex(gx, gy, gz + 1)]
    val d101 = densityField[gridIndex(gx + 1, gy, gz + 1)]
    val d011 = densityField[gridIndex(gx, gy + 1, gz + 1)]
    val d111 = densityField[gridIndex(gx + 1, gy + 1, gz + 1)]

    val c00 = d000 + tx * (d100 - d000)
    val c10 = d010 + tx * (d110 - d010)
    val c01 = d001 + tx * (d101 - d001)
    val c11 = d011 + tx * (d111 - d011)

    val c0 = c00 + ty * (c10 - c00)
    val c1 = c01 + ty * (c11 - c01)

    return c0 + tz * (c1 - c0)
  }

  /**
   * Finds the highest air-to-solid surface Y at (x, z) and adds +2.0m clearance
   * so the player always spawns safely above the terrain.
   */
  fun findSpawnY(x: Float, z: Float): Float {
    var prevY = maxY - 0.1f
    var y = prevY - 0.25f
    while (y >= minY) {
      if (getDensityAt(x, y, z) > ISO_LEVEL) {
        var low = y
        var high = prevY
        repeat(6) {
          val mid = (low + high) * 0.5f
          if (getDensityAt(x, mid, z) > ISO_LEVEL) low = mid else high = mid
        }
        return high + 2.0f
      }
      prevY = y
      y -= 0.25f
    }
    return 4.0f
  }

  /**
   * 3D volumetric raycast against the 0.0f isosurface up to [maxDistance] meters.
   * Uses a coarse 0.18m step followed by 6-step binary search refinement on impact.
   */
  fun raycast(
    origin: Vector3,
    direction: Vector3,
    maxDistance: Float = 3.5f,
    outHit: Vector3
  ): Boolean {
    val step = 0.18f
    var prevT = 0f
    var t = step

    while (t <= maxDistance + 0.0001f) {
      val clampedT = t.coerceAtMost(maxDistance)
      val px = origin.x + direction.x * clampedT
      val py = origin.y + direction.y * clampedT
      val pz = origin.z + direction.z * clampedT

      if (px in minX..maxX && py in minY..maxY && pz in minZ..maxZ) {
        if (getDensityAt(px, py, pz) > ISO_LEVEL) {
          var low = prevT
          var high = clampedT
          repeat(6) {
            val mid = (low + high) * 0.5f
            val mx = origin.x + direction.x * mid
            val my = origin.y + direction.y * mid
            val mz = origin.z + direction.z * mid
            if (getDensityAt(mx, my, mz) > ISO_LEVEL) {
              high = mid
            } else {
              low = mid
            }
          }
          val hitT = (low + high) * 0.5f
          outHit.set(
            origin.x + direction.x * hitT,
            origin.y + direction.y * hitT,
            origin.z + direction.z * hitT
          )
          return true
        }
      }

      prevT = clampedT
      t += step
    }

    return false
  }

  fun updateAimIndicator(hitPoint: Vector3) {
    getSurfaceNormalAt(hitPoint.x, hitPoint.y, hitPoint.z, tempNormal)
    tempQuat.setFromCross(Vector3.Y, tempNormal)
    tempPos.set(
      hitPoint.x + tempNormal.x * 0.05f,
      hitPoint.y + tempNormal.y * 0.05f,
      hitPoint.z + tempNormal.z * 0.05f
    )
    aimIndicatorInstance.transform.set(tempPos, tempQuat)
  }

  private fun getSurfaceNormalAt(x: Float, y: Float, z: Float, out: Vector3) {
    val eps = 0.25f
    val nx = getDensityAt(x - eps, y, z) - getDensityAt(x + eps, y, z)
    val ny = getDensityAt(x, y - eps, z) - getDensityAt(x, y + eps, z)
    val nz = getDensityAt(x, y, z - eps) - getDensityAt(x, y, z + eps)
    out.set(nx, ny, nz)
    if (out.len2() > 1e-8f) {
      out.nor()
    } else {
      out.set(Vector3.Y)
    }
  }

  val chunkCount: Int
    get() = chunks.size

  fun getVisibleChunkInstance(index: Int): ModelInstance? {
    val chunk = chunks[index]
    return if (chunk.indexCount > 0) chunk.instance else null
  }

  private fun createAimIndicatorModel(): Model {
    val attributes = VertexAttributes(VertexAttribute.Position())
    val meshBuilder = MeshBuilder()
    meshBuilder.begin(attributes, GL20.GL_TRIANGLES)

    val segments = 32
    val innerRadius = 0.20f
    val outerRadius = 0.26f
    val twoPi = (PI * 2.0).toFloat()

    for (i in 0 until segments) {
      val a0 = twoPi * i / segments
      val a1 = twoPi * (i + 1) / segments
      val cos0 = cos(a0)
      val sin0 = sin(a0)
      val cos1 = cos(a1)
      val sin1 = sin(a1)

      val vIn0 = meshBuilder.vertex(Vector3(cos0 * innerRadius, 0f, sin0 * innerRadius), null, null, null)
      val vOut0 = meshBuilder.vertex(Vector3(cos0 * outerRadius, 0f, sin0 * outerRadius), null, null, null)
      val vIn1 = meshBuilder.vertex(Vector3(cos1 * innerRadius, 0f, sin1 * innerRadius), null, null, null)
      val vOut1 = meshBuilder.vertex(Vector3(cos1 * outerRadius, 0f, sin1 * outerRadius), null, null, null)

      meshBuilder.triangle(vIn0, vIn1, vOut0)
      meshBuilder.triangle(vOut0, vIn1, vOut1)
    }

    val dotRadius = 0.045f
    val centerVertex = meshBuilder.vertex(Vector3(0f, 0f, 0f), null, null, null)
    val dotSegments = 12
    for (i in 0 until dotSegments) {
      val a0 = twoPi * i / dotSegments
      val a1 = twoPi * (i + 1) / dotSegments
      val v0 = meshBuilder.vertex(Vector3(cos(a0) * dotRadius, 0f, sin(a0) * dotRadius), null, null, null)
      val v1 = meshBuilder.vertex(Vector3(cos(a1) * dotRadius, 0f, sin(a1) * dotRadius), null, null, null)
      meshBuilder.triangle(centerVertex, v1, v0)
    }

    val ringMesh = meshBuilder.end()
    val material = Material(
      ColorAttribute.createDiffuse(Color(0.98f, 0.86f, 0.30f, 1f)),
      IntAttribute.createCullFace(GL20.GL_NONE)
    )

    val modelBuilder = ModelBuilder()
    modelBuilder.begin()
    modelBuilder.part("aim_ring", ringMesh, GL20.GL_TRIANGLES, 0, ringMesh.numIndices, material)
    return modelBuilder.end()
  }

  override fun dispose() {
    for (chunk in chunks) {
      chunk.dispose()
    }
    aimModel.dispose()
  }

  companion object {
    const val ISO_LEVEL = 0.0f
    private const val CHUNK_CELLS_X = 8
    private const val CHUNK_CELLS_Z = 8
    private const val VERTEX_FLOATS = 6 // position (3) + normal (3)
    private const val MAX_CHUNK_VERTICES = 24000
    private const val MAX_CHUNK_INDICES = 24000
    private const val MAX_DENSITY = 12.0f

    private val EDGE_CORNER_A = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3)
    private val EDGE_CORNER_B = intArrayOf(1, 2, 3, 0, 5, 6, 7, 4, 4, 5, 6, 7)

    // Standard Paul Bourke Marching Cubes edge bitmask lookup table (256 cases)
    val EDGE_TABLE = intArrayOf(
      0x000, 0x109, 0x203, 0x30a, 0x406, 0x50f, 0x605, 0x70c,
      0x80c, 0x905, 0xa0f, 0xb06, 0xc0a, 0xd03, 0xe09, 0xf00,
      0x190, 0x099, 0x393, 0x29a, 0x596, 0x49f, 0x795, 0x69c,
      0x99c, 0x895, 0xb9f, 0xa96, 0xd9a, 0xc93, 0xf99, 0xe90,
      0x230, 0x339, 0x033, 0x13a, 0x636, 0x73f, 0x435, 0x53c,
      0xa3c, 0xb35, 0x83f, 0x936, 0xe3a, 0xf33, 0xc39, 0xd30,
      0x3a0, 0x2a9, 0x1a3, 0x0aa, 0x7a6, 0x6af, 0x5a5, 0x4ac,
      0xbac, 0xaa5, 0x9af, 0x8a6, 0xfaa, 0xea3, 0xda9, 0xca0,
      0x460, 0x569, 0x663, 0x76a, 0x066, 0x16f, 0x265, 0x36c,
      0xc6c, 0xd65, 0xe6f, 0xf66, 0x86a, 0x963, 0xa69, 0xb60,
      0x5f0, 0x4f9, 0x7f3, 0x6fa, 0x1f6, 0x0ff, 0x3f5, 0x2fc,
      0xdfc, 0xcf5, 0xfff, 0xef6, 0x9fa, 0x8f3, 0xbf9, 0xaf0,
      0x650, 0x759, 0x453, 0x55a, 0x256, 0x35f, 0x055, 0x15c,
      0xe5c, 0xf55, 0xc5f, 0xd56, 0xa5a, 0xb53, 0x859, 0x950,
      0x7c0, 0x6c9, 0x5c3, 0x4ca, 0x3c6, 0x2cf, 0x1c5, 0x0cc,
      0xfcc, 0xec5, 0xdcf, 0xcc6, 0xbca, 0xac3, 0x9c9, 0x8c0,
      0x8c0, 0x9c9, 0xac3, 0xbca, 0xcc6, 0xdcf, 0xec5, 0xfcc,
      0x0cc, 0x1c5, 0x2cf, 0x3c6, 0x4ca, 0x5c3, 0x6c9, 0x7c0,
      0x950, 0x859, 0xb53, 0xa5a, 0xd56, 0xc5f, 0xf55, 0xe5c,
      0x15c, 0x055, 0x35f, 0x256, 0x55a, 0x453, 0x759, 0x650,
      0xaf0, 0xbf9, 0x8f3, 0x9fa, 0xef6, 0xfff, 0xcf5, 0xdfc,
      0x2fc, 0x3f5, 0x0ff, 0x1f6, 0x6fa, 0x7f3, 0x4f9, 0x5f0,
      0xb60, 0xa69, 0x963, 0x86a, 0xf66, 0xe6f, 0xd65, 0xc6c,
      0x36c, 0x265, 0x16f, 0x066, 0x76a, 0x663, 0x569, 0x460,
      0xca0, 0xda9, 0xea3, 0xfaa, 0x8a6, 0x9af, 0xaa5, 0xbac,
      0x4ac, 0x5a5, 0x6af, 0x7a6, 0x0aa, 0x1a3, 0x2a9, 0x3a0,
      0xd30, 0xc39, 0xf33, 0xe3a, 0x936, 0x83f, 0xb35, 0xa3c,
      0x53c, 0x435, 0x73f, 0x636, 0x13a, 0x033, 0x339, 0x230,
      0xe90, 0xf99, 0xc93, 0xd9a, 0xa96, 0xb9f, 0x895, 0x99c,
      0x69c, 0x795, 0x49f, 0x596, 0x29a, 0x393, 0x099, 0x190,
      0xf00, 0xe09, 0xd03, 0xc0a, 0xb06, 0xa0f, 0x905, 0x80c,
      0x70c, 0x605, 0x50f, 0x406, 0x30a, 0x203, 0x109, 0x000
    )

    // Standard Paul Bourke Marching Cubes triangle table (256 cases x 16 ints, -1 terminated)
    val TRI_TABLE = arrayOf(
      intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 8, 3, 9, 8, 1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, 1, 2, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 2, 10, 0, 2, 9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 8, 3, 2, 10, 8, 10, 9, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 11, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 11, 2, 8, 11, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 9, 0, 2, 3, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 11, 2, 1, 9, 11, 9, 8, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 10, 1, 11, 10, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 10, 1, 0, 8, 10, 8, 11, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 9, 0, 3, 11, 9, 11, 10, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 8, 10, 10, 8, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 7, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 3, 0, 7, 3, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, 8, 4, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 1, 9, 4, 7, 1, 7, 3, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, 8, 4, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 4, 7, 3, 0, 4, 1, 2, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 2, 10, 9, 0, 2, 8, 4, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 10, 9, 2, 9, 7, 2, 7, 3, 7, 9, 4, -1, -1, -1, -1),
      intArrayOf(8, 4, 7, 3, 11, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 4, 7, 11, 2, 4, 2, 0, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 0, 1, 8, 4, 7, 2, 3, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 7, 11, 9, 4, 11, 9, 11, 2, 9, 2, 1, -1, -1, -1, -1),
      intArrayOf(3, 10, 1, 3, 11, 10, 7, 8, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 11, 10, 1, 4, 11, 1, 0, 4, 7, 11, 4, -1, -1, -1, -1),
      intArrayOf(4, 7, 8, 9, 0, 11, 9, 11, 10, 11, 0, 3, -1, -1, -1, -1),
      intArrayOf(4, 7, 11, 4, 11, 9, 9, 11, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 5, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 5, 4, 0, 8, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 5, 4, 1, 5, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 5, 4, 8, 3, 5, 3, 1, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, 9, 5, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 0, 8, 1, 2, 10, 4, 9, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 2, 10, 5, 4, 2, 4, 0, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 10, 5, 3, 2, 5, 3, 5, 4, 3, 4, 8, -1, -1, -1, -1),
      intArrayOf(9, 5, 4, 2, 3, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 11, 2, 0, 8, 11, 4, 9, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 5, 4, 0, 1, 5, 2, 3, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 1, 5, 2, 5, 8, 2, 8, 11, 4, 8, 5, -1, -1, -1, -1),
      intArrayOf(10, 3, 11, 10, 1, 3, 9, 5, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 9, 5, 0, 8, 1, 8, 10, 1, 8, 11, 10, -1, -1, -1, -1),
      intArrayOf(5, 4, 0, 5, 0, 11, 5, 11, 10, 11, 0, 3, -1, -1, -1, -1),
      intArrayOf(5, 4, 8, 5, 8, 10, 10, 8, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 7, 8, 5, 7, 9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 3, 0, 9, 5, 3, 5, 7, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 7, 8, 0, 1, 7, 1, 5, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 5, 3, 3, 5, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 7, 8, 9, 5, 7, 10, 1, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 1, 2, 9, 5, 0, 5, 3, 0, 5, 7, 3, -1, -1, -1, -1),
      intArrayOf(8, 0, 2, 8, 2, 5, 8, 5, 7, 10, 5, 2, -1, -1, -1, -1),
      intArrayOf(2, 10, 5, 2, 5, 3, 3, 5, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 9, 5, 7, 8, 9, 3, 11, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 5, 7, 9, 7, 2, 9, 2, 0, 2, 7, 11, -1, -1, -1, -1),
      intArrayOf(2, 3, 11, 0, 1, 8, 1, 7, 8, 1, 5, 7, -1, -1, -1, -1),
      intArrayOf(11, 2, 1, 11, 1, 7, 7, 1, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 5, 8, 8, 5, 7, 10, 1, 3, 10, 3, 11, -1, -1, -1, -1),
      intArrayOf(5, 7, 0, 5, 0, 9, 7, 11, 0, 1, 0, 10, 11, 10, 0, -1),
      intArrayOf(11, 10, 0, 11, 0, 3, 10, 5, 0, 8, 0, 7, 5, 7, 0, -1),
      intArrayOf(11, 10, 5, 7, 11, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 6, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, 5, 10, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 0, 1, 5, 10, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 8, 3, 1, 9, 8, 5, 10, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 6, 5, 2, 6, 1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 6, 5, 1, 2, 6, 3, 0, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 6, 5, 9, 0, 6, 0, 2, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 9, 8, 5, 8, 2, 5, 2, 6, 3, 2, 8, -1, -1, -1, -1),
      intArrayOf(2, 3, 11, 10, 6, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 0, 8, 11, 2, 0, 10, 6, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, 2, 3, 11, 5, 10, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 10, 6, 1, 9, 2, 9, 11, 2, 9, 8, 11, -1, -1, -1, -1),
      intArrayOf(6, 3, 11, 6, 5, 3, 5, 1, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 11, 0, 11, 5, 0, 5, 1, 5, 11, 6, -1, -1, -1, -1),
      intArrayOf(3, 11, 6, 0, 3, 6, 0, 6, 5, 0, 5, 9, -1, -1, -1, -1),
      intArrayOf(6, 5, 9, 6, 9, 11, 11, 9, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 10, 6, 4, 7, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 3, 0, 4, 7, 3, 6, 5, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 9, 0, 5, 10, 6, 8, 4, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 6, 5, 1, 9, 7, 1, 7, 3, 7, 9, 4, -1, -1, -1, -1),
      intArrayOf(6, 1, 2, 6, 5, 1, 4, 7, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 5, 5, 2, 6, 3, 0, 4, 3, 4, 7, -1, -1, -1, -1),
      intArrayOf(8, 4, 7, 9, 0, 5, 0, 6, 5, 0, 2, 6, -1, -1, -1, -1),
      intArrayOf(7, 3, 9, 7, 9, 4, 3, 2, 9, 5, 9, 6, 2, 6, 9, -1),
      intArrayOf(3, 11, 2, 7, 8, 4, 10, 6, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 10, 6, 4, 7, 2, 4, 2, 0, 2, 7, 11, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, 4, 7, 8, 2, 3, 11, 5, 10, 6, -1, -1, -1, -1),
      intArrayOf(9, 2, 1, 9, 11, 2, 9, 4, 11, 7, 11, 4, 5, 10, 6, -1),
      intArrayOf(8, 4, 7, 3, 11, 5, 3, 5, 1, 5, 11, 6, -1, -1, -1, -1),
      intArrayOf(5, 1, 11, 5, 11, 6, 1, 0, 11, 7, 11, 4, 0, 4, 11, -1),
      intArrayOf(0, 5, 9, 0, 6, 5, 0, 3, 6, 11, 6, 3, 8, 4, 7, -1),
      intArrayOf(6, 5, 9, 6, 9, 11, 4, 7, 9, 7, 11, 9, -1, -1, -1, -1),
      intArrayOf(10, 4, 9, 6, 4, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 10, 6, 4, 9, 10, 0, 8, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 0, 1, 10, 6, 0, 6, 4, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 3, 1, 8, 1, 6, 8, 6, 4, 6, 1, 10, -1, -1, -1, -1),
      intArrayOf(1, 4, 9, 1, 2, 4, 2, 6, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 0, 8, 1, 2, 9, 2, 4, 9, 2, 6, 4, -1, -1, -1, -1),
      intArrayOf(0, 2, 4, 4, 2, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 3, 2, 8, 2, 4, 4, 2, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 4, 9, 10, 6, 4, 11, 2, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 2, 2, 8, 11, 4, 9, 10, 4, 10, 6, -1, -1, -1, -1),
      intArrayOf(3, 11, 2, 0, 1, 6, 0, 6, 4, 6, 1, 10, -1, -1, -1, -1),
      intArrayOf(6, 4, 1, 6, 1, 10, 4, 8, 1, 2, 1, 11, 8, 11, 1, -1),
      intArrayOf(9, 6, 4, 9, 3, 6, 9, 1, 3, 11, 6, 3, -1, -1, -1, -1),
      intArrayOf(8, 11, 1, 8, 1, 0, 11, 6, 1, 9, 1, 4, 6, 4, 1, -1),
      intArrayOf(3, 11, 6, 3, 6, 0, 0, 6, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(6, 4, 8, 11, 6, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 10, 6, 7, 8, 10, 8, 9, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 7, 3, 0, 10, 7, 0, 9, 10, 6, 7, 10, -1, -1, -1, -1),
      intArrayOf(10, 6, 7, 1, 10, 7, 1, 7, 8, 1, 8, 0, -1, -1, -1, -1),
      intArrayOf(10, 6, 7, 10, 7, 1, 1, 7, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 6, 1, 6, 8, 1, 8, 9, 8, 6, 7, -1, -1, -1, -1),
      intArrayOf(2, 6, 9, 2, 9, 1, 6, 7, 9, 0, 9, 3, 7, 3, 9, -1),
      intArrayOf(7, 8, 0, 7, 0, 6, 6, 0, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 3, 2, 6, 7, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 3, 11, 10, 6, 8, 10, 8, 9, 8, 6, 7, -1, -1, -1, -1),
      intArrayOf(2, 0, 7, 2, 7, 11, 0, 9, 7, 6, 7, 10, 9, 10, 7, -1),
      intArrayOf(1, 8, 0, 1, 7, 8, 1, 10, 7, 6, 7, 10, 2, 3, 11, -1),
      intArrayOf(11, 2, 1, 11, 1, 7, 10, 6, 1, 6, 7, 1, -1, -1, -1, -1),
      intArrayOf(8, 9, 6, 8, 6, 7, 9, 1, 6, 11, 6, 3, 1, 3, 6, -1),
      intArrayOf(0, 9, 1, 11, 6, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 8, 0, 7, 0, 6, 3, 11, 0, 11, 6, 0, -1, -1, -1, -1),
      intArrayOf(7, 11, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 6, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 0, 8, 11, 7, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, 11, 7, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 1, 9, 8, 3, 1, 11, 7, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 1, 2, 6, 11, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, 3, 0, 8, 6, 11, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 9, 0, 2, 10, 9, 6, 11, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(6, 11, 7, 2, 10, 3, 10, 8, 3, 10, 9, 8, -1, -1, -1, -1),
      intArrayOf(7, 2, 3, 6, 2, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(7, 0, 8, 7, 6, 0, 6, 2, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 7, 6, 2, 3, 7, 0, 1, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 6, 2, 1, 8, 6, 1, 9, 8, 8, 7, 6, -1, -1, -1, -1),
      intArrayOf(10, 7, 6, 10, 1, 7, 1, 3, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 7, 6, 1, 7, 10, 1, 8, 7, 1, 0, 8, -1, -1, -1, -1),
      intArrayOf(0, 3, 7, 0, 7, 10, 0, 10, 9, 6, 10, 7, -1, -1, -1, -1),
      intArrayOf(7, 6, 10, 7, 10, 8, 8, 10, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(6, 8, 4, 11, 8, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 6, 11, 3, 0, 6, 0, 4, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 6, 11, 8, 4, 6, 9, 0, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 4, 6, 9, 6, 3, 9, 3, 1, 11, 3, 6, -1, -1, -1, -1),
      intArrayOf(6, 8, 4, 6, 11, 8, 2, 10, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, 3, 0, 11, 0, 6, 11, 0, 4, 6, -1, -1, -1, -1),
      intArrayOf(4, 11, 8, 4, 6, 11, 0, 2, 9, 2, 10, 9, -1, -1, -1, -1),
      intArrayOf(10, 9, 3, 10, 3, 2, 9, 4, 3, 11, 3, 6, 4, 6, 3, -1),
      intArrayOf(8, 2, 3, 8, 4, 2, 4, 6, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 4, 2, 4, 6, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 9, 0, 2, 3, 4, 2, 4, 6, 4, 3, 8, -1, -1, -1, -1),
      intArrayOf(1, 9, 4, 1, 4, 2, 2, 4, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 1, 3, 8, 6, 1, 8, 4, 6, 6, 10, 1, -1, -1, -1, -1),
      intArrayOf(10, 1, 0, 10, 0, 6, 6, 0, 4, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 6, 3, 4, 3, 8, 6, 10, 3, 0, 3, 9, 10, 9, 3, -1),
      intArrayOf(10, 9, 4, 6, 10, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 9, 5, 7, 6, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, 4, 9, 5, 11, 7, 6, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 0, 1, 5, 4, 0, 7, 6, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 7, 6, 8, 3, 4, 3, 5, 4, 3, 1, 5, -1, -1, -1, -1),
      intArrayOf(9, 5, 4, 10, 1, 2, 7, 6, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(6, 11, 7, 1, 2, 10, 0, 8, 3, 4, 9, 5, -1, -1, -1, -1),
      intArrayOf(7, 6, 11, 5, 4, 10, 4, 2, 10, 4, 0, 2, -1, -1, -1, -1),
      intArrayOf(3, 4, 8, 3, 5, 4, 3, 2, 5, 10, 5, 2, 11, 7, 6, -1),
      intArrayOf(7, 2, 3, 7, 6, 2, 5, 4, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 5, 4, 0, 8, 6, 0, 6, 2, 6, 8, 7, -1, -1, -1, -1),
      intArrayOf(3, 6, 2, 3, 7, 6, 1, 5, 0, 5, 4, 0, -1, -1, -1, -1),
      intArrayOf(6, 2, 8, 6, 8, 7, 2, 1, 8, 4, 8, 5, 1, 5, 8, -1),
      intArrayOf(9, 5, 4, 10, 1, 6, 1, 7, 6, 1, 3, 7, -1, -1, -1, -1),
      intArrayOf(1, 6, 10, 1, 7, 6, 1, 0, 7, 8, 7, 0, 9, 5, 4, -1),
      intArrayOf(4, 0, 10, 4, 10, 5, 0, 3, 10, 6, 10, 7, 3, 7, 10, -1),
      intArrayOf(7, 6, 10, 7, 10, 8, 5, 4, 10, 4, 8, 10, -1, -1, -1, -1),
      intArrayOf(6, 9, 5, 6, 11, 9, 11, 8, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 6, 11, 0, 6, 3, 0, 5, 6, 0, 9, 5, -1, -1, -1, -1),
      intArrayOf(0, 11, 8, 0, 5, 11, 0, 1, 5, 5, 6, 11, -1, -1, -1, -1),
      intArrayOf(6, 11, 3, 6, 3, 5, 5, 3, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 10, 9, 5, 11, 9, 11, 8, 11, 5, 6, -1, -1, -1, -1),
      intArrayOf(0, 11, 3, 0, 6, 11, 0, 9, 6, 5, 6, 9, 1, 2, 10, -1),
      intArrayOf(11, 8, 5, 11, 5, 6, 8, 0, 5, 10, 5, 2, 0, 2, 5, -1),
      intArrayOf(6, 11, 3, 6, 3, 5, 2, 10, 3, 10, 5, 3, -1, -1, -1, -1),
      intArrayOf(5, 8, 9, 5, 2, 8, 5, 6, 2, 3, 8, 2, -1, -1, -1, -1),
      intArrayOf(9, 5, 6, 9, 6, 0, 0, 6, 2, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 5, 8, 1, 8, 0, 5, 6, 8, 3, 8, 2, 6, 2, 8, -1),
      intArrayOf(1, 5, 6, 2, 1, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 3, 6, 1, 6, 10, 3, 8, 6, 5, 6, 9, 8, 9, 6, -1),
      intArrayOf(10, 1, 0, 10, 0, 6, 9, 5, 0, 5, 6, 0, -1, -1, -1, -1),
      intArrayOf(0, 3, 8, 5, 6, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 5, 6, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 5, 10, 7, 5, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 5, 10, 11, 7, 5, 8, 3, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 11, 7, 5, 10, 11, 1, 9, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(10, 7, 5, 10, 11, 7, 9, 8, 1, 8, 3, 1, -1, -1, -1, -1),
      intArrayOf(11, 1, 2, 11, 7, 1, 7, 5, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, 1, 2, 7, 1, 7, 5, 7, 2, 11, -1, -1, -1, -1),
      intArrayOf(9, 7, 5, 9, 2, 7, 9, 0, 2, 2, 11, 7, -1, -1, -1, -1),
      intArrayOf(7, 5, 2, 7, 2, 11, 5, 9, 2, 3, 2, 8, 9, 8, 2, -1),
      intArrayOf(2, 5, 10, 2, 3, 5, 3, 7, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 2, 0, 8, 5, 2, 8, 7, 5, 10, 2, 5, -1, -1, -1, -1),
      intArrayOf(9, 0, 1, 5, 10, 3, 5, 3, 7, 3, 10, 2, -1, -1, -1, -1),
      intArrayOf(9, 8, 2, 9, 2, 1, 8, 7, 2, 10, 2, 5, 7, 5, 2, -1),
      intArrayOf(1, 3, 5, 3, 7, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 7, 0, 7, 1, 1, 7, 5, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 0, 3, 9, 3, 5, 5, 3, 7, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 8, 7, 5, 9, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 8, 4, 5, 10, 8, 10, 11, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(5, 0, 4, 5, 11, 0, 5, 10, 11, 11, 3, 0, -1, -1, -1, -1),
      intArrayOf(0, 1, 9, 8, 4, 10, 8, 10, 11, 10, 4, 5, -1, -1, -1, -1),
      intArrayOf(10, 11, 4, 10, 4, 5, 11, 3, 4, 9, 4, 1, 3, 1, 4, -1),
      intArrayOf(2, 5, 1, 2, 8, 5, 2, 11, 8, 4, 5, 8, -1, -1, -1, -1),
      intArrayOf(0, 4, 11, 0, 11, 3, 4, 5, 11, 2, 11, 1, 5, 1, 11, -1),
      intArrayOf(0, 2, 5, 0, 5, 9, 2, 11, 5, 4, 5, 8, 11, 8, 5, -1),
      intArrayOf(9, 4, 5, 2, 11, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 5, 10, 3, 5, 2, 3, 4, 5, 3, 8, 4, -1, -1, -1, -1),
      intArrayOf(5, 10, 2, 5, 2, 4, 4, 2, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 10, 2, 3, 5, 10, 3, 8, 5, 4, 5, 8, 0, 1, 9, -1),
      intArrayOf(5, 10, 2, 5, 2, 4, 1, 9, 2, 9, 4, 2, -1, -1, -1, -1),
      intArrayOf(8, 4, 5, 8, 5, 3, 3, 5, 1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 4, 5, 1, 0, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(8, 4, 5, 8, 5, 3, 9, 0, 5, 0, 3, 5, -1, -1, -1, -1),
      intArrayOf(9, 4, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 11, 7, 4, 9, 11, 9, 10, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 8, 3, 4, 9, 7, 9, 11, 7, 9, 10, 11, -1, -1, -1, -1),
      intArrayOf(1, 10, 11, 1, 11, 4, 1, 4, 0, 7, 4, 11, -1, -1, -1, -1),
      intArrayOf(3, 1, 4, 3, 4, 8, 1, 10, 4, 7, 4, 11, 10, 11, 4, -1),
      intArrayOf(4, 11, 7, 9, 11, 4, 9, 2, 11, 9, 1, 2, -1, -1, -1, -1),
      intArrayOf(9, 7, 4, 9, 11, 7, 9, 1, 11, 2, 11, 1, 0, 8, 3, -1),
      intArrayOf(11, 7, 4, 11, 4, 2, 2, 4, 0, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(11, 7, 4, 11, 4, 2, 8, 3, 4, 3, 2, 4, -1, -1, -1, -1),
      intArrayOf(2, 9, 10, 2, 7, 9, 2, 3, 7, 7, 4, 9, -1, -1, -1, -1),
      intArrayOf(9, 10, 7, 9, 7, 4, 10, 2, 7, 8, 7, 0, 2, 0, 7, -1),
      intArrayOf(3, 7, 10, 3, 10, 2, 7, 4, 10, 1, 10, 0, 4, 0, 10, -1),
      intArrayOf(1, 10, 2, 8, 7, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 9, 1, 4, 1, 7, 7, 1, 3, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 9, 1, 4, 1, 7, 0, 8, 1, 8, 7, 1, -1, -1, -1, -1),
      intArrayOf(4, 0, 3, 7, 4, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(4, 8, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 10, 8, 10, 11, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 0, 9, 3, 9, 11, 11, 9, 10, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 1, 10, 0, 10, 8, 8, 10, 11, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 1, 10, 11, 3, 10, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 2, 11, 1, 11, 9, 9, 11, 8, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 0, 9, 3, 9, 11, 1, 2, 9, 2, 11, 9, -1, -1, -1, -1),
      intArrayOf(0, 2, 11, 8, 0, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(3, 2, 11, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 3, 8, 2, 8, 10, 10, 8, 9, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(9, 10, 2, 0, 9, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(2, 3, 8, 2, 8, 10, 0, 1, 8, 1, 10, 8, -1, -1, -1, -1),
      intArrayOf(1, 10, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(1, 3, 8, 9, 1, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 9, 1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(0, 3, 8, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1),
      intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
    )
  }
}

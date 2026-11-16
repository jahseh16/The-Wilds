package com.example

import java.io.File
import org.junit.Assume

/** Finds `app/src/main/assets` whatever the working directory of the test runner is. */
object TestAssets {
  private val root: File? = listOfNotNull(System.getProperty("assets.dir"), "src/main/assets", "app/src/main/assets")
    .map { File(it) }.firstOrNull { File(it, "models/trees/tree_01.obj").exists() }

  fun available(): Boolean = root != null

  fun read(path: String): String {
    Assume.assumeTrue("assets folder not found from ${File(".").absolutePath}", root != null)
    return File(root, path).readText()
  }

  fun exists(path: String): Boolean = root != null && File(root, path).exists()

  fun models(): ScatterModels = ScatterModels { read(it) }
}

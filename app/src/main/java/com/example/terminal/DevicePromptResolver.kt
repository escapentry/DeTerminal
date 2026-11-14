package com.example.terminal

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

object DevicePromptResolver {

  fun resolveDeviceModel(
    manufacturer: String = Build.MANUFACTURER.orEmpty(),
    model: String = Build.MODEL.orEmpty(),
    device: String = Build.DEVICE.orEmpty(),
  ): String {
    val cleanModel = model.trim().replace(Regex("\\s+"), " ")
    val cleanManufacturer = manufacturer.trim().replace(Regex("\\s+"), " ")
    val resolved = when {
      cleanModel.isNotBlank() &&
        (cleanManufacturer.isBlank() ||
          cleanModel.startsWith(cleanManufacturer, ignoreCase = true) ||
          cleanModel.length >= 6) -> cleanModel
      cleanManufacturer.isNotBlank() && cleanModel.isNotBlank() -> "$cleanManufacturer $cleanModel"
      cleanManufacturer.isNotBlank() -> cleanManufacturer
      device.isNotBlank() -> device.trim()
      else -> "Android"
    }
    return resolved.take(26)
  }

  @Suppress("DEPRECATION")
  fun resolveStartupDirectory(context: Context): File {
    val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    if (external != null && external.exists() && external.isDirectory) {
      return external
    }
    val sdcard = File("/sdcard")
    if (sdcard.exists() && sdcard.isDirectory) {
      return sdcard
    }
    return context.filesDir
  }

  /**
   * Replaces both the root directory ("/") and the startup folder (e.g. "/storage/emulated/0")
   * with the auto-determined device model so the prompt stays clean and tidy.
   */
  fun formatPromptLocation(
    currentDirPath: String,
    startupDirPath: String,
    deviceModel: String,
  ): String {
    val normalizedCurrent = normalizePath(currentDirPath)
    val normalizedStartup = normalizePath(startupDirPath)

    if (normalizedStartup != "/" && normalizedCurrent == normalizedStartup) {
      return deviceModel
    }
    if (normalizedStartup != "/" && normalizedCurrent.startsWith("$normalizedStartup/")) {
      val suffix = normalizedCurrent.removePrefix(normalizedStartup)
      return "$deviceModel$suffix"
    }
    if (normalizedCurrent == "/") {
      return deviceModel
    }
    if (normalizedCurrent.startsWith("/")) {
      return "$deviceModel$normalizedCurrent"
    }
    return normalizedCurrent.ifBlank { deviceModel }
  }

  fun buildPromptPrefix(
    deviceModel: String,
    currentDirPath: String,
    startupDirPath: String,
    isRoot: Boolean,
  ): String {
    val displayPath = formatPromptLocation(currentDirPath, startupDirPath, deviceModel)
    val symbol = if (isRoot) "#" else "$"
    return "$displayPath $symbol"
  }

  private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    return trimmed.trimEnd('/').ifEmpty { "/" }
  }
}

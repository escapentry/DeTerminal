package com.example.terminal

import androidx.compose.runtime.Immutable

enum class TerminalLineType {
  COMMAND,
  STDOUT,
  STDERR,
  INFO,
}

@Immutable
data class TerminalLine(
  val id: Long,
  val type: TerminalLineType,
  val text: String,
  val promptPrefix: String = "",
  val isRootPrompt: Boolean = false,
)

@Immutable
data class TerminalUiState(
  val lines: List<TerminalLine> = emptyList(),
  val deviceModel: String = "",
  val startupDir: String = "/",
  val currentDir: String = "/",
  val promptPrefix: String = "$",
  val isRoot: Boolean = false,
  val isRunning: Boolean = false,
  val inputText: String = "",
  val hasFilesPermission: Boolean = true,
  val permissionBannerDismissed: Boolean = false,
)

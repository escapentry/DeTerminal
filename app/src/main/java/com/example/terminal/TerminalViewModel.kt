package com.example.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

  companion object {
    private const val MAX_BUFFER_LINES = 2000
  }

  private val lineIdGen = AtomicLong(1L)
  private val deviceModel = DevicePromptResolver.resolveDeviceModel()
  private val startupDirectory = DevicePromptResolver.resolveStartupDirectory(application)
  private val startupDirPath = startupDirectory.absolutePath

  private val _uiState = MutableStateFlow(
    TerminalUiState(
      deviceModel = deviceModel,
      startupDir = startupDirPath,
      currentDir = startupDirPath,
      promptPrefix = DevicePromptResolver.buildPromptPrefix(
        deviceModel = deviceModel,
        currentDirPath = startupDirPath,
        startupDirPath = startupDirPath,
        isRoot = false,
      ),
      hasFilesPermission = StoragePermissionHelper.hasFilesPermission(application),
    )
  )
  val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

  private val _suspendEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val suspendEvents: SharedFlow<Unit> = _suspendEvents.asSharedFlow()

  private val commandHistory = mutableListOf<String>()
  private var historyCursor = -1
  private var historyDraft = ""

  private val shellEngine = TerminalShellEngine(
    scope = viewModelScope,
    startupDir = startupDirectory,
    onOutput = { type, text ->
      appendLine(TerminalLine(id = lineIdGen.getAndIncrement(), type = type, text = text))
    },
    onStateChanged = { dir, isRoot, isRunning ->
      _uiState.update { state ->
        state.copy(
          currentDir = dir,
          isRoot = isRoot,
          isRunning = isRunning,
          promptPrefix = DevicePromptResolver.buildPromptPrefix(
            deviceModel = state.deviceModel,
            currentDirPath = dir,
            startupDirPath = state.startupDir,
            isRoot = isRoot,
          ),
        )
      }
    },
    onClearOutput = {
      _uiState.update { it.copy(lines = emptyList()) }
    },
    onSuspendActivity = {
      _suspendEvents.tryEmit(Unit)
    },
  )

  init {
    viewModelScope.launch {
      shellEngine.start()
    }
  }

  fun onInputChanged(value: String) {
    if (value.contains('\n')) {
      val lines = value.split('\n')
      val toRun = lines.dropLast(1).joinToString("\n").trimEnd('\r')
      val remainder = lines.last()
      _uiState.update { it.copy(inputText = remainder) }
      if (toRun.isNotBlank()) {
        submitCommand(toRun)
      }
      return
    }
    _uiState.update { it.copy(inputText = value) }
  }

  fun submitCurrentInput() {
    val cmd = _uiState.value.inputText
    _uiState.update { it.copy(inputText = "") }
    submitCommand(cmd)
  }

  fun submitCommand(rawCommand: String) {
    val trimmed = rawCommand.trim()
    val state = _uiState.value
    appendLine(
      TerminalLine(
        id = lineIdGen.getAndIncrement(),
        type = TerminalLineType.COMMAND,
        text = rawCommand,
        promptPrefix = state.promptPrefix,
        isRootPrompt = state.isRoot,
      )
    )

    if (trimmed.isEmpty()) return

    if (commandHistory.lastOrNull() != trimmed) {
      commandHistory.add(trimmed)
      if (commandHistory.size > 200) {
        commandHistory.removeAt(0)
      }
    }
    historyCursor = -1
    historyDraft = ""

    viewModelScope.launch {
      shellEngine.execute(trimmed)
    }
  }

  fun interruptCommand() {
    shellEngine.interrupt()
  }

  fun historyUp() {
    if (commandHistory.isEmpty()) return
    if (historyCursor == -1) {
      historyDraft = _uiState.value.inputText
      historyCursor = commandHistory.lastIndex
    } else if (historyCursor > 0) {
      historyCursor--
    }
    _uiState.update { it.copy(inputText = commandHistory[historyCursor]) }
  }

  fun historyDown() {
    if (historyCursor == -1) return
    if (historyCursor < commandHistory.lastIndex) {
      historyCursor++
      _uiState.update { it.copy(inputText = commandHistory[historyCursor]) }
    } else {
      historyCursor = -1
      _uiState.update { it.copy(inputText = historyDraft) }
    }
  }

  fun insertQuickToken(token: String) {
    _uiState.update { it.copy(inputText = it.inputText + token) }
  }

  fun tabComplete() {
    val current = _uiState.value.inputText
    val lastSpace = current.lastIndexOf(' ')
    val prefix = if (lastSpace >= 0) current.substring(0, lastSpace + 1) else ""
    val token = if (lastSpace >= 0) current.substring(lastSpace + 1) else current
    val matches = shellEngine.completeToken(token)
    if (matches.isEmpty()) return

    if (matches.size == 1) {
      _uiState.update { it.copy(inputText = prefix + matches.first()) }
    } else {
      val common = longestCommonPrefix(matches)
      if (common.length > token.length) {
        _uiState.update { it.copy(inputText = prefix + common) }
      } else {
        appendLine(
          TerminalLine(
            id = lineIdGen.getAndIncrement(),
            type = TerminalLineType.INFO,
            text = matches.joinToString("  "),
          )
        )
      }
    }
  }

  fun refreshFilesPermission() {
    val granted = StoragePermissionHelper.hasFilesPermission(getApplication())
    _uiState.update { state ->
      state.copy(
        hasFilesPermission = granted,
        permissionBannerDismissed = if (granted) true else state.permissionBannerDismissed,
      )
    }
  }

  fun dismissPermissionBanner() {
    _uiState.update { it.copy(permissionBannerDismissed = true) }
  }

  override fun onCleared() {
    shellEngine.closeAll()
    super.onCleared()
  }

  private fun appendLine(line: TerminalLine) {
    _uiState.update { state ->
      val updated = (state.lines + line)
      val bounded = if (updated.size > MAX_BUFFER_LINES) {
        updated.drop(updated.size - MAX_BUFFER_LINES)
      } else {
        updated
      }
      state.copy(lines = bounded)
    }
  }

  private fun longestCommonPrefix(items: List<String>): String {
    if (items.isEmpty()) return ""
    var prefix = items.first()
    for (i in 1 until items.size) {
      val next = items[i]
      val limit = minOf(prefix.length, next.length)
      var matchLen = 0
      while (matchLen < limit && prefix[matchLen] == next[matchLen]) {
        matchLen++
      }
      prefix = prefix.substring(0, matchLen)
      if (prefix.isEmpty()) break
    }
    return prefix
  }
}

package com.example.terminal

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class TerminalShellEngine(
  private val scope: CoroutineScope,
  private val startupDir: File,
  private val onOutput: (TerminalLineType, String) -> Unit,
  private val onStateChanged: (currentDir: String, isRoot: Boolean, isRunning: Boolean) -> Unit,
  private val onClearOutput: () -> Unit,
  private val onSuspendActivity: () -> Unit,
) {
  companion object {
    private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private const val MARKER_PREFIX = "__DET_MARK_"
    private const val SU_READY_PREFIX = "__DET_SU_OK_"

    fun resolveShBinary(): String =
      listOf("/system/bin/sh", "/bin/sh")
        .firstOrNull { File(it).canExecute() }
        ?: "sh"

    fun resolveSuBinary(): String? =
      listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
      ).firstOrNull { File(it).canExecute() }
        ?: findExecutableInPath("su")

    private fun findExecutableInPath(name: String): String? {
      val pathEnv = System.getenv("PATH").orEmpty()
      for (dir in pathEnv.split(':')) {
        if (dir.isBlank()) continue
        val candidate = File(dir, name)
        if (candidate.exists() && candidate.canExecute()) {
          return candidate.absolutePath
        }
      }
      return null
    }

    fun stripAnsi(raw: String): String =
      ANSI_REGEX.replace(raw, "").replace("\r", "")
  }

  private data class PendingMarker(
    val token: String,
    val deferred: CompletableDeferred<Pair<Int, String>>,
  )

  private class ShellSession(
    val process: Process,
    val writer: BufferedWriter,
    val isRoot: Boolean,
    val stdoutJob: Job,
    val stderrJob: Job,
    val monitorJob: Job,
  ) {
    val closed = AtomicBoolean(false)
  }

  private val execMutex = Mutex()
  private var baseShSession: ShellSession? = null
  private var suSession: ShellSession? = null
  private var currentDirPath: String = startupDir.absolutePath

  @Volatile
  private var pendingMarker: PendingMarker? = null

  @Volatile
  private var pendingSuProbe: Pair<String, CompletableDeferred<String?>>? = null

  val isRootActive: Boolean
    get() = suSession != null

  val currentDirectory: String
    get() = currentDirPath

  suspend fun start() = withContext(Dispatchers.IO) {
    if (baseShSession == null) {
      baseShSession = spawnSession(
        binary = resolveShBinary(),
        workingDir = startupDir,
        isRoot = false,
      )
      notifyState(isRunning = false)
    }
  }

  suspend fun execute(rawCommand: String) {
    val trimmed = rawCommand.trim()
    if (trimmed.isEmpty()) return

    if (trimmed == "clear") {
      onClearOutput()
      return
    }

    if (trimmed == "exit" || trimmed.startsWith("exit ")) {
      handleExitCommand()
      return
    }

    execMutex.withLock {
      if (baseShSession == null) {
        start()
      }

      if (isInteractiveSuCommand(trimmed)) {
        enterSuShell()
        return
      }

      if (trimmed == "sh" && !isRootActive) {
        onOutput(TerminalLineType.INFO, "sh session active")
        return
      }

      val active = suSession ?: baseShSession
      if (active == null || !isProcessAlive(active.process)) {
        // Respawn base shell transparently
        baseShSession = spawnSession(resolveShBinary(), File(currentDirPath), isRoot = false)
        suSession = null
      }

      val target = suSession ?: baseShSession ?: return
      runInSession(target, rawCommand)
    }
  }

  fun interrupt() {
    scope.launch(Dispatchers.IO) {
      val runningMarker = pendingMarker
      pendingMarker = null
      pendingSuProbe?.second?.complete(null)
      pendingSuProbe = null

      val activeSu = suSession
      if (activeSu != null) {
        closeSession(activeSu)
        suSession = null
        runningMarker?.deferred?.complete(130 to currentDirPath)
        onOutput(TerminalLineType.STDERR, "^C")
        notifyState(isRunning = false)
        return@launch
      }

      val activeSh = baseShSession
      if (activeSh != null) {
        closeSession(activeSh)
        baseShSession = null
      }
      runningMarker?.deferred?.complete(130 to currentDirPath)
      onOutput(TerminalLineType.STDERR, "^C")
      val safeDir = File(currentDirPath).takeIf { it.exists() && it.isDirectory } ?: startupDir
      baseShSession = spawnSession(resolveShBinary(), safeDir, isRoot = false)
      notifyState(isRunning = false)
    }
  }

  fun closeAll() {
    pendingMarker?.deferred?.complete(130 to currentDirPath)
    pendingMarker = null
    pendingSuProbe?.second?.complete(null)
    pendingSuProbe = null
    suSession?.let { closeSession(it) }
    suSession = null
    baseShSession?.let { closeSession(it) }
    baseShSession = null
  }

  fun completeToken(token: String): List<String> {
    val clean = token.trim()
    val separatorIdx = clean.lastIndexOf('/')
    val dirPart: String
    val namePrefix: String
    if (separatorIdx >= 0) {
      dirPart = clean.substring(0, separatorIdx + 1)
      namePrefix = clean.substring(separatorIdx + 1)
    } else {
      dirPart = ""
      namePrefix = clean
    }

    val searchDir = when {
      dirPart.startsWith("/") -> File(dirPart)
      dirPart.startsWith("~/") -> File(startupDir, dirPart.removePrefix("~/"))
      dirPart == "~" -> startupDir
      dirPart.isNotEmpty() -> File(currentDirPath, dirPart)
      else -> File(currentDirPath)
    }

    val entries = runCatching { searchDir.listFiles() }.getOrNull() ?: return emptyList()
    return entries
      .filter { it.name.startsWith(namePrefix) }
      .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
      .take(24)
      .map { file ->
        val suffix = if (file.isDirectory) "/" else ""
        "$dirPart${file.name}$suffix"
      }
  }

  private suspend fun handleExitCommand() {
    val activeSu = suSession
    if (activeSu != null) {
      execMutex.withLock {
        closeSession(activeSu)
        suSession = null
        // Sync directory back into base sh
        baseShSession?.let { sh ->
          if (isProcessAlive(sh.process)) {
            runCatching {
              sh.writer.write("cd ${shellQuote(currentDirPath)}\n")
              sh.writer.flush()
            }
          }
        }
        onOutput(TerminalLineType.INFO, "Exited su")
        notifyState(isRunning = false)
      }
    } else {
      onSuspendActivity()
    }
  }

  private suspend fun enterSuShell() {
    if (suSession != null && isProcessAlive(suSession!!.process)) {
      onOutput(TerminalLineType.INFO, "Already in su session")
      return
    }
    val suBin = resolveSuBinary()
    if (suBin == null) {
      onOutput(TerminalLineType.STDERR, "su: binary not found (device not rooted)")
      return
    }

    notifyState(isRunning = true)
    val safeDir = File(currentDirPath).takeIf { it.exists() && it.isDirectory } ?: startupDir
    val candidate = runCatching {
      spawnSession(binary = suBin, workingDir = safeDir, isRoot = true)
    }.getOrElse { err ->
      onOutput(TerminalLineType.STDERR, "su: ${err.message ?: "unable to start root shell"}")
      notifyState(isRunning = false)
      return
    }

    val token = UUID.randomUUID().toString().replace("-", "").take(12)
    val deferred = CompletableDeferred<String?>()
    pendingSuProbe = token to deferred

    val writeOk = runCatching {
      withContext(Dispatchers.IO) {
        candidate.writer.write("cd ${shellQuote(currentDirPath)} 2>/dev/null; printf '${SU_READY_PREFIX}%s__:%s\\n' '$token' \"\$PWD\"\n")
        candidate.writer.flush()
      }
    }.isSuccess

    if (!writeOk) {
      pendingSuProbe = null
      closeSession(candidate)
      onOutput(TerminalLineType.STDERR, "su: permission denied")
      notifyState(isRunning = false)
      return
    }

    val resolvedPwd = withTimeoutOrNull(8000L) { deferred.await() }
    pendingSuProbe = null

    if (resolvedPwd == null || !isProcessAlive(candidate.process)) {
      closeSession(candidate)
      onOutput(TerminalLineType.STDERR, "su: permission denied or timed out")
      notifyState(isRunning = false)
      return
    }

    suSession = candidate
    if (resolvedPwd.isNotBlank()) {
      currentDirPath = resolvedPwd
    }
    onOutput(TerminalLineType.INFO, "su root shell active (#)")
    notifyState(isRunning = false)
  }

  private suspend fun runInSession(session: ShellSession, rawCommand: String) {
    val token = UUID.randomUUID().toString().replace("-", "").take(12)
    val deferred = CompletableDeferred<Pair<Int, String>>()
    pendingMarker = PendingMarker(token, deferred)
    notifyState(isRunning = true)

    val sent = runCatching {
      withContext(Dispatchers.IO) {
        session.writer.write(rawCommand)
        session.writer.write("\n")
        session.writer.write("__DET_EC=\$?\n")
        session.writer.write("printf '${MARKER_PREFIX}%s__:%d:%s\\n' '$token' \"\$__DET_EC\" \"\$PWD\"\n")
        session.writer.flush()
      }
    }.isSuccess

    if (!sent) {
      pendingMarker = null
      onOutput(TerminalLineType.STDERR, "shell write error")
      notifyState(isRunning = false)
      return
    }

    val (_, newPwd) = deferred.await()
    pendingMarker = null
    if (newPwd.isNotBlank()) {
      currentDirPath = newPwd
    }
    notifyState(isRunning = false)
  }

  private fun spawnSession(
    binary: String,
    workingDir: File,
    isRoot: Boolean,
  ): ShellSession {
    val safeDir = workingDir.takeIf { it.exists() && it.isDirectory }
      ?: File("/").takeIf { it.exists() }
      ?: workingDir
    val pb = ProcessBuilder(binary)
    pb.directory(safeDir)
    pb.redirectErrorStream(false)
    val env = pb.environment()
    env["TERM"] = "xterm-256color"
    env["HOME"] = startupDir.absolutePath

    val process = pb.start()
    val writer = BufferedWriter(OutputStreamWriter(process.outputStream))

    val stdoutJob = scope.launch(Dispatchers.IO) {
      val reader = BufferedReader(InputStreamReader(process.inputStream))
      runCatching {
        while (true) {
          val line = reader.readLine() ?: break
          handleStdoutLine(stripAnsi(line))
        }
      }
    }

    val stderrJob = scope.launch(Dispatchers.IO) {
      val reader = BufferedReader(InputStreamReader(process.errorStream))
      runCatching {
        while (true) {
          val line = reader.readLine() ?: break
          val clean = stripAnsi(line)
          if (clean.isNotEmpty()) {
            onOutput(TerminalLineType.STDERR, clean)
          }
        }
      }
    }

    val monitorJob = scope.launch(Dispatchers.IO) {
      runCatching { process.waitFor() }
      pendingSuProbe?.second?.complete(null)
      val marker = pendingMarker
      if (marker != null && !marker.deferred.isCompleted) {
        marker.deferred.complete(1 to currentDirPath)
      }
      if (isRoot && suSession?.process === process) {
        suSession = null
        onOutput(TerminalLineType.INFO, "su session ended")
        notifyState(isRunning = false)
      }
    }

    return ShellSession(process, writer, isRoot, stdoutJob, stderrJob, monitorJob)
  }

  private fun handleStdoutLine(line: String) {
    val probe = pendingSuProbe
    if (probe != null) {
      val probeTag = "${SU_READY_PREFIX}${probe.first}__:"
      val idx = line.indexOf(probeTag)
      if (idx >= 0) {
        val before = line.substring(0, idx)
        if (before.isNotEmpty()) onOutput(TerminalLineType.STDOUT, before)
        val pwd = line.substring(idx + probeTag.length).trim()
        probe.second.complete(pwd)
        return
      }
    }

    val marker = pendingMarker
    if (marker != null) {
      val markerTag = "${MARKER_PREFIX}${marker.token}__:"
      val idx = line.indexOf(markerTag)
      if (idx >= 0) {
        val before = line.substring(0, idx)
        if (before.isNotEmpty()) {
          onOutput(TerminalLineType.STDOUT, before)
        }
        val payload = line.substring(idx + markerTag.length)
        val colonIdx = payload.indexOf(':')
        val exitCode = if (colonIdx > 0) payload.substring(0, colonIdx).toIntOrNull() ?: 0 else 0
        val pwd = if (colonIdx >= 0) payload.substring(colonIdx + 1) else currentDirPath
        marker.deferred.complete(exitCode to pwd)
        return
      }
    }

    onOutput(TerminalLineType.STDOUT, line)
  }

  private fun closeSession(session: ShellSession) {
    if (!session.closed.compareAndSet(false, true)) return
    runCatching { session.writer.close() }
    runCatching { session.process.destroy() }
    if (isProcessAlive(session.process)) {
      runCatching { session.process.destroyForcibly() }
    }
    session.stdoutJob.cancel()
    session.stderrJob.cancel()
    session.monitorJob.cancel()
  }

  private fun notifyState(isRunning: Boolean) {
    onStateChanged(currentDirPath, isRootActive, isRunning)
  }

  private fun isInteractiveSuCommand(cmd: String): Boolean {
    return cmd == "su" || cmd == "su -" || cmd == "su 0" || cmd == "su root"
  }

  private fun isProcessAlive(process: Process): Boolean =
    runCatching {
      process.exitValue()
      false
    }.getOrDefault(true)

  private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
}

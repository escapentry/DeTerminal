package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.display.DisplayAutoScaler
import com.example.display.TerminalDisplayProfile
import com.example.terminal.StoragePermissionHelper
import com.example.terminal.TerminalLine
import com.example.terminal.TerminalLineType
import com.example.terminal.TerminalViewModel
import com.example.ui.theme.DangerCoral
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.OrbitCyan
import com.example.ui.theme.OrbitIndigo
import com.example.ui.theme.OrbitMint

@Composable
fun TerminalScreen(
  onSuspendActivity: () -> Unit,
  viewModel: TerminalViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val density = LocalDensity.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  val listState = rememberLazyListState()

  // Prevent Back button/gesture from exiting the terminal; only 'exit' suspends activity
  BackHandler(enabled = true) { /* intentionally ignored */ }

  LaunchedEffect(viewModel) {
    viewModel.suspendEvents.collect {
      onSuspendActivity()
    }
  }

  val legacyStorageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    viewModel.refreshFilesPermission()
  }

  val allFilesSettingsLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) {
    viewModel.refreshFilesPermission()
  }

  val requestFilesPermission = {
    if (StoragePermissionHelper.requiresAllFilesSettingsFlow()) {
      val primaryIntent = StoragePermissionHelper.createManageAllFilesIntent(context)
      val launched = runCatching {
        allFilesSettingsLauncher.launch(primaryIntent)
      }.isSuccess
      if (!launched) {
        runCatching {
          allFilesSettingsLauncher.launch(StoragePermissionHelper.createFallbackAllFilesIntent())
        }
      }
    } else {
      legacyStorageLauncher.launch(StoragePermissionHelper.legacyStoragePermissions)
    }
  }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshFilesPermission()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // On Android 7-10, request runtime storage permission immediately on startup if not granted
  LaunchedEffect(Unit) {
    if (!uiState.hasFilesPermission && !StoragePermissionHelper.requiresAllFilesSettingsFlow()) {
      legacyStorageLauncher.launch(StoragePermissionHelper.legacyStoragePermissions)
    }
  }

  // Auto-scroll to latest line as output streams
  LaunchedEffect(uiState.lines.size) {
    if (uiState.lines.isNotEmpty()) {
      listState.scrollToItem(uiState.lines.lastIndex)
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianVoid),
  ) {
    TerminalObsidianGlowBackdrop()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val profile = DisplayAutoScaler.computeProfile(
        availableWidthDp = maxWidth.value,
        availableHeightDp = maxHeight.value,
        density = density.density,
        systemFontScale = density.fontScale,
      )

      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
      ) {
        AnimatedVisibility(
          visible = !uiState.hasFilesPermission && !uiState.permissionBannerDismissed,
        ) {
          FilesPermissionBanner(
            onGrantClick = requestFilesPermission,
            onDismissClick = viewModel::dismissPermissionBanner,
            modifier = Modifier
              .padding(horizontal = profile.horizontalPaddingDp, vertical = 6.dp),
          )
        }

        // Scrollable Terminal Transcript (tap anywhere to focus & open keyboard)
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) {
              focusRequester.requestFocus()
              keyboardController?.show()
            },
        ) {
          SelectionContainer {
            LazyColumn(
              state = listState,
              contentPadding = PaddingValues(
                horizontal = profile.horizontalPaddingDp,
                vertical = 8.dp,
              ),
              verticalArrangement = Arrangement.spacedBy(2.dp),
              modifier = Modifier
                .fillMaxSize()
                .testTag("terminal_output_list"),
            ) {
              items(uiState.lines, key = { it.id }) { line ->
                TerminalOutputLineRow(
                  line = line,
                  profile = profile,
                )
              }
            }
          }
        }

        // Active Input Prompt Bar
        TerminalCommandInputBar(
          promptPrefix = uiState.promptPrefix,
          isRoot = uiState.isRoot,
          isRunning = uiState.isRunning,
          inputText = uiState.inputText,
          profile = profile,
          focusRequester = focusRequester,
          onInputChange = viewModel::onInputChanged,
          onSubmit = {
            viewModel.submitCurrentInput()
            runCatching { focusRequester.requestFocus() }
          },
          onHistoryUp = viewModel::historyUp,
          onHistoryDown = viewModel::historyDown,
          onTabComplete = viewModel::tabComplete,
          onInterrupt = viewModel::interruptCommand,
        )

        LaunchedEffect(Unit) {
          runCatching { focusRequester.requestFocus() }
          keyboardController?.show()
        }

        // Compact Obsidian Quick-Key Accessory Strip
        TerminalAccessoryKeyBar(
          isRunning = uiState.isRunning,
          isRoot = uiState.isRoot,
          profile = profile,
          onInsertToken = { token ->
            viewModel.insertQuickToken(token)
            runCatching { focusRequester.requestFocus() }
          },
          onHistoryUp = {
            viewModel.historyUp()
            runCatching { focusRequester.requestFocus() }
          },
          onHistoryDown = {
            viewModel.historyDown()
            runCatching { focusRequester.requestFocus() }
          },
          onInterrupt = viewModel::interruptCommand,
          onToggleSu = {
            if (uiState.isRoot) {
              viewModel.submitCommand("exit")
            } else {
              viewModel.submitCommand("su")
            }
            runCatching { focusRequester.requestFocus() }
          },
        )
      }
    }
  }
}

@Composable
private fun TerminalObsidianGlowBackdrop() {
  Canvas(modifier = Modifier.fillMaxSize()) {
    drawRect(color = ObsidianVoid, size = size)
    val maxDim = maxOf(size.width, size.height)
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(OrbitCyan.copy(alpha = 0.08f), Color.Transparent),
        center = Offset(size.width * 0.18f, size.height * 0.14f),
        radius = maxDim * 0.55f,
      ),
      radius = maxDim * 0.55f,
      center = Offset(size.width * 0.18f, size.height * 0.14f),
    )
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(OrbitIndigo.copy(alpha = 0.07f), Color.Transparent),
        center = Offset(size.width * 0.85f, size.height * 0.72f),
        radius = maxDim * 0.60f,
      ),
      radius = maxDim * 0.60f,
      center = Offset(size.width * 0.85f, size.height * 0.72f),
    )
  }
}

@Composable
private fun FilesPermissionBanner(
  onGrantClick: () -> Unit,
  onDismissClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = Color(0xCC101624),
    shape = RoundedCornerShape(14.dp),
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, OrbitCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
      .testTag("files_permission_banner"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
      Text(
        text = stringResource(id = R.string.files_permission_prompt),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.88f),
        modifier = Modifier.weight(1f),
      )
      TextButton(
        onClick = onGrantClick,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("grant_files_permission_button"),
      ) {
        Text(
          text = stringResource(id = R.string.grant_files_permission),
          color = OrbitCyan,
          fontWeight = FontWeight.SemiBold,
        )
      }
      IconButton(
        onClick = onDismissClick,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.Close,
          contentDescription = stringResource(id = R.string.dismiss),
          tint = Color.White.copy(alpha = 0.65f),
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun TerminalOutputLineRow(
  line: TerminalLine,
  profile: TerminalDisplayProfile,
) {
  val monoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = profile.terminalFontSp,
    lineHeight = profile.terminalLineHeightSp,
  )

  when (line.type) {
    TerminalLineType.COMMAND -> {
      val promptColor = if (line.isRootPrompt) DangerCoral else OrbitCyan
      val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = promptColor, fontWeight = FontWeight.SemiBold)) {
          append(line.promptPrefix)
          append(" ")
        }
        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Medium)) {
          append(line.text)
        }
      }
      Text(
        text = annotated,
        style = monoStyle,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    TerminalLineType.STDOUT -> {
      Text(
        text = line.text,
        style = monoStyle,
        color = Color(0xFFE2E8F0),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    TerminalLineType.STDERR -> {
      Text(
        text = line.text,
        style = monoStyle,
        color = DangerCoral,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    TerminalLineType.INFO -> {
      Text(
        text = line.text,
        style = monoStyle,
        color = OrbitMint,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
private fun TerminalCommandInputBar(
  promptPrefix: String,
  isRoot: Boolean,
  isRunning: Boolean,
  inputText: String,
  profile: TerminalDisplayProfile,
  focusRequester: FocusRequester,
  onInputChange: (String) -> Unit,
  onSubmit: () -> Unit,
  onHistoryUp: () -> Unit,
  onHistoryDown: () -> Unit,
  onTabComplete: () -> Unit,
  onInterrupt: () -> Unit,
) {
  var tfv by remember { mutableStateOf(TextFieldValue(inputText)) }
  if (tfv.text != inputText) {
    tfv = TextFieldValue(
      text = inputText,
      selection = TextRange(inputText.length),
    )
  }

  val promptColor = if (isRoot) DangerCoral else OrbitCyan

  Surface(
    color = Color(0xB8101624),
    modifier = Modifier
      .fillMaxWidth()
      .border(1.dp, Color.White.copy(alpha = 0.08f)),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .padding(horizontal = profile.horizontalPaddingDp, vertical = 6.dp),
    ) {
      Text(
        text = promptPrefix,
        style = TextStyle(
          fontFamily = FontFamily.Monospace,
          fontSize = profile.terminalFontSp,
          fontWeight = FontWeight.Bold,
        ),
        color = promptColor,
        modifier = Modifier.testTag("terminal_prompt_label"),
      )

      Spacer(modifier = Modifier.width(8.dp))

      Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier.weight(1f),
      ) {
        if (inputText.isEmpty()) {
          Text(
            text = if (isRunning) "running…" else stringResource(id = R.string.command_hint),
            style = TextStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = profile.terminalFontSp,
            ),
            color = Color.White.copy(alpha = 0.35f),
          )
        }
        BasicTextField(
          value = tfv,
          onValueChange = { next ->
            tfv = next
            onInputChange(next.text)
          },
          singleLine = true,
          cursorBrush = SolidColor(promptColor),
          textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = profile.terminalFontSp,
            color = Color.White,
          ),
          keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Send,
          ),
          keyboardActions = KeyboardActions(
            onSend = { onSubmit() },
            onDone = { onSubmit() },
          ),
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
              if (event.type == KeyEventType.KeyDown) {
                when {
                  event.isCtrlPressed && event.key == Key.C -> {
                    onInterrupt()
                    true
                  }
                  event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    onSubmit()
                    true
                  }
                  event.key == Key.DirectionUp -> {
                    onHistoryUp()
                    true
                  }
                  event.key == Key.DirectionDown -> {
                    onHistoryDown()
                    true
                  }
                  event.key == Key.Tab -> {
                    onTabComplete()
                    true
                  }
                  else -> false
                }
              } else {
                false
              }
            }
            .testTag("terminal_command_input"),
        )
      }
    }
  }
}

@Composable
private fun TerminalAccessoryKeyBar(
  isRunning: Boolean,
  isRoot: Boolean,
  profile: TerminalDisplayProfile,
  onInsertToken: (String) -> Unit,
  onHistoryUp: () -> Unit,
  onHistoryDown: () -> Unit,
  onInterrupt: () -> Unit,
  onToggleSu: () -> Unit,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xD80A0F1A))
      .padding(horizontal = profile.horizontalPaddingDp, vertical = 4.dp),
  ) {
    val itemModifier = Modifier.weight(1f)
    AccessoryKeyChip("/", profile, tag = "key_slash", modifier = itemModifier) { onInsertToken("/") }
    AccessoryKeyChip("-", profile, tag = "key_dash", modifier = itemModifier) { onInsertToken("-") }
    AccessoryKeyChip("|", profile, tag = "key_pipe", modifier = itemModifier) { onInsertToken("|") }
    AccessoryKeyChip("~", profile, tag = "key_tilde", modifier = itemModifier) { onInsertToken("~") }
    AccessoryKeyChip("↑", profile, tag = "key_up", modifier = itemModifier, onClick = onHistoryUp)
    AccessoryKeyChip("↓", profile, tag = "key_down", modifier = itemModifier, onClick = onHistoryDown)
    AccessoryKeyChip(
      label = if (isRunning) "^C" else if (isRoot) "sh" else "su",
      profile = profile,
      accent = if (isRunning || isRoot) DangerCoral else OrbitCyan,
      tag = if (isRunning) "key_ctrl_c" else "key_su",
      modifier = itemModifier,
      onClick = if (isRunning) onInterrupt else onToggleSu,
    )
  }
}

@Composable
private fun AccessoryKeyChip(
  label: String,
  profile: TerminalDisplayProfile,
  accent: Color = Color.White,
  tag: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .heightIn(min = profile.accessoryBarHeightDp)
      .clip(RoundedCornerShape(10.dp))
      .background(Color(0x88172033))
      .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 4.dp)
      .testTag(tag),
  ) {
    Text(
      text = label,
      style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = profile.accessoryFontSp,
        fontWeight = FontWeight.SemiBold,
      ),
      color = accent,
      maxLines = 1,
    )
  }
}

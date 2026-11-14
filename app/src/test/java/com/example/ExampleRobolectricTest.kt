package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.terminal.DevicePromptResolver
import com.example.terminal.TerminalLineType
import com.example.terminal.TerminalShellEngine
import com.example.ui.TerminalScreen
import com.example.ui.theme.TerminalTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `app name matches Terminal`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("Terminal", context.getString(R.string.app_name))
  }

  @Test
  fun `device prompt resolver replaces root and startup folder with device model`() {
    val model = DevicePromptResolver.resolveDeviceModel(
      manufacturer = "Google",
      model = "Pixel 8",
      device = "shiba",
    )
    assertEquals("Pixel 8", model)

    val startup = "/storage/emulated/0"
    assertEquals("Pixel 8", DevicePromptResolver.formatPromptLocation(startup, startup, model))
    assertEquals(
      "Pixel 8/Documents",
      DevicePromptResolver.formatPromptLocation("$startup/Documents", startup, model),
    )
    assertEquals("Pixel 8", DevicePromptResolver.formatPromptLocation("/", startup, model))
    assertEquals(
      "Pixel 8/system/bin",
      DevicePromptResolver.formatPromptLocation("/system/bin", startup, model),
    )
    assertEquals("Pixel 8 $", DevicePromptResolver.buildPromptPrefix(model, startup, startup, false))
    assertEquals("Pixel 8 #", DevicePromptResolver.buildPromptPrefix(model, "/", startup, true))
  }

  @Test
  fun `shell engine executes sh commands tracks directories and suspends on exit`() = runBlocking {
    val tempRoot = File(System.getProperty("java.io.tmpdir"), "terminal_test_${System.nanoTime()}")
    val subDir = File(tempRoot, "workspace").apply { mkdirs() }
    val outputs = mutableListOf<Pair<TerminalLineType, String>>()
    var suspended = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val engine = TerminalShellEngine(
      scope = scope,
      startupDir = tempRoot,
      onOutput = { type, text -> synchronized(outputs) { outputs.add(type to text) } },
      onStateChanged = { _, _, _ -> },
      onClearOutput = { synchronized(outputs) { outputs.clear() } },
      onSuspendActivity = { suspended = true },
    )

    try {
      engine.start()
      engine.execute("echo tidy_terminal_ok")
      assertTrue(outputs.any { it.first == TerminalLineType.STDOUT && it.second.contains("tidy_terminal_ok") })

      engine.execute("cd workspace")
      assertTrue(engine.currentDirectory.endsWith("workspace"))

      engine.execute("clear")
      assertTrue(outputs.isEmpty())

      assertFalse(suspended)
      engine.execute("exit")
      assertTrue(suspended)
      assertEquals(subDir.name, File(engine.currentDirectory).name)
    } finally {
      engine.closeAll()
      scope.cancel()
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `terminal screen renders immediately with prompt input and accessory keys`() {
    var suspended = false
    composeTestRule.setContent {
      TerminalTheme {
        TerminalScreen(
          onSuspendActivity = { suspended = true },
        )
      }
    }

    composeTestRule.onNodeWithTag("terminal_prompt_label").assertIsDisplayed()
    composeTestRule.onNodeWithTag("terminal_command_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_tab").assertDoesNotExist()
    composeTestRule.onNodeWithTag("key_slash").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_dash").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_pipe").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_tilde").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_up").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_down").assertIsDisplayed()
    composeTestRule.onNodeWithTag("key_su").assertIsDisplayed()

    composeTestRule.onNodeWithTag("terminal_command_input").performTextInput("echo hello_world")
    composeTestRule.onNodeWithTag("terminal_command_input").performImeAction()
    composeTestRule.waitUntil(timeoutMillis = 3000) {
      runCatching {
        composeTestRule.onNodeWithText("hello_world").assertIsDisplayed()
        true
      }.getOrDefault(false)
    }
    assertFalse(suspended)

    composeTestRule.onNodeWithTag("terminal_command_input").performTextInput("exit")
    composeTestRule.onNodeWithTag("terminal_command_input").performImeAction()
    composeTestRule.waitUntil(timeoutMillis = 3000) { suspended }
    assertTrue(suspended)
  }
}

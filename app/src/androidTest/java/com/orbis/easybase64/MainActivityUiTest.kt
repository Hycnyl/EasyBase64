package com.orbis.easybase64

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val preferences get() = instrumentation.targetContext.getSharedPreferences("easybase64_ui", Context.MODE_PRIVATE)

    @Before fun resetUserPreferencesAndQueue() {
        preferences.edit().clear().putString("language", "en").putString("theme", "light").commit()
        val repository = (compose.activity.application as EasyBase64Application).repository
        runBlocking { repository.load(); repository.clear() }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("language_en").performScrollTo().performClick()
        compose.onNodeWithTag("theme_light").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("settings_dialog").assertDoesNotExist()
        compose.waitForIdle()
    }

    @Test fun emptyQueueCannotStartAndCreditIsVisible() {
        compose.onNodeWithTag("app_title").assertIsDisplayed()
        compose.onNodeWithTag("start_button").assertIsNotEnabled()
        saveScreenshot("phone-english-light")
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithText("Orbis & Codex", substring = true).assertExists()
    }

    @Test fun languageAndThemeSurviveActivityRecreation() {
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("language_zh").performScrollTo().performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().performClick()
        assertEquals("zh", preferences.getString("language", null))
        assertEquals("dark", preferences.getString("theme", null))
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("settings_dialog").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("app_title").assertIsDisplayed()
        compose.onNodeWithTag("start_button").assertIsNotEnabled()
        compose.onNodeWithText("添加文件").assertExists()
        saveScreenshot("phone-chinese-dark")
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag("language_en").performScrollTo().performClick()
        compose.onNodeWithTag("theme_light").performScrollTo().performClick()
        assertEquals("en", preferences.getString("language", null))
        assertEquals("light", preferences.getString("theme", null))
    }

    @Test fun certificateWrapperLocksItsRequiredFormat() {
        compose.onNodeWithText("Encode").performClick()
        if (compose.onAllNodesWithTag("options_panel").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("Format options").performScrollTo().performClick()
        }
        compose.onNodeWithTag("certificate_toggle").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("padding_toggle").assertIsNotEnabled().assertIsOn()
        compose.onNodeWithText("URL-safe").assertIsNotEnabled()
        compose.onNodeWithTag("final_newline_toggle").assertIsNotEnabled().assertIsOn()
        compose.onNodeWithTag("certificate_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("padding_toggle").assertIsEnabled()
    }

    @Test fun lowHeightLandscapeRemainsUsable() {
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(timeoutMillis = 15_000) {
                instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            compose.onNodeWithTag("app_title").assertIsDisplayed()
            compose.onNodeWithTag("start_button").assertIsNotEnabled()
            compose.onNodeWithTag("settings_button").assertIsDisplayed().performClick()
            compose.onNodeWithTag("settings_dialog").assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithTag("settings_dialog").assertDoesNotExist()
            saveScreenshot("phone-landscape")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        assertEquals("A system dialog must not obscure the screenshot", instrumentation.targetContext.packageName,
            UiDevice.getInstance(instrumentation).currentPackageName)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot capture failed" }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

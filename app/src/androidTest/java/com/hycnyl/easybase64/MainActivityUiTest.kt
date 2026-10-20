package com.hycnyl.easybase64

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.onNodeWithTag("developer_link").performScrollTo().assertTextEquals("Hycnyl")
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

    @Test fun bilingualSettingsShowLinkedAttributionAndDistributionNotice() {
        Intents.init()
        try {
            // Capture launches before Android resolves a browser; no network or browser is required.
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            for (zh in listOf(false, true)) {
                openSettings(zh, dark = zh)
                compose.onNodeWithText(if (zh) "设置" else "Settings").assertIsDisplayed()
                compose.onAllNodesWithText("Orbis", substring = true).assertCountEquals(0)
                compose.onAllNodesWithText("Codex", substring = true).assertCountEquals(0)
                compose.onAllNodesWithText("云盘文档提供方", substring = true).assertCountEquals(0)
                compose.onAllNodesWithText("cloud document provider", substring = true, ignoreCase = true)
                    .assertCountEquals(0)

                compose.onNodeWithTag("developer_link").performScrollTo()
                    .assertTextEquals("Hycnyl").assertHasClickAction()
                assertBlueText("developer_link")
                compose.onNodeWithTag("developer_link").performClick()
                compose.onNodeWithTag("github_link").performScrollTo()
                    .assertTextEquals("Hycnyl/EasyBase64").assertHasClickAction()
                assertBlueText("github_link")
                compose.onNodeWithTag("github_link").performClick()
                assertSettingsIconsAligned()

                compose.onNodeWithTag("background_notice").assertTextEquals(if (zh)
                    "后台任务可能被 Android 中断；重新打开应用后可检查并重试。"
                else "Background tasks can be interrupted by Android; reopen the app to review and retry.")
                compose.onNodeWithTag("distribution_notice").performScrollTo().assertTextEquals(if (zh)
                    "本软件仅通过 GitHub 分发，与 easybase64.com、easybase64.org 等网站无任何关系。"
                else "This software is distributed only through GitHub and has no affiliation with easybase64.com, easybase64.org, or any such websites.")
                saveScreenshot(if (zh) "settings-chinese-dark-notice" else "settings-english-light-notice")
                closeDialog(zh)
            }
            val launches = Intents.getIntents().filter { it.action == Intent.ACTION_VIEW }
            assertEquals(
                listOf("https://github.com/Hycnyl", "https://github.com/Hycnyl/EasyBase64",
                    "https://github.com/Hycnyl", "https://github.com/Hycnyl/EasyBase64"),
                launches.map { it.dataString },
            )
            assertTrue("Links must be offered to an external browsable handler",
                launches.all { it.hasCategory(Intent.CATEGORY_BROWSABLE) })
        } finally {
            Intents.release()
        }
    }

    @Test fun bilingualFormatLabelsExplainWindowsCertificateStyle() {
        for (zh in listOf(false, true)) {
            openSettings(zh)
            closeDialog(zh)
            compose.onNodeWithText(if (zh) "编码" else "Encode").performScrollTo().performClick()
            openFormatOptions(zh)
            compose.onNodeWithTag("certutil_explanation").performScrollTo().assertTextEquals(if (zh)
                "即 Windows 设备上 certutil -encode 'filename' 的默认样式。"
            else "The default style of certutil -encode 'filename' on Windows devices.")
            compose.onNodeWithText(if (zh) "保留末尾填充（=）" else "Keep trailing padding (=)")
                .performScrollTo().assertIsDisplayed()
            saveScreenshot(if (zh) "encode-options-chinese" else "encode-options-english")
            closeDialog(zh)

            compose.onNodeWithText(if (zh) "解码" else "Decode").performScrollTo().performClick()
            openFormatOptions(zh)
            val labels = if (zh) listOf("允许 ASCII 空白", "接受 CERTIFICATE 包装", "接受 省略填充（=）")
                else listOf("Allow ASCII whitespace", "Accept CERTIFICATE wrapper", "Accept omitted padding (=)")
            labels.forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText(if (zh)
                "本软件将校验完整输入，包括填充字符和结束边界之后的内容，不要尝试解码有缺损的文件！"
            else "This software validates the entire input, including data after padding characters and the final boundary. Do not attempt to decode incomplete or damaged files!")
                .performScrollTo().assertIsDisplayed()
            saveScreenshot(if (zh) "decode-options-chinese" else "decode-options-english")
            closeDialog(zh)
        }
    }

    private fun openSettings(zh: Boolean, dark: Boolean = false) {
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag(if (zh) "language_zh" else "language_en").performScrollTo().performClick()
        compose.onNodeWithTag(if (dark) "theme_dark" else "theme_light").performScrollTo().performClick()
    }

    private fun openFormatOptions(zh: Boolean) {
        if (compose.onAllNodesWithTag("options_panel").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText(if (zh) "格式参数" else "Format options").performScrollTo().performClick()
        }
    }

    private fun closeDialog(zh: Boolean) {
        compose.onNodeWithText(if (zh) "完成" else "Done").performClick()
        compose.waitForIdle()
    }

    private fun assertBlueText(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("The link must expose its rendered text", results.isNotEmpty())
        val color = results.single().layoutInput.style.color
        assertTrue("The visible link must remain blue in either theme",
            color.alpha > 0.99f && color.blue > color.red && color.blue > color.green)
    }

    private fun assertSettingsIconsAligned() {
        val bounds = listOf("developer_icon", "github_icon", "privacy_icon").map { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
                .fetchSemanticsNode().boundsInRoot
        }
        val expectedSize = 20f * compose.activity.resources.displayMetrics.density
        bounds.forEach {
            assertEquals("Setting icons share their left edge", bounds.first().left, it.left, 1f)
            assertEquals("Setting icons retain consistent width", expectedSize, it.width, 1f)
            assertEquals("Setting icons retain consistent height", expectedSize, it.height, 1f)
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

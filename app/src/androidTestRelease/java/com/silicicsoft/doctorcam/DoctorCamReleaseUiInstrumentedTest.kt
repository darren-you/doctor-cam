package com.silicicsoft.doctorcam

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用正式 Activity 与 Hilt 装配验证生产页面、重建和真实横屏，不消费调试入口。 */
@RunWith(AndroidJUnit4::class)
class DoctorCamReleaseUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionDevicePageSurvivesRecreationAndRotationWithoutDebugPanel() {
        assertEquals(0, composeRule.activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertProductionDevicePage()
        composeRule.activityRule.scenario.recreate()
        assertProductionDevicePage()

        val originalOrientation = composeRule.activity.requestedOrientation
        val originalWindow = composeRule.onNodeWithTag("doctorcam_devices_screen")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("本用例需要真实竖屏起点以验证横屏窗口变化", originalWindow.height > originalWindow.width)
        try {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForOrientation(landscape = true)
            assertProductionDevicePage()
        } finally {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = originalOrientation
            }
            waitForOrientation(landscape = false)
        }
    }

    private fun assertProductionDevicePage() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("doctorcam_empty_devices").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("doctorcam_devices_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("doctorcam_empty_devices").assertIsDisplayed()
        composeRule.onNodeWithText("设备列表").assertIsDisplayed()
        composeRule.onNodeWithText("暂无设备").assertIsDisplayed()
        for (tag in listOf("debug_panel_expanded", "debug_panel_collapsed", "debug_panel_drag_handle")) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
        }
        composeRule.onAllNodes(
            hasClickAction() and (
                hasText("TestPage") or hasText("ONNX", substring = true, ignoreCase = true) or
                    hasContentDescription("ONNX", substring = true, ignoreCase = true)
                ),
        ).assertCountEquals(0)
        composeRule.onNodeWithTag("doctorcam_test_screen").assertDoesNotExist()
    }

    private fun waitForOrientation(landscape: Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val window = composeRule.onAllNodesWithTag("doctorcam_devices_screen")
                .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            window != null && if (landscape) window.width > window.height else window.height > window.width
        }
        composeRule.waitForIdle()
    }
}

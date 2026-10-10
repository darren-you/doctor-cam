package cn.darrenyou.doctorcam

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 使用真实 Activity、Hilt 装配与 Compose 导航验证没有接入 UVC 摄像头时的交互。
 * 不注入虚构设备，不把空态与导航测试当作真实摄像头预览或抓拍验收。
 */
@RunWith(AndroidJUnit4::class)
class DoctorCamUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deviceListEmptyStateSurvivesActivityRecreation() {
        assertDeviceList()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
        assertOnnxEntryIsHidden()

        composeRule.activityRule.scenario.recreate()

        assertDeviceList()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
        assertOnnxEntryIsHidden()
    }

    @Test
    fun debugPanelNavigatesToTestPageAndBack() {
        assertDeviceList()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
        composeRule.onNodeWithText("TestPage").performClick()

        composeRule.onNodeWithTag("doctorcam_test_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("doctorcam_devices_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("折叠调试面板").performClick()
        composeRule.onNodeWithTag("debug_panel_collapsed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("展开调试面板").performClick()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()

        composeRule.onNodeWithTag("doctorcam_test_back").performClick()

        assertDeviceList()
        composeRule.onNodeWithTag("doctorcam_test_screen").assertDoesNotExist()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
    }

    @Test
    fun debugPanelDragsCollapsesAndExpandsWithoutOpeningDuringDrag() {
        val originalOrientation = composeRule.activity.requestedOrientation
        val originalWindow = composeRule.onNodeWithTag("doctorcam_devices_screen")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("本用例需要真实竖屏起点以验证横屏窗口变化", originalWindow.height > originalWindow.width)
        try {
            exerciseDebugPanelGesturesAndRotation()
        } finally {
            composeRule.activityRule.scenario.onActivity {
                it.requestedOrientation = originalOrientation
            }
            composeRule.waitUntil(timeoutMillis = 10_000) {
                val screen = composeRule.onAllNodesWithTag("doctorcam_devices_screen")
                    .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
                screen != null && screen.height > screen.width
            }
            composeRule.waitForIdle()
        }
    }

    private fun exerciseDebugPanelGesturesAndRotation() {
        assertDeviceList()
        val expandedBefore = composeRule.onNodeWithTag("debug_panel_expanded")
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("debug_panel_drag_handle").performTouchInput {
            swipe(start = center, end = center + Offset(90f, 110f), durationMillis = 300)
        }
        composeRule.waitForIdle()
        val expandedAfter = composeRule.onNodeWithTag("debug_panel_expanded")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("真实触摸拖动必须移动面板", (expandedAfter.topLeft - expandedBefore.topLeft).getDistance() > 40f)

        composeRule.onNodeWithContentDescription("折叠调试面板").performClick()
        val collapsedBefore = composeRule.onNodeWithTag("debug_panel_collapsed")
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("debug_panel_collapsed").performTouchInput {
            swipe(start = center, end = center + Offset(90f, 110f), durationMillis = 300)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("debug_panel_expanded").assertDoesNotExist()
        val collapsedAfter = composeRule.onNodeWithTag("debug_panel_collapsed")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("折叠态拖动必须移动且不误触展开", (collapsedAfter.topLeft - collapsedBefore.topLeft).getDistance() > 40f)

        composeRule.onNodeWithContentDescription("展开调试面板").performClick()
        composeRule.onNodeWithTag("debug_panel_expanded").assertIsDisplayed()
        composeRule.onNodeWithTag("debug_panel_collapsed").assertDoesNotExist()
        assertDeviceList()

        val panelBeforeRotation = composeRule.onNodeWithTag("debug_panel_expanded")
            .fetchSemanticsNode().boundsInRoot
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            val screen = composeRule.onAllNodesWithTag("doctorcam_devices_screen")
                .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            screen != null && screen.width > screen.height
        }
        composeRule.waitForIdle()
        val panelAfterRotation = composeRule.onNodeWithTag("debug_panel_expanded")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("真实横屏不能裁切面板宽度", panelAfterRotation.width >= panelBeforeRotation.width - 1f)
        assertTrue("真实横屏不能裁切面板高度", panelAfterRotation.height >= panelBeforeRotation.height - 1f)
        assertPanelInsideSafeArea("debug_panel_expanded")
        composeRule.onNodeWithContentDescription("折叠调试面板").performClick()
        assertPanelInsideSafeArea("debug_panel_collapsed")
        composeRule.onNodeWithContentDescription("展开调试面板").performClick()
        assertPanelInsideSafeArea("debug_panel_expanded")
    }

    private fun assertDeviceList() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("doctorcam_empty_devices").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("doctorcam_devices_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("doctorcam_empty_devices").assertIsDisplayed()
        composeRule.onNodeWithText("设备列表").assertIsDisplayed()
        composeRule.onNodeWithText("暂无设备").assertIsDisplayed()
    }

    private fun assertOnnxEntryIsHidden() {
        // 只检查可点击的实际入口，避免把后端说明或模型名称误判成用户入口。
        composeRule.onAllNodes(
            hasClickAction() and (
                hasText("ONNX", substring = true, ignoreCase = true) or
                    hasContentDescription("ONNX", substring = true, ignoreCase = true)
                ),
        ).assertCountEquals(0)
    }

    private fun assertPanelInsideSafeArea(tag: String) {
        composeRule.waitForIdle()
        val screen = composeRule.onNodeWithTag("doctorcam_devices_screen")
            .fetchSemanticsNode().boundsInRoot
        val panel = composeRule.onNodeWithTag(tag).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val insets = composeRule.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        assertTrue("面板左侧必须在实际窗口安全区内", panel.left >= screen.left + insets.left - 1f)
        assertTrue("面板顶部必须在实际窗口安全区内", panel.top >= screen.top + insets.top - 1f)
        assertTrue("面板右侧必须在实际窗口安全区内", panel.right <= screen.right - insets.right + 1f)
        assertTrue("面板底部必须在实际窗口安全区内", panel.bottom <= screen.bottom - insets.bottom + 1f)
    }
}

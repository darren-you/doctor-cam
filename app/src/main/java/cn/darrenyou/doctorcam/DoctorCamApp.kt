package cn.darrenyou.doctorcam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cn.darrenyou.doctorcam.core.ui.components.DebugPanel
import cn.darrenyou.doctorcam.feature.devices.DevicesPage
import cn.darrenyou.doctorcam.feature.devices.DevicesRoute
import cn.darrenyou.doctorcam.feature.camera.CameraPage
import cn.darrenyou.doctorcam.feature.camera.CameraRoute
import cn.darrenyou.doctorcam.feature.test.TestPage
import cn.darrenyou.doctorcam.feature.test.TestRoute

// Release 编译前显式关闭；门禁检查这个唯一字面量。
private const val _debug = false

@Composable
fun DoctorCamApp() {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = DevicesRoute) {
            composable<DevicesRoute> {
                DevicesPage(onOpenCamera = { navController.navigate(it) })
            }
            composable<CameraRoute> { entry ->
                CameraPage(route = entry.toRoute(), onBack = { navController.popBackStack() })
            }
            composable<TestRoute> {
                TestPage(onBack = { navController.popBackStack() })
            }
        }
        if (_debug) {
            DebugPanel(onOpenTestPage = { navController.navigate(TestRoute) { launchSingleTop = true } })
        }
    }
}

package cn.darrenyou.doctorcam.feature.devices

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.darrenyou.doctorcam.feature.camera.CameraRoute
import kotlinx.serialization.Serializable

@Serializable
data object DevicesRoute

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
fun DevicesPage(onOpenCamera: (CameraRoute) -> Unit, viewModel: DevicesViewModel = hiltViewModel()) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeviceId by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingVendorId by rememberSaveable { mutableStateOf(0) }
    var pendingProductId by rememberSaveable { mutableStateOf(0) }
    var pendingDetection by rememberSaveable { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA)
    }
    fun hasRequiredPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = hasRequiredPermissions()
        val route = pendingDeviceId?.let { CameraRoute(it, pendingVendorId, pendingProductId, pendingDetection) }
        pendingDeviceId = null
        if (granted && route != null && devices.any {
            it.deviceId == route.deviceId && it.vendorId == route.vendorId && it.productId == route.productId
        }) onOpenCamera(route)
        else permissionMessage = if (granted) "所选摄像头已断开"
            else if (Build.VERSION.SDK_INT <= 28) "请允许相机与存储权限后再次选择摄像头"
            else "请允许相机权限后再次选择摄像头"
    }
    fun open(route: CameraRoute) {
        permissionMessage = null
        if (hasRequiredPermissions()) {
            onOpenCamera(route)
        } else {
            pendingDeviceId = route.deviceId
            pendingVendorId = route.vendorId
            pendingProductId = route.productId
            pendingDetection = route.detection
            launcher.launch(requiredPermissions)
        }
    }
    Scaffold(
        modifier = Modifier.testTag("doctorcam_devices_screen"),
        topBar = { TopAppBar(title = { Text("设备列表") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DoctorCam", style = MaterialTheme.typography.headlineMedium)
            Text("连接 USB UVC 摄像头后，轻点预览，长按进入 Paddle 检测。", style = MaterialTheme.typography.bodyLarge)
            permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (devices.isEmpty()) {
                Card(Modifier.fillMaxWidth().testTag("doctorcam_empty_devices")) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Usb, contentDescription = null)
                        Text("暂无设备", style = MaterialTheme.typography.titleLarge)
                        Text("请通过支持数据传输的 USB 连接线或转接器连接摄像头。")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(devices, key = { it.deviceId }) { device ->
                        Card(Modifier.fillMaxWidth().combinedClickable(
                            onClickLabel = "预览摄像头",
                            onLongClickLabel = "Paddle 检测",
                            onClick = { open(CameraRoute(device.deviceId, device.vendorId, device.productId, false)) },
                            onLongClick = { open(CameraRoute(device.deviceId, device.vendorId, device.productId, true)) },
                        )) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(device.name, style = MaterialTheme.typography.titleMedium)
                                Text("VID %04X · PID %04X".format(device.vendorId, device.productId))
                                Text("轻点预览 · 长按检测", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

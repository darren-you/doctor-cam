package com.silicicsoft.doctorcam.feature.camera

import android.Manifest
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Build
import android.content.pm.PackageManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silicicsoft.doctorcam.core.camera.CameraConnection
import com.silicicsoft.doctorcam.core.inference.Detection
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class CameraRoute(val deviceId: Int, val vendorId: Int, val productId: Int, val detection: Boolean)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CameraPage(route: CameraRoute, onBack: () -> Unit, viewModel: CameraViewModel = hiltViewModel()) {
    val state by viewModel.cameraState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resume()
            if (event == Lifecycle.Event.ON_PAUSE) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pause()
        }
    }
    Scaffold(
        modifier = Modifier.testTag("uvc_vision_camera_screen"),
        topBar = {
            TopAppBar(
                title = { Text(if (route.detection) "Paddle 检测" else "UVC 预览") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回设备列表") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                val ratio = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 4f / 3f
                val frameModifier = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                    else Modifier.fillMaxWidth().aspectRatio(ratio)
                Box(frameModifier) {
                    CameraTexture(viewModel, Modifier.fillMaxSize())
                    DetectionOverlay(state.detections, Modifier.fillMaxSize())
                }
                if (!state.hasFrame) Text(connectionText(state.connection), color = Color.White, modifier = Modifier.padding(24.dp))
            }
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connectionText(state.connection), style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it) }
                Button(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    enabled = state.hasFrame && !saving,
                    onClick = {
                        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                            viewModel.reportStoragePermissionMissing()
                        else viewModel.save()
                    },
                ) { Text(if (saving) "正在保存…" else "抓拍并保存到相册") }
            }
        }
    }
}

private fun connectionText(connection: CameraConnection): String = when (connection) {
    CameraConnection.IDLE -> "等待摄像头"
    CameraConnection.INITIALIZING -> "正在加载模型"
    CameraConnection.REQUESTING_PERMISSION -> "等待 USB 授权"
    CameraConnection.CONNECTING -> "正在连接摄像头"
    CameraConnection.STREAMING -> "预览中"
    CameraConnection.PERMISSION_DENIED -> "未获得 USB 权限，请返回后重新选择摄像头"
    CameraConnection.DISCONNECTED -> "摄像头已断开"
    CameraConnection.ERROR -> "摄像头初始化失败"
}

private class PreviewSurfaceOwner {
    var surface: Surface? = null
    var texture: SurfaceTexture? = null
}

@Composable
private fun CameraTexture(viewModel: CameraViewModel, modifier: Modifier) {
    val owner = remember { PreviewSurfaceOwner() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val surface = Surface(texture)
                        owner.surface = surface
                        owner.texture = texture
                        viewModel.controller.setSurface(surface, width, height)
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        viewModel.controller.setSurface(owner.surface, width, height)
                    }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        if (owner.texture === texture) {
                            val surface = owner.surface
                            owner.surface = null
                            owner.texture = null
                            viewModel.controller.clearSurface(surface) { texture.release() }
                        }
                        // 停止 Native 预览后由控制器回调释放 Texture，框架不提前回收。
                        return false
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        onRelease = { _ ->
            val texture = owner.texture
            val surface = owner.surface
            owner.surface = null
            owner.texture = null
            if (texture != null) viewModel.controller.clearSurface(surface) { texture.release() }
            // 保留 destroyed 回调的 false 返回值，避免框架与控制器竞争释放。
        },
    )
}

@Composable
private fun DetectionOverlay(detections: List<Detection>, modifier: Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.RED; textSize = 32f } }
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Magenta)
    Canvas(modifier) {
        for (detection in detections) {
            if (detection.sourceWidth <= 0 || detection.sourceHeight <= 0) continue
            val sx = size.width / detection.sourceWidth
            val sy = size.height / detection.sourceHeight
            val left = detection.left * sx
            val top = detection.top * sy
            val color = colors[detection.classId.coerceAtLeast(0) % colors.size]
            paint.color = color.toArgb() // drawIntoCanvas 使用相同类别颜色。
            drawRect(color, Offset(left, top), Size((detection.right - detection.left) * sx, (detection.bottom - detection.top) * sy), style = Stroke(3.dp.toPx()))
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText("${detection.className} ${String.format(Locale.ROOT, "%.2f", detection.confidence)}", left, (top - 8).coerceAtLeast(paint.textSize), paint)
            }
        }
    }
}

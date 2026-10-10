package cn.darrenyou.doctorcam.core.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import cn.darrenyou.doctorcam.core.inference.Detection
import cn.darrenyou.doctorcam.core.inference.DetectionMode
import cn.darrenyou.doctorcam.core.inference.InferenceEngine
import cn.darrenyou.doctorcam.core.inference.InferenceEngineFactory
import cn.darrenyou.doctorcam.utils.ImageProcessingUtils
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 每个预览 ViewModel 独占一份控制器；所有设备资源操作在同一 Looper 上串行执行。 */
class UsbCameraController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val inferenceFactory: InferenceEngineFactory,
) {
    private val workerThread = HandlerThread("doctor-cam-camera").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sessionId = AtomicLong()
    private val mutableState = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var cameraSessionId: Long? = null
    private var controlBlock: USBMonitor.UsbControlBlock? = null
    private var inference: InferenceEngine? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var permissionSelection: DeviceSelection? = null
    private var permissionRequested = false
    @Volatile private var disposed = false
    @Volatile private var acceptingFrames = false
    private val frameLock = Any()
    private var lastFrame: CapturedFrame? = null

    fun open(deviceId: Int, vendorId: Int, productId: Int, mode: DetectionMode) {
        if (disposed) return
        val id = nextSession()
        val selection = DeviceSelection(deviceId, vendorId, productId)
        worker.post {
            if (!isCurrent(id)) return@post
            closeSession()?.let { cleanupError ->
                publish(id) { CameraState(connection = CameraConnection.ERROR, error = cleanupError.message) }
                return@post
            }
            if (permissionSelection != selection) {
                permissionSelection = selection
                permissionRequested = false
            }
            publish(id) { CameraState(connection = CameraConnection.INITIALIZING) }
            try {
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) { "UVC 摄像头需要相机权限" }
                val device = usbManager.deviceList.values.firstOrNull(selection::matches)
                    ?: throw IOException("所选摄像头已断开")
                val currentMonitor = USBMonitor(context, listener(id, selection, mode))
                monitor = currentMonitor
                currentMonitor.register()
                if (!permissionRequested || currentMonitor.hasPermission(device)) {
                    permissionRequested = true
                    publish(id) { it.copy(connection = CameraConnection.REQUESTING_PERMISSION) }
                    currentMonitor.requestPermission(device)
                } else {
                    publish(id) {
                        it.copy(connection = CameraConnection.PERMISSION_DENIED, error = "未获得 USB 设备权限")
                    }
                }
            } catch (error: Exception) {
                fail(id, error)
            } catch (error: LinkageError) {
                fail(id, error)
            }
        }
    }

    /** 消费非空 Surface 的所有权；同一个对象的尺寸更新不会停止预览或释放对象。 */
    fun setSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        if (surface == null) {
            clearSurface()
            return
        }
        if (disposed) {
            surface.release()
            return
        }
        val posted = worker.post {
            if (disposed) {
                surface.release()
                return@post
            }
            val id = sessionId.get()
            surfaceWidth = width
            surfaceHeight = height
            if (this.surface === surface) {
                if (!acceptingFrames && camera != null) {
                    try {
                        startPreview(id)
                    } catch (error: Exception) {
                        fail(id, error)
                    } catch (error: LinkageError) {
                        fail(id, error)
                    }
                }
                return@post
            }
            try {
                if (this.surface != null) {
                    synchronized(frameLock) { acceptingFrames = false }
                    camera?.stopPreview()
                }
            } catch (error: Exception) {
                fail(id, error)
            } catch (error: LinkageError) {
                fail(id, error)
            } finally {
                releaseOwnedSurface()
            }
            this.surface = surface
            discardFrame()
            publish(id) { it.copy(hasFrame = false, detections = emptyList()) }
            if (disposed) {
                releaseOwnedSurface()
                return@post
            }
            try {
                startPreview(id)
            } catch (error: Exception) {
                fail(id, error)
            } catch (error: LinkageError) {
                fail(id, error)
            }
        }
        if (!posted) surface.release()
    }

    /** 先在设备线程停止预览并释放 Surface，再通知 UI 释放所属 SurfaceTexture。 */
    fun clearSurface(expectedSurface: Surface? = null, onReleased: () -> Unit = {}) {
        if (expectedSurface == null) synchronized(frameLock) { acceptingFrames = false }
        val posted = worker.post {
            if (expectedSurface != null && surface !== expectedSurface) {
                // 替换已释放旧 Surface；迟到的旧 TextureView 回调只收尾它自己的 Texture。
                notifySurfaceReleased(onReleased)
                return@post
            }
            synchronized(frameLock) { acceptingFrames = false }
            val id = sessionId.get()
            try {
                camera?.stopPreview()
            } catch (error: Exception) {
                fail(id, error)
            } catch (error: LinkageError) {
                fail(id, error)
            } finally {
                releaseOwnedSurface()
                discardFrame()
                publish(id) { it.copy(hasFrame = false, detections = emptyList()) }
                notifySurfaceReleased(onReleased)
            }
        }
        // dispose 会先释放 Surface 再退出 Looper；拒绝入队时资源已不再被借用。
        if (!posted) notifySurfaceReleased(onReleased)
    }

    /** 返回独立位图，避免后台保存期间被下一帧替换或回收。 */
    suspend fun capture(): CapturedFrame {
        var snapshot: CapturedFrame? = null
        try {
            return withContext(Dispatchers.Default) {
                synchronized(frameLock) {
                    if (!acceptingFrames) throw IOException("相机预览未运行")
                    val frame = lastFrame ?: throw IOException("尚未收到完整相机帧")
                    CapturedFrame(
                        bitmap = frame.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            ?: throw IOException("无法复制相机图像"),
                        detections = frame.detections,
                    ).also { snapshot = it }
                }
            }
        } catch (error: CancellationException) {
            // withContext 返回调用方时也可能取消；未交付的位图仍由控制器回收。
            snapshot?.bitmap?.recycle()
            throw error
        }
    }

    /** 暂停页面时释放连接和模型，但保留该设备本次授权请求状态。 */
    fun close() {
        if (disposed) return
        val id = nextSession()
        worker.post {
            if (!isCurrent(id)) return@post
            val error = closeSession()
            publish(id) {
                if (error == null) CameraState() else CameraState(connection = CameraConnection.ERROR, error = error.message)
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        nextSession()
        worker.post {
            closeSession()
            releaseOwnedSurface()
            workerThread.quitSafely()
        }
    }

    private fun listener(id: Long, selection: DeviceSelection, mode: DetectionMode) =
        object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) = Unit

            override fun onDettach(device: UsbDevice?) {
                if (device != null && selection.matches(device)) worker.post {
                    if (isCurrent(id)) {
                        closeSession()
                        publish(id) { CameraState(
                            connection = CameraConnection.DISCONNECTED,
                            error = "摄像头已断开",
                        ) }
                    }
                }
            }

            override fun onConnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                if (device == null || block == null || !selection.matches(device)) return
                worker.post {
                    if (!isCurrent(id) || camera != null) return@post
                    try {
                        publish(id) { it.copy(connection = CameraConnection.CONNECTING, error = null) }
                        controlBlock = block
                        inference = inferenceFactory.create(mode)
                        if (!isCurrent(id)) {
                            closeSession()
                            return@post
                        }
                        camera = UVCCamera()
                        cameraSessionId = id
                        checkNotNull(camera).open(block)
                        selectPreviewSize(id)
                        startPreview(id)
                    } catch (error: Exception) {
                        fail(id, error)
                    } catch (error: LinkageError) {
                        fail(id, error)
                    }
                }
            }

            override fun onDisconnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
                worker.post {
                    // UVC 自身 close 会同步关闭克隆的 control block；旧回调不能关闭新连接。
                    if (block != null && isCurrent(id) && camera?.usbControlBlock === block) {
                        closeSession()
                        publish(id) { CameraState(
                            connection = CameraConnection.DISCONNECTED,
                            error = "摄像头连接已关闭",
                        ) }
                    }
                }
            }

            override fun onCancel(device: UsbDevice?) {
                worker.post {
                    publish(id) {
                        it.copy(connection = CameraConnection.PERMISSION_DENIED, error = "未获得 USB 设备权限")
                    }
                }
            }
        }

    private fun selectPreviewSize(id: Long) {
        val currentCamera = checkNotNull(camera)
        val targetRatio = if (surfaceWidth > 0 && surfaceHeight > 0) {
            surfaceWidth.toFloat() / surfaceHeight
        } else 16f / 9f
        val formats = listOf(UVCCamera.FRAME_FORMAT_MJPEG to 6, UVCCamera.FRAME_FORMAT_YUYV to 4)
        for ((format, descriptorType) in formats) {
            val candidates = UVCCamera.getSupportedSize(descriptorType, currentCamera.supportedSize)
                .filter { it.width > 0 && it.height > 0 && it.width % 2 == 0 && it.height % 2 == 0 }
                .sortedWith(compareBy<com.serenegiant.usb.Size> { abs(it.width.toFloat() / it.height - targetRatio) }
                    .thenBy { abs(it.width.toLong() * it.height - 1280L * 720) })
            for (size in candidates) {
                try {
                    currentCamera.setPreviewSize(size.width, size.height, format)
                    publish(id) { it.copy(width = size.width, height = size.height) }
                    return
                } catch (_: IllegalArgumentException) {
                    // 只尝试摄像头真实声明的尺寸和格式。
                }
            }
        }
        throw IOException("摄像头未提供可用的 MJPEG / YUYV 预览格式")
    }

    private fun startPreview(id: Long) {
        val currentCamera = camera ?: return
        val currentSurface = surface ?: return
        if (!isCurrent(id) || cameraSessionId != id || !currentSurface.isValid) return
        currentCamera.setFrameCallback(frameCallback(id, inference), UVCCamera.PIXEL_FORMAT_NV21)
        currentCamera.setPreviewDisplay(currentSurface)
        synchronized(frameLock) {
            if (!isCurrent(id)) return
            acceptingFrames = true
        }
        currentCamera.startPreview()
        publish(id) { it.copy(connection = CameraConnection.STREAMING, error = null) }
    }

    private fun frameCallback(id: Long, engine: InferenceEngine?) = IFrameCallback { buffer ->
        if (!isCurrent(id) || !acceptingFrames) return@IFrameCallback
        val dimensions = mutableState.value
        var bitmap: android.graphics.Bitmap? = null
        var transferred = false
        try {
            val converted = ImageProcessingUtils.getFrameAsBitmap(buffer, dimensions.width, dimensions.height)
                ?: return@IFrameCallback
            bitmap = converted
            val detections: List<Detection> = engine?.detect(converted) ?: emptyList()
            if (!isCurrent(id) || !acceptingFrames) return@IFrameCallback
            synchronized(frameLock) {
                if (!isCurrent(id) || !acceptingFrames) return@IFrameCallback
                lastFrame?.bitmap?.recycle()
                lastFrame = CapturedFrame(converted, detections)
                transferred = true
                mutableState.update { it.copy(hasFrame = true, detections = detections) }
            }
        } catch (error: Exception) {
            worker.post { fail(id, error) }
        } catch (error: LinkageError) {
            worker.post { fail(id, error) }
        } finally {
            if (!transferred) bitmap?.recycle()
        }
    }

    private fun fail(id: Long, error: Throwable) {
        if (!isCurrent(id)) return
        closeSession()
        publish(id) { CameraState(connection = CameraConnection.ERROR, error = error.message ?: "相机运行失败") }
    }

    private fun closeSession(): Throwable? {
        synchronized(frameLock) { acceptingFrames = false }
        val oldCamera = camera
        camera = null
        cameraSessionId = null
        val oldControlBlock = controlBlock
        controlBlock = null
        val oldMonitor = monitor
        monitor = null
        val oldInference = inference
        inference = null
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                if (failure == null) failure = error
                Log.e("UsbCameraController", "资源释放失败", error)
            } catch (error: LinkageError) {
                if (failure == null) failure = error
                Log.e("UsbCameraController", "Native 资源释放失败", error)
            }
        }
        release { oldCamera?.destroy() }
        // UVCCamera.open 会 clone；原监视器连接也必须明确关闭。
        release { oldControlBlock?.close() }
        release { oldMonitor?.destroy() }
        release { oldInference?.close() }
        discardFrame()
        return failure
    }

    private fun discardFrame() = synchronized(frameLock) {
        lastFrame?.bitmap?.recycle()
        lastFrame = null
    }

    private fun releaseOwnedSurface() {
        val oldSurface = surface
        surface = null
        try {
            oldSurface?.release()
        } catch (error: Exception) {
            Log.e("UsbCameraController", "预览 Surface 释放失败", error)
        } catch (error: LinkageError) {
            Log.e("UsbCameraController", "预览 Surface Native 释放失败", error)
        }
    }

    private fun notifySurfaceReleased(onReleased: () -> Unit) {
        try {
            onReleased()
        } catch (error: Exception) {
            Log.e("UsbCameraController", "预览 SurfaceTexture 释放回调失败", error)
        } catch (error: LinkageError) {
            Log.e("UsbCameraController", "预览 SurfaceTexture Native 释放回调失败", error)
        }
    }

    private fun nextSession(): Long = synchronized(frameLock) {
        acceptingFrames = false
        sessionId.incrementAndGet()
    }

    private fun publish(id: Long, transform: (CameraState) -> CameraState) = synchronized(frameLock) {
        if (isCurrent(id)) mutableState.value = transform(mutableState.value)
    }

    private fun isCurrent(id: Long): Boolean = !disposed && sessionId.get() == id

    private data class DeviceSelection(val deviceId: Int, val vendorId: Int, val productId: Int) {
        fun matches(device: UsbDevice): Boolean = device.deviceId == deviceId &&
            device.vendorId == vendorId && device.productId == productId
    }
}

package cn.darrenyou.doctorcam.feature.camera

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import cn.darrenyou.doctorcam.core.camera.UsbCameraController
import cn.darrenyou.doctorcam.core.inference.DetectionMode
import cn.darrenyou.doctorcam.core.media.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val controller: UsbCameraController,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<CameraRoute>()
    val cameraState = controller.state
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    private var resumed = false
    fun resume() {
        if (resumed) return
        resumed = true
        controller.open(route.deviceId, route.vendorId, route.productId,
            if (route.detection) DetectionMode.PADDLE else DetectionMode.PREVIEW)
    }
    fun pause() {
        if (!resumed) return
        resumed = false
        controller.close()
    }
    fun storagePermissionDenied() { mutableMessage.value = "未获得存储权限，图片未保存" }
    fun save() {
        if (mutableSaving.value || !cameraState.value.hasFrame) return
        mutableSaving.value = true
        mutableMessage.value = null
        viewModelScope.launch {
            try {
                val uri = mediaRepository.save(controller.capture()).getOrThrow()
                mutableMessage.value = "已保存到系统相册：$uri"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableMessage.value = "保存失败：${error.message ?: "未知错误"}"
            } finally {
                mutableSaving.value = false
            }
        }
    }
    override fun onCleared() { controller.dispose(); super.onCleared() }
}

package com.silicicsoft.doctorcam.core.camera

import com.silicicsoft.doctorcam.core.inference.Detection

data class CameraState(
    val connection: CameraConnection = CameraConnection.IDLE,
    val width: Int = 0,
    val height: Int = 0,
    val hasFrame: Boolean = false,
    val detections: List<Detection> = emptyList(),
    val error: String? = null,
)

enum class CameraConnection {
    IDLE,
    INITIALIZING,
    REQUESTING_PERMISSION,
    CONNECTING,
    STREAMING,
    PERMISSION_DENIED,
    DISCONNECTED,
    ERROR,
}

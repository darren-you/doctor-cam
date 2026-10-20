package com.silicicsoft.doctorcam.core.camera

data class CameraDevice(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val manufacturer: String,
    val deviceName: String,
)

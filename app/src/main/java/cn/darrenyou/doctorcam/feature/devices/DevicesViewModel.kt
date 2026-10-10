package cn.darrenyou.doctorcam.feature.devices

import androidx.lifecycle.ViewModel
import cn.darrenyou.doctorcam.core.camera.UsbDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DevicesViewModel @Inject constructor(repository: UsbDeviceRepository) : ViewModel() {
    val devices = repository.devices
}

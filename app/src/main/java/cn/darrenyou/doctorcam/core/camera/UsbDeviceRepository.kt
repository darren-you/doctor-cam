package cn.darrenyou.doctorcam.core.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn

@Singleton
class UsbDeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 列表只观察 USB 系统事件；权限和连接唯一由预览页的控制器持有。
    val devices: StateFlow<List<CameraDevice>> = callbackFlow {
        fun publish() {
            trySend(currentDevices())
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                publish()
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        publish()
        // 部分 USB 宿主不发送 attached 广播；保留原 USBMonitor 的两秒枚举行为。
        val enumeration = launch {
            while (true) {
                delay(2_000)
                publish()
            }
        }
        awaitClose {
            enumeration.cancel()
            context.unregisterReceiver(receiver)
        }
    }.conflate().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun currentDevices(): List<CameraDevice> = usbManager.deviceList.values
        .filter(::isVideoDevice)
        .map { device ->
            CameraDevice(
                deviceId = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                name = device.productName ?: "USB 摄像头",
                manufacturer = device.manufacturerName ?: "未知厂商",
                deviceName = device.deviceName,
            )
        }.sortedBy { it.deviceId }

    private fun isVideoDevice(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
}

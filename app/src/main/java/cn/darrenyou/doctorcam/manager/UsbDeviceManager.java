package cn.darrenyou.doctorcam.manager;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbConstants;
import android.os.Handler;
import android.os.Looper;

import com.serenegiant.usb.USBMonitor;

import java.util.ArrayList;
import java.util.List;

public class UsbDeviceManager {
    private Context context;
    private USBMonitor usbMonitor;
    private List<UsbDeviceInfo> deviceList = new ArrayList<>();
    private DeviceListListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean released;

    public UsbDeviceManager(Context context) {
        this.context = context;
        initUsbMonitor();
    }

    private void initUsbMonitor() {
        usbMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice device) {
                updateDeviceList();
            }

            @Override
            public void onDettach(UsbDevice device) {
                updateDeviceList();
            }

            @Override
            public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                // 设备已连接，可以开始使用
            }

            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                // 设备已断开连接
            }

            @Override
            public void onCancel(UsbDevice device) {
                // 用户取消了权限请求
            }
        });
    }

    public synchronized void register() {
        if (usbMonitor != null) {
            usbMonitor.register();
            updateDeviceList();
        }
    }

    public synchronized void unregister() {
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
    }

    public synchronized void release() {
        released = true;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }
    }

    private synchronized void updateDeviceList() {
        if (released || usbMonitor == null) {
            return;
        }
        deviceList.clear();
        List<UsbDevice> devices = usbMonitor.getDeviceList();
        
        for (UsbDevice device : devices) {
            if (!isVideoDevice(device)) {
                continue;
            }
            UsbDeviceInfo deviceInfo = new UsbDeviceInfo(
                    device,
                    device.getDeviceName(),
                    device.getManufacturerName(),
                    device.getProductName(),
                    device.getDeviceId(),
                    device.getVendorId(),
                    device.getProductId()
            );
            deviceList.add(deviceInfo);
        }
        
        // 使用Handler确保在主线程中回调
        List<UsbDeviceInfo> snapshot = new ArrayList<>(deviceList);
        mainHandler.post(() -> {
            if (!released && listener != null) {
                listener.onDeviceListUpdated(snapshot);
            }
        });
    }

    private static boolean isVideoDevice(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    public List<UsbDeviceInfo> getDeviceList() {
        return new ArrayList<>(deviceList);
    }

    public void setDeviceListListener(DeviceListListener listener) {
        this.listener = listener;
    }

    public void requestPermission(UsbDevice device) {
        if (usbMonitor != null) {
            usbMonitor.requestPermission(device);
        }
    }

    public static class UsbDeviceInfo {
        private UsbDevice device;
        private String deviceName;
        private String manufacturerName;
        private String productName;
        private int deviceId;
        private int vendorId;
        private int productId;

        public UsbDeviceInfo(UsbDevice device, String deviceName, String manufacturerName,
                             String productName, int deviceId, int vendorId, int productId) {
            this.device = device;
            this.deviceName = deviceName;
            this.manufacturerName = manufacturerName;
            this.productName = productName;
            this.deviceId = deviceId;
            this.vendorId = vendorId;
            this.productId = productId;
        }

        public UsbDevice getDevice() {
            return device;
        }

        public String getDeviceName() {
            return deviceName != null ? deviceName : "未知设备";
        }

        public String getManufacturerName() {
            return manufacturerName != null ? manufacturerName : "未知厂商";
        }

        public String getProductName() {
            return productName != null ? productName : "未知产品";
        }

        public int getDeviceId() {
            return deviceId;
        }

        public int getVendorId() {
            return vendorId;
        }

        public int getProductId() {
            return productId;
        }

        @Override
        public String toString() {
            return "设备名称: " + getProductName() +
                    "\n厂商: " + getManufacturerName() +
                    "\n设备ID: " + getDeviceId() +
                    "\n厂商ID: " + getVendorId() +
                    "\n产品ID: " + getProductId();
        }
    }

    public interface DeviceListListener {
        void onDeviceListUpdated(List<UsbDeviceInfo> deviceList);
    }
}

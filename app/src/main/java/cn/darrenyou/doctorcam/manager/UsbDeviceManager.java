package cn.darrenyou.doctorcam.manager;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;

import com.serenegiant.usb.USBMonitor;

import java.util.ArrayList;
import java.util.List;

public class UsbDeviceManager {
    private static final String ACTION_USB_PERMISSION = "cn.darrenyou.doctorcam.USB_PERMISSION";

    private Context context;
    private USBMonitor usbMonitor;
    private List<UsbDeviceInfo> deviceList = new ArrayList<>();
    private DeviceListListener listener;

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

    public void register() {
        if (usbMonitor != null) {
            usbMonitor.register();
            updateDeviceList();
        }
    }

    public void unregister() {
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
    }

    public void release() {
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }
    }

    private void updateDeviceList() {
        deviceList.clear();
        List<UsbDevice> devices = usbMonitor.getDeviceList();
        
        for (UsbDevice device : devices) {
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
        new Handler(Looper.getMainLooper()).post(() -> {
            if (listener != null) {
                listener.onDeviceListUpdated(deviceList);
            }
        });
    }

    public List<UsbDeviceInfo> getDeviceList() {
        return deviceList;
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
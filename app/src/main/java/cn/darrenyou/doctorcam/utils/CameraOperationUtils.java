package cn.darrenyou.doctorcam.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.util.Log;
import android.widget.Toast;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

/**
 * 相机操作工具类，提供相机相关的通用操作
 */
public class CameraOperationUtils {
    private static final String TAG = "CameraOperationUtils";
    
    /**
     * 保存当前相机图像
     * @param context 上下文
     * @param bitmap 要保存的图像
     * @return 是否保存成功
     */
    public static boolean saveCameraImage(Context context, Bitmap bitmap) {
        if (bitmap == null) {
            Toast.makeText(context, "无法获取图像", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        try {
            // 保存图片到相册
            StorageUtils.saveImageToGallery(context, bitmap);
            return true;
        } catch (Exception e) {
            Toast.makeText(context, "保存图像失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "保存图像失败", e);
            return false;
        }
    }
    
    /**
     * 安全释放相机资源
     * @param camera 要释放的相机对象
     */
    public static void safeReleaseCamera(UVCCamera camera) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.close();
                camera.destroy();
            } catch (final Exception e) {
                Log.e(TAG, "释放相机资源失败", e);
            }
        }
    }
    
    /**
     * 检查设备是否匹配指定的供应商ID和产品ID
     * @param device USB设备
     * @param vendorId 供应商ID
     * @param productId 产品ID
     * @return 是否匹配
     */
    public static boolean isDeviceMatch(UsbDevice device, int vendorId, int productId) {
        return device != null && device.getVendorId() == vendorId && device.getProductId() == productId;
    }
    
    /**
     * 创建USB设备监听器
     * @param context 上下文
     * @param listener 设备连接监听器
     * @return USB监视器
     */
    public static USBMonitor createUsbMonitor(Context context, USBMonitor.OnDeviceConnectListener listener) {
        return new USBMonitor(context, listener);
    }
}
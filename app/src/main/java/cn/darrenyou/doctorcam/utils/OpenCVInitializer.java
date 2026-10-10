package cn.darrenyou.doctorcam.utils;

import android.content.Context;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;

public class OpenCVInitializer {
    private static final String TAG = "OpenCVInitializer";
    private static volatile boolean isInitialized = false;

    /**
     * 初始化 OpenCV
     * @param context 上下文
     * @param callback 初始化回调
     */
    public static void initAsync(Context context, final OpenCVInitCallback callback) {
        // 如果已经初始化，直接返回成功
        if (isInitialized) {
            if (callback != null) {
                callback.onOpenCVInitialized(true);
            }
            return;
        }

        try {
            // 使用与仓内 Native SDK 配套的 OpenCV 4.2.0 Java 加载入口。
            boolean success = OpenCVLoader.initDebug();
            if (success) {
                isInitialized = true;

                // 输出 OpenCV 版本信息，便于调试
                String versionInfo = Core.getVersionString();
                String buildInfo = Core.getBuildInformation();
                Log.d(TAG, "OpenCV 初始化成功，版本: " + versionInfo);
                Log.d(TAG, "OpenCV 构建信息: " + buildInfo);

                if (callback != null) {
                    callback.onOpenCVInitialized(true);
                }
            } else {
                Log.e(TAG, "OpenCV 初始化失败");
                if (callback != null) {
                    callback.onOpenCVInitialized(false);
                }
            }
        } catch (LinkageError | Exception e) {
            Log.e(TAG, "OpenCV 初始化异常: " + e.getMessage());
            if (callback != null) {
                callback.onOpenCVInitialized(false);
            }
        }
    }

    /**
     * 检查 OpenCV 是否已初始化
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return isInitialized;
    }

    /**
     * OpenCV 初始化回调接口
     */
    public interface OpenCVInitCallback {
        void onOpenCVInitialized(boolean success);
    }
}

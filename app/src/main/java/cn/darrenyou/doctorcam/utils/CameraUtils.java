package cn.darrenyou.doctorcam.utils;

import android.util.Size;

public class CameraUtils {
    // 常用的预览尺寸
    private static final Size[] PREVIEW_SIZES = {
            new Size(1920, 1080), // 1080p
            new Size(1280, 720),  // 720p
            new Size(960, 720),   
            new Size(800, 600),
            new Size(640, 480),   // VGA
            new Size(352, 288),   // CIF
            new Size(320, 240)    // QVGA
    };

    public static Size getOptimalPreviewSize(int screenWidth, int screenHeight) {
        float targetRatio = (float) screenWidth / screenHeight;
        Size optimalSize = PREVIEW_SIZES[1]; // 默认使用720p
        float minDiff = Float.MAX_VALUE;

        // 在预定义的尺寸中找到最接近屏幕比例的尺寸
        for (Size size : PREVIEW_SIZES) {
            float ratio = (float) size.getWidth() / size.getHeight();
            float diff = Math.abs(ratio - targetRatio);
            
            if (diff < minDiff) {
                optimalSize = size;
                minDiff = diff;
            }
        }

        return optimalSize;
    }
}
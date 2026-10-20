package com.silicicsoft.doctorcam.utils;

import android.graphics.Bitmap;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.nio.ByteBuffer;

/** OpenCV Native 帧转换边界，实际摄像头与 Android 回归共用同一入口。 */
public final class ImageProcessingUtils {
    private static final String TAG = "ImageProcessingUtils";

    private ImageProcessingUtils() {}

    public static Bitmap yuv2BitmapNative(byte[] data, int width, int height) {
        if (data == null) return null;
        return getFrameAsBitmap(ByteBuffer.wrap(data), width, height);
    }

    public static Bitmap getFrameAsBitmap(ByteBuffer frame, int width, int height) {
        long pixelCount = (long) width * height;
        long requiredSize = pixelCount + pixelCount / 2;
        if (!OpenCVInitializer.isInitialized() || frame == null || width <= 0 || height <= 0
                || width % 2 != 0 || height % 2 != 0 || requiredSize > frame.remaining()) {
            return null;
        }
        Mat yuvMat = null;
        Mat rgbaMat = null;
        Bitmap bitmap = null;
        boolean converted = false;
        try {
            byte[] data = new byte[(int) requiredSize];
            frame.duplicate().get(data);
            yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
            yuvMat.put(0, 0, data);
            rgbaMat = new Mat();
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbaMat, bitmap);
            converted = true;
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "转换 NV21 帧为 Bitmap 失败", e);
            return null;
        } finally {
            if (yuvMat != null) yuvMat.release();
            if (rgbaMat != null) rgbaMat.release();
            if (!converted && bitmap != null) bitmap.recycle();
        }
    }
}

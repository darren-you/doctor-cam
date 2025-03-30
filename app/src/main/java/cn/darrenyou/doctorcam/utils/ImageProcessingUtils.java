package cn.darrenyou.doctorcam.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * 图像处理工具类，提供图像转换和预处理功能
 */
public class ImageProcessingUtils {
    private static final String TAG = "ImageProcessingUtils";
    
    /**
     * 将YUV420SP格式的数据转换为RGB位图
     */
    public static void decodeYUV420SP(int[] rgb, byte[] yuv420sp, int width, int height) {
        final int frameSize = width * height;

        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width;
            int u = 0, v = 0;

            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & yuv420sp[yp]) - 16;
                if (y < 0) y = 0;

                if ((i & 1) == 0) {
                    if (uvp < yuv420sp.length - 1) {
                        v = (0xff & yuv420sp[uvp++]) - 128;
                        u = (0xff & yuv420sp[uvp++]) - 128;
                    }
                }

                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                r = Math.min(Math.max(r, 0), 262143);
                g = Math.min(Math.max(g, 0), 262143);
                b = Math.min(Math.max(b, 0), 262143);

                rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }
    }
    
    /**
     * 使用OpenCV将YUV数据转换为Bitmap
     */
    public static Bitmap yuv2Bitmap(byte[] data, int width, int height) {
        // 创建YUV Mat
        Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        yuvMat.put(0, 0, data);
        
        // 创建RGB Mat
        Mat rgbMat = new Mat(height, width, CvType.CV_8UC3);
        
        // YUV转RGB
        Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_NV21);
        
        // 创建Bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgbMat, bitmap);
        
        // 释放Mat
        yuvMat.release();
        rgbMat.release();
        
        return bitmap;
    }

    /**
     * 使用OpenCV的本地方法加速YUV到Bitmap的转换
     * 相比于普通方法，这个实现避免了多余的内存拷贝，直接在本地代码中完成转换
     *
     * @param data YUV格式的图像数据
     * @param width 图像宽度
     * @param height 图像高度
     * @return 转换后的Bitmap
     */
    public static Bitmap yuv2BitmapNative(byte[] data, int width, int height) {
        if (!OpenCVInitializer.isInitialized()) {
            return yuv2Bitmap(data, width, height);
        }

        try {
            // 创建直接分配的ByteBuffer，避免Java堆内存和本地内存之间的拷贝
            java.nio.ByteBuffer directBuffer = java.nio.ByteBuffer.allocateDirect(data.length);
            directBuffer.put(data);
            directBuffer.rewind();

            // 使用OpenCV的本地内存创建Mat
            Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1, directBuffer);

            // 创建RGB Mat，使用连续内存
            Mat rgbMat = new Mat(height, width, CvType.CV_8UC4);

            // 使用OpenCV的本地方法进行颜色空间转换
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGBA_NV21);

            // 创建Bitmap并直接使用本地内存
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // 使用OpenCV的Utils类直接将Mat数据复制到Bitmap，避免额外的内存拷贝
            Utils.matToBitmap(rgbMat, bitmap);

            // 释放Mat资源
            yuvMat.release();
            rgbMat.release();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "本地YUV转换失败，回退到标准方法", e);
            return yuv2Bitmap(data, width, height);
        }
    }
    
    /**
     * 使用OpenCV对图像进行预处理
     */
    public static Bitmap preprocessImage(Bitmap input) {
        if (!OpenCVInitializer.isInitialized()) {
            return input;
        }

        try {
            Mat src = new Mat();
            Utils.bitmapToMat(input, src);

            // 降噪
            Mat denoised = new Mat();
            Imgproc.GaussianBlur(src, denoised, new org.opencv.core.Size(3, 3), 0);

            // 增强对比度
            Mat enhanced = new Mat();
            Core.normalize(denoised, enhanced, 0, 255, Core.NORM_MINMAX);

            // 转回 Bitmap
            Bitmap result = Bitmap.createBitmap(input.getWidth(), input.getHeight(), input.getConfig());
            Utils.matToBitmap(enhanced, result);

            // 释放资源
            src.release();
            denoised.release();
            enhanced.release();

            return result;
        } catch (Exception e) {
            Log.e(TAG, "图像预处理失败", e);
            return input;
        }
    }

        /**
     * 校正图像的色彩空间，确保与预览一致
     * @param bitmap 输入图像
     * @return 校正后的图像
     */
    public static Bitmap correctColorSpace(Bitmap bitmap) {
        if (bitmap == null) return null;
        
        // 如果OpenCV已初始化，使用OpenCV进行色彩空间转换
        if (OpenCVInitializer.isInitialized()) {
            // 使用OpenCV的Mat进行处理
            org.opencv.core.Mat mat = new org.opencv.core.Mat();
            org.opencv.android.Utils.bitmapToMat(bitmap, mat);
            
            // 转换色彩空间 - 从BGR转到RGB (OpenCV默认使用BGR)
            org.opencv.imgproc.Imgproc.cvtColor(mat, mat, org.opencv.imgproc.Imgproc.COLOR_BGR2RGB);
            
            // 调整色彩平衡
            org.opencv.core.Core.convertScaleAbs(mat, mat, 1.0, 0);
            
            // 将处理后的Mat转回Bitmap
            org.opencv.android.Utils.matToBitmap(mat, bitmap);
            mat.release();
        }
        
        return bitmap;
    }
    
    /**
     * 调整图像的色彩平衡
     * @param bitmap 输入图像
     * @return 调整后的图像
     */
    public static Bitmap adjustColorBalance(Bitmap bitmap) {
        if (bitmap == null) return null;
        
        // 创建一个新的Bitmap以避免修改原始Bitmap
        Bitmap result = bitmap.copy(bitmap.getConfig(), true);
        
        // 使用ColorMatrix调整色彩
        android.graphics.ColorMatrix colorMatrix = new android.graphics.ColorMatrix();
        
        // 调整RGB通道的增益 - 这些值需要根据实际情况调整
        // 例如，如果红色偏弱，增加红色通道的增益
        float[] matrix = {
            1.1f, 0, 0, 0, 0,    // 红色通道增益稍微提高
            0, 1.0f, 0, 0, 0,    // 绿色通道保持不变
            0, 0, 1.05f, 0, 0,   // 蓝色通道稍微提高
            0, 0, 0, 1.0f, 0     // alpha通道保持不变
        };
        colorMatrix.set(matrix);
        
        // 创建画布和画笔
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(colorMatrix));
        
        // 使用调整后的画笔绘制原始图像
        canvas.drawBitmap(bitmap, 0, 0, paint);
        
        return result;
    }
}

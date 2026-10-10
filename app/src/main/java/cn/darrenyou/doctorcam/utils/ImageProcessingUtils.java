package cn.darrenyou.doctorcam.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 图像处理工具类，提供图像转换和预处理功能
 */
public class ImageProcessingUtils {
    private static final String TAG = "ImageProcessingUtils";

    /**
     * 使用Java标准方法将YUV数据转换为Bitmap
     */
    public static Bitmap yuv2BitmapJava(byte[] data, int width, int height) {
        try {
            YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, out);
            byte[] imageBytes = out.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            out.close();
            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "Java YUV转换失败", e);
            return null;
        }
    }

    /**
     * 使用OpenCV的本地方法加速YUV到Bitmap的转换
     * 复用帧转换入口，NV21 按 RGBA 通道顺序写入 Bitmap。
     *
     * @param data YUV格式的图像数据
     * @param width 图像宽度
     * @param height 图像高度
     * @return 转换后的Bitmap
     */
    public static Bitmap yuv2BitmapNative(byte[] data, int width, int height) {
        if (data == null) {
            return null;
        }
        return getFrameAsBitmap(ByteBuffer.wrap(data), width, height);
    }


    /**
     * 颜色校正方法，用于修复颜色问题
     * 
     * @param input 输入位图
     * @return 颜色校正后的位图
     */
    public static Bitmap correctColors(Bitmap input) {
        if (input == null) return null;
        if (!OpenCVInitializer.isInitialized()) return input;
        
        try {
            // 转换为Mat
            Mat srcMat = new Mat();
            Utils.bitmapToMat(input, srcMat);
            
            // 创建输出Mat
            Mat dstMat = new Mat();
            
            // 交换R和B通道
            List<Mat> channels = new ArrayList<>();
            Core.split(srcMat, channels);
            
            // 确保有足够的通道
            if (channels.size() >= 3) {
                Mat temp = channels.get(0);
                channels.set(0, channels.get(2));
                channels.set(2, temp);
                
                Core.merge(channels, dstMat);
                
                // 转回Bitmap
                Bitmap output = Bitmap.createBitmap(input.getWidth(), input.getHeight(), input.getConfig());
                Utils.matToBitmap(dstMat, output);
                
                // 释放资源
                srcMat.release();
                dstMat.release();
                for (Mat m : channels) {
                    m.release();
                }
                
                return output;
            }
            
            // 释放资源
            srcMat.release();
            dstMat.release();
            for (Mat m : channels) {
                m.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "颜色校正失败", e);
        }
        
        return input;
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

    public static Bitmap getFrameAsBitmap(ByteBuffer frame, int width, int height) {
        long requiredSize = (long) width * height * 3 / 2;
        if (!OpenCVInitializer.isInitialized() || frame == null || width <= 0 || height <= 0
                || width % 2 != 0 || height % 2 != 0 || requiredSize > frame.remaining()) {
            return null;
        }
        Mat yuvMat = null;
        Mat rgbaMat = null;
        try {
            // 不消费 UVC 回调借出的 Buffer；每次使用独立视图读取当前帧。
            byte[] data = new byte[(int) requiredSize];
            frame.duplicate().get(data);
            yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
            yuvMat.put(0, 0, data);
            rgbaMat = new Mat();
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgbaMat, bitmap);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "转换 NV21 帧为 Bitmap 失败", e);
            return null;
        } finally {
            if (yuvMat != null) {
                yuvMat.release();
            }
            if (rgbaMat != null) {
                rgbaMat.release();
            }
        }
    }

    public static byte[] bitmapToByteArray(Bitmap bitmap) {
        // 将Bitmap转换为字节数组保存
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
        return stream.toByteArray();
    }
}

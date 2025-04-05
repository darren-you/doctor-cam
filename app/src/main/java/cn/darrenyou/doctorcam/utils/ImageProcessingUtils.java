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
     * 相比于普通方法，这个实现避免了多余的内存拷贝，直接在本地代码中完成转换
     *
     * @param data YUV格式的图像数据
     * @param width 图像宽度
     * @param height 图像高度
     * @return 转换后的Bitmap
     */
    public static Bitmap yuv2BitmapNative(byte[] data, int width, int height) {
        if (!OpenCVInitializer.isInitialized()) {
            return yuv2BitmapJava(data, width, height);
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
            
            // 修复颜色通道顺序问题 - 将RGBA转换为正确的顺序
            // OpenCV使用BGRA顺序，需要转换为RGBA
            Mat fixedMat = new Mat(height, width, CvType.CV_8UC4);
            byte[] rgba = new byte[4];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double[] pixel = rgbMat.get(y, x);
                    rgba[0] = (byte)pixel[2]; // R (原B通道)
                    rgba[1] = (byte)pixel[1]; // G
                    rgba[2] = (byte)pixel[0]; // B (原R通道)
                    rgba[3] = (byte)pixel[3]; // A
                    fixedMat.put(y, x, rgba);
                }
            }

            // 创建Bitmap并直接使用本地内存
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // 使用OpenCV的Utils类直接将Mat数据复制到Bitmap，避免额外的内存拷贝
            Utils.matToBitmap(fixedMat, bitmap);

            // 释放Mat资源
            yuvMat.release();
            rgbMat.release();
            fixedMat.release();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "本地YUV转换失败，回退到标准方法", e);
            return yuv2BitmapJava(data, width, height);
        }
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
}

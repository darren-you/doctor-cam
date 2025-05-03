package cn.darrenyou.doctorcam.utils;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import java.util.List;

import cn.darrenyou.doctorcam.BuildConfig;
import cn.darrenyou.doctorcam.base.BaseDetection;
import cn.darrenyou.doctorcam.onnx.OnnxYOLODetector;

/**
 * 检测结果绘制工具类
 */
public class DetectionUtils {
    private static final String TAG = "DetectionUtils";

    // 定义类别颜色
    private static final int[] COLORS = new int[] {
            Color.rgb(255, 0, 0),    // 红色 - 类别0
            Color.rgb(0, 255, 0),    // 绿色 - 类别1
            Color.rgb(0, 0, 255),    // 蓝色 - 类别2
            Color.rgb(255, 255, 0),  // 黄色 - 类别3
            Color.rgb(255, 0, 255)   // 紫色 - 类别4
    };

    /**
     * 在画布上绘制检测结果
     * @param canvas 画布
     * @param detections 检测结果列表
     * @param scaleX X轴缩放比例
     * @param scaleY Y轴缩放比例
     */
    public static void drawDetections(Canvas canvas, List<BaseDetection> detections, float scaleX, float scaleY) {
        // 设置绘制参数
        Paint boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);

        Paint textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);

        // 绘制每个检测结果
        for (BaseDetection detection : detections) {
            // 获取类别索引
            int classIndex = 0;
            for (int i = 0; i < OnnxYOLODetector.CLASSES.length; i++) {
                if (OnnxYOLODetector.CLASSES[i].equals(detection.className)) {
                    classIndex = i;
                    break;
                }
            }

            // 设置颜色
            int color = COLORS[classIndex % COLORS.length];
            boxPaint.setColor(color);
            textBgPaint.setColor(color);

            // 将原始图像坐标映射到屏幕坐标
            RectF originalBox = detection.bbox;
            RectF scaledBox = new RectF(
                    originalBox.left * scaleX,
                    originalBox.top * scaleY,
                    originalBox.right * scaleX,
                    originalBox.bottom * scaleY
            );
            
            if (BuildConfig.DEBUG) {
                // 记录坐标映射信息
                Log.d(TAG, "坐标映射: 原始坐标=" + originalBox.left + "," + originalBox.top + "," + 
                    originalBox.right + "," + originalBox.bottom + 
                    ", 映射坐标=" + scaledBox.left + "," + scaledBox.top + "," + 
                    scaledBox.right + "," + scaledBox.bottom);

                // 记录绘制信息
                Log.d(TAG, "绘制检测框: " + detection.className +
                        " (" + detection.confidence + ") 在 " +
                        scaledBox.left + "," + scaledBox.top + "," +
                        scaledBox.right + "," + scaledBox.bottom);
            }

            // 绘制边界框
            canvas.drawRect(scaledBox, boxPaint);

            // 准备标签文本
            String labelText = String.format("%s %.2f", detection.className, detection.confidence);

            // 测量文本尺寸
            float textWidth = textPaint.measureText(labelText);
            float textHeight = textPaint.getTextSize();

            // 绘制标签背景
            canvas.drawRect(
                    scaledBox.left,
                    scaledBox.top - textHeight - 4,
                    scaledBox.left + textWidth + 8,
                    scaledBox.top,
                    textBgPaint
            );

            // 绘制标签文本
            canvas.drawText(
                    labelText,
                    scaledBox.left + 4,
                    scaledBox.top - 4,
                    textPaint
            );
        }
    }

    /**
     * 在位图上绘制检测结果
     * @param canvas 画布
     * @param detections 检测结果列表
     */
    public static void drawDetectionsOnBitmap(Canvas canvas, List<BaseDetection> detections) {
        // 设置绘制参数
        Paint boxPaint = new Paint();
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);

        Paint textBgPaint = new Paint();
        textBgPaint.setStyle(Paint.Style.FILL);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36);

        for (BaseDetection detection : detections) {
            // 获取类别索引
            int classIndex = 0;
            for (int i = 0; i < OnnxYOLODetector.CLASSES.length; i++) {
                if (OnnxYOLODetector.CLASSES[i].equals(detection.className)) {
                    classIndex = i;
                    break;
                }
            }

            // 设置颜色
            int color = COLORS[classIndex % COLORS.length];
            boxPaint.setColor(color);
            textBgPaint.setColor(color);

            // 绘制边界框
            canvas.drawRect(detection.bbox, boxPaint);

            // 准备标签文本
            String labelText = String.format("%s %.2f", detection.className, detection.confidence);

            // 测量文本尺寸
            float textWidth = textPaint.measureText(labelText);
            float textHeight = textPaint.getTextSize();

            // 绘制标签背景
            canvas.drawRect(
                    detection.bbox.left,
                    detection.bbox.top - textHeight - 4,
                    detection.bbox.left + textWidth + 8,
                    detection.bbox.top,
                    textBgPaint
            );

            // 绘制标签文本
            canvas.drawText(
                    labelText,
                    detection.bbox.left + 4,
                    detection.bbox.top - 4,
                    textPaint
            );
        }
    }

    /**
     * 获取检测类别对应的颜色
     * @param className 类别名称
     * @return 颜色值
     */
    public static int getColorForClass(String className) {
        int classIndex = 0;
        for (int i = 0; i < OnnxYOLODetector.CLASSES.length; i++) {
            if (OnnxYOLODetector.CLASSES[i].equals(className)) {
                classIndex = i;
                break;
            }
        }
        return COLORS[classIndex % COLORS.length];
    }
}
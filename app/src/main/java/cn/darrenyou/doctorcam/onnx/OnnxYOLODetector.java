package cn.darrenyou.doctorcam.onnx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public class OnnxYOLODetector {
    private static final String TAG = "OnnxDetectionLog";
    private static final String MODEL_FILE = "onnx/best_320x320.onnx";
    private static final int INPUT_SIZE = 320;
    public static final String[] CLASSES = new String[]{"声门裂", "杓状软骨", "混合识别"};
    private static float CONFIDENCE_THRESHOLD = 0.1f; // 置信度阈值
    private static final float IOU_THRESHOLD = 0.6f; // NMS 的 IOU 阈值

    private final OrtEnvironment env;
    private final OrtSession session;

    // 保存letterbox参数，用于坐标转换
    private float scale;
    private float offsetX;
    private float offsetY;

    public OnnxYOLODetector(Context context) throws IOException, OrtException {
        Log.d(TAG, "开始初始化YOLO检测器");

        // 创建ONNX Runtime环境
        env = OrtEnvironment.getEnvironment();
        Log.d(TAG, "ONNX Runtime环境创建成功");

        // 从assets加载模型
        Log.d(TAG, "尝试从assets加载模型: " + MODEL_FILE);
        InputStream modelInputStream = context.getAssets().open(MODEL_FILE);
        byte[] modelBytes = new byte[modelInputStream.available()];
        modelInputStream.read(modelBytes);
        modelInputStream.close();
        Log.d(TAG, "模型文件加载成功，大小: " + modelBytes.length + " 字节");

        // 创建会话
        Log.d(TAG, "创建ONNX会话...");
        session = env.createSession(modelBytes);
        Log.d(TAG, "ONNX会话创建成功");

        // 打印模型输入输出信息
        StringBuilder inputNames = new StringBuilder();
        for (String inputName : session.getInputNames()) {
            inputNames.append(inputName).append(", ");
        }
        Log.d(TAG, "模型输入: " + inputNames);

        StringBuilder outputNames = new StringBuilder();
        for (String outputName : session.getOutputNames()) {
            outputNames.append(outputName).append(", ");
        }
        Log.d(TAG, "模型输出: " + outputNames);
    }

    public void close() {
        try {
            if (session != null) {
                session.close();
            }
            if (env != null) {
                env.close();
            }
        } catch (OrtException e) {
            Log.e(TAG, "关闭ONNX会话时出错", e);
        }
    }

    public List<OnnxDetection> detect(Bitmap bitmap) {
        Log.d(TAG, "开始检测，输入图像尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        // 保存原始图像尺寸，用于后处理
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();

        // 预处理图像
        float[] input = preprocessImage(bitmap);

        List<OnnxDetection> detections = new ArrayList<>();

        try {
            // 创建输入张量
            long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);

            // 运行推理
            Log.d(TAG, "开始模型推理...");
            OrtSession.Result result = session.run(Collections.singletonMap("images", inputTensor));

            Log.d(TAG, "模型推理完成，开始处理输出...");
            Log.d(TAG, "输出数量: " + result.size());

            // 处理输出
            Object outputObj = result.get(0).getValue();
            Log.d(TAG, "输出类型: " + (outputObj != null ? outputObj.getClass().getName() : "null"));

            if (outputObj instanceof float[][][]) {
                float[][][] outputArray = (float[][][]) outputObj;
                Log.d(TAG, "输出维度: [" + outputArray.length + ", " +
                        (outputArray.length > 0 ? outputArray[0].length : 0) + ", " +
                        (outputArray.length > 0 && outputArray[0].length > 0 ? outputArray[0][0].length : 0) + "]");

                // 记录letterbox参数，用于坐标转换
                Log.d(TAG, "Letterbox参数: scale=" + scale + ", offsetX=" + offsetX + ", offsetY=" + offsetY);

                // 处理YOLOv5输出格式 [1, 6300, 8]
                // 其中6300是检测框数量，8是每个框的数据 (x, y, w, h, conf, class1, class2, ...)
                int numBoxes = outputArray[0].length;
                int numClasses = CLASSES.length;

                // 创建一个数组来存储所有检测结果
                List<float[]> validDetections = new ArrayList<>();

                // 第一步：收集所有有效的检测结果
                for (int i = 0; i < numBoxes; i++) {
                    float[] detection = outputArray[0][i];

                    // 获取置信度 (第5个元素)
                    float confidence = detection[4];

                    if (confidence > CONFIDENCE_THRESHOLD) {
                        // 找出最高类别置信度和索引
                        float maxClassConf = 0;
                        int classIndex = 0;

                        for (int j = 0; j < numClasses && j + 5 < detection.length; j++) {
                            float score = detection[5 + j];
                            if (score > maxClassConf) {
                                maxClassConf = score;
                                classIndex = j;
                            }
                        }

                        // 计算最终置信度
                        float finalConfidence = confidence * maxClassConf;

                        if (finalConfidence > CONFIDENCE_THRESHOLD) {
                            // 创建一个新的数组来存储检测结果
                            // [x, y, w, h, confidence, class_index, final_confidence]
                            float[] validDetection = new float[7];
                            validDetection[0] = detection[0]; // x
                            validDetection[1] = detection[1]; // y
                            validDetection[2] = detection[2]; // w
                            validDetection[3] = detection[3]; // h
                            validDetection[4] = confidence;   // obj confidence
                            validDetection[5] = classIndex;   // class index
                            validDetection[6] = finalConfidence; // final confidence

                            // 将有效检测添加到列表中
                            validDetections.add(validDetection);

                            Log.d(TAG, "有效检测: 类别=" + CLASSES[classIndex] +
                                    ", 置信度=" + finalConfidence +
                                    ", 原始坐标=[" + detection[0] + ", " + detection[1] +
                                    ", " + detection[2] + ", " + detection[3] + "]");
                        }
                    }
                }

                Log.d(TAG, "有效检测数量: " + validDetections.size());

                // 第二步：应用非极大值抑制 (NMS)
                // 这里简化实现，实际应该使用更高效的算法
                List<Integer> keepIndices = new ArrayList<>();
                boolean[] suppressed = new boolean[validDetections.size()];

                // 按置信度排序
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    validDetections.sort((a, b) -> Float.compare(b[6], a[6]));
                }

                for (int i = 0; i < validDetections.size(); i++) {
                    if (suppressed[i]) continue;

                    keepIndices.add(i);

                    float[] a = validDetections.get(i);
                    float ax1 = a[0] - a[2] / 2;
                    float ay1 = a[1] - a[3] / 2;
                    float ax2 = a[0] + a[2] / 2;
                    float ay2 = a[1] + a[3] / 2;
                    float aArea = a[2] * a[3];

                    for (int j = i + 1; j < validDetections.size(); j++) {
                        if (suppressed[j]) continue;

                        // 如果类别不同，不抑制
                        if (validDetections.get(j)[5] != a[5]) continue;

                        float[] b = validDetections.get(j);
                        float bx1 = b[0] - b[2] / 2;
                        float by1 = b[1] - b[3] / 2;
                        float bx2 = b[0] + b[2] / 2;
                        float by2 = b[1] + b[3] / 2;
                        float bArea = b[2] * b[3];

                        // 计算交集
                        float x1 = Math.max(ax1, bx1);
                        float y1 = Math.max(ay1, by1);
                        float x2 = Math.min(ax2, bx2);
                        float y2 = Math.min(ay2, by2);

                        float w = Math.max(0, x2 - x1);
                        float h = Math.max(0, y2 - y1);
                        float intersectionArea = w * h;

                        // 计算IoU
                        float unionArea = aArea + bArea - intersectionArea;
                        float iou = intersectionArea / unionArea;

                        if (iou > IOU_THRESHOLD) { // NMS阈值
                            suppressed[j] = true;
                        }
                    }
                }

                Log.d(TAG, "NMS后保留的检测数量: " + keepIndices.size());

                // 第三步：转换为最终的检测结果
                for (int idx : keepIndices) {
                    float[] det = validDetections.get(idx);

                    // 获取边界框坐标 (YOLO格式是中心点x,y,宽度,高度)
                    float x = det[0];
                    float y = det[1];
                    float w = det[2];
                    float h = det[3];
                    int classIndex = (int) det[5];
                    float confidence = det[6];

                    // 转换为左上角和右下角坐标
                    float x1 = x - w / 2;
                    float y1 = y - h / 2;
                    float x2 = x + w / 2;
                    float y2 = y + h / 2;

                    // 添加更多日志，记录转换前的坐标
                    Log.d(TAG, "转换前坐标: x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2 +
                            ", scale=" + scale + ", offsetX=" + offsetX + ", offsetY=" + offsetY);

                    // 从模型输出空间转换回原始图像空间
                    // 首先，从letterbox坐标转换回未缩放的坐标
                    x1 = (x1 - offsetX) / scale;
                    y1 = (y1 - offsetY) / scale;
                    x2 = (x2 - offsetX) / scale;
                    y2 = (y2 - offsetY) / scale;

                    // 添加更多日志，记录转换后的坐标
                    Log.d(TAG, "转换后坐标: x1=" + x1 + ", y1=" + y1 + ", x2=" + x2 + ", y2=" + y2);

                    // 裁剪到图像边界
                    x1 = Math.max(0, Math.min(x1, originalWidth));
                    y1 = Math.max(0, Math.min(y1, originalHeight));
                    x2 = Math.max(0, Math.min(x2, originalWidth));
                    y2 = Math.max(0, Math.min(y2, originalHeight));

                    // 创建边界框
                    RectF bbox = new RectF(x1, y1, x2, y2);

                    // 添加检测结果，包含原始图像尺寸信息
                    detections.add(new OnnxDetection(confidence, bbox, CLASSES[classIndex], originalWidth, originalHeight));

                    Log.d(TAG, "最终检测结果: 类别=" + CLASSES[classIndex] +
                            ", 置信度=" + confidence +
                            ", 位置=[" + x1 + ", " + y1 + ", " + x2 + ", " + y2 + "]" +
                            ", 原始图像尺寸=[" + originalWidth + ", " + originalHeight + "]");
                }
            } else {
                Log.e(TAG, "不支持的输出类型: " + (outputObj != null ? outputObj.getClass().getName() : "null"));
            }

            Log.d(TAG, "检测完成，共找到 " + detections.size() + " 个物体");

            // 释放资源
            inputTensor.close();
            result.close();

        } catch (Exception e) {
            Log.e(TAG, "检测过程中发生错误", e);
            e.printStackTrace();
        }

        return detections;
    }

    private float[] preprocessImage(Bitmap bitmap) {
        Log.d(TAG, "开始预处理图像...");

        // 使用letterbox调整大小，保持宽高比
        Bitmap resizedBitmap;

        if (bitmap.getWidth() != INPUT_SIZE || bitmap.getHeight() != INPUT_SIZE) {
            // 计算缩放比例 - 与YOLOv5的letterbox函数保持一致
            scale = Math.min(
                    (float)INPUT_SIZE / bitmap.getWidth(),
                    (float)INPUT_SIZE / bitmap.getHeight()
            );

            // 计算新尺寸
            int newWidth = Math.round(bitmap.getWidth() * scale);
            int newHeight = Math.round(bitmap.getHeight() * scale);

            // 添加日志输出缩放信息
            Log.d(TAG, "Letterbox缩放: 原始尺寸=" + bitmap.getWidth() + "x" + bitmap.getHeight() +
                    ", 缩放比例=" + scale + ", 缩放后尺寸=" + newWidth + "x" + newHeight);

            // 调整大小
            resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);

            // 创建一个新的320x320位图，灰色背景
            Bitmap paddedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
            paddedBitmap.eraseColor(Color.rgb(114, 114, 114)); // 设置灰色背景，与YOLOv5默认一致

            // 将调整大小的图像绘制到中心
            Canvas canvas = new Canvas(paddedBitmap);
            offsetX = (INPUT_SIZE - newWidth) / 2.0f;
            offsetY = (INPUT_SIZE - newHeight) / 2.0f;

            // 记录填充信息
            Log.d(TAG, "Letterbox填充: 左=" + offsetX + ", 上=" + offsetY);

            canvas.drawBitmap(resizedBitmap, offsetX, offsetY, null);

            // 如果创建了新的位图，回收旧的
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle();
            }

            resizedBitmap = paddedBitmap;
        } else {
            resizedBitmap = bitmap;
            scale = 1.0f;
            offsetX = 0.0f;
            offsetY = 0.0f;
        }

        // 调整大小后的图像
        Log.d(TAG, "图像已调整大小为: " + resizedBitmap.getWidth() + "x" + resizedBitmap.getHeight());

        // 获取中心点像素值，用于调试
        int centerX = resizedBitmap.getWidth() / 2;
        int centerY = resizedBitmap.getHeight() / 2;
        int centerPixel = resizedBitmap.getPixel(centerX, centerY);
        float r = Color.red(centerPixel);
        float g = Color.green(centerPixel);
        float b = Color.blue(centerPixel);
        Log.d(TAG, "像素样本值: 中心点=(" + centerX + ", " + centerY + "), 中心点RGB=(" + r + ", " + g + ", " + b + ")");

        // 转换为浮点数组，格式为 [1, 3, 320, 320]
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resizedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        // 创建输入数组
        float[] input = new float[3 * INPUT_SIZE * INPUT_SIZE];

        // 填充输入数组，转换为RGB格式并归一化
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            // 获取RGB值
            float r_value = Color.red(pixel) / 255.0f;
            float g_value = Color.green(pixel) / 255.0f;
            float b_value = Color.blue(pixel) / 255.0f;

            // 填充到输入数组，使用CHW格式 (通道,高度,宽度)
            int y = i / INPUT_SIZE;
            int x = i % INPUT_SIZE;

            // RGB通道
            input[0 * INPUT_SIZE * INPUT_SIZE + y * INPUT_SIZE + x] = r_value;
            input[1 * INPUT_SIZE * INPUT_SIZE + y * INPUT_SIZE + x] = g_value;
            input[2 * INPUT_SIZE * INPUT_SIZE + y * INPUT_SIZE + x] = b_value;
        }

        Log.d(TAG, "图像预处理完成，输入形状: [1, 3, " + INPUT_SIZE + ", " + INPUT_SIZE + "]");

        // 如果创建了新的位图，回收
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle();
        }

        return input;
    }
}
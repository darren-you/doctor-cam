package cn.darrenyou.doctorcam.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ObjectDetector {
    private static final String TAG = "ObjectDetector";
    private static final String MODEL_PATH = "detect.tflite";
    private static final String LABEL_PATH = "labelmap.txt";
    private static final int INPUT_SIZE = 300;
    private static final float THRESHOLD = 0.5f;

    private final Interpreter tflite;
    private final List<String> labels;
    private final ImageProcessor imageProcessor;
    private ByteBuffer inputBuffer;
    private final float[][][] outputLocations;
    private final float[][] outputClasses;
    private final float[][] outputScores;
    private final float[] numDetections;

    public ObjectDetector(Context context) throws IOException {
        // 加载模型
        MappedByteBuffer model = FileUtil.loadMappedFile(context, MODEL_PATH);
        Interpreter.Options options = new Interpreter.Options();
        tflite = new Interpreter(model, options);

        // 加载标签
        labels = FileUtil.loadLabels(context, LABEL_PATH);

        // 初始化图像处理器
        imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .build();

        // 初始化输出数组
        outputLocations = new float[1][10][4];
        outputClasses = new float[1][10];
        outputScores = new float[1][10];
        numDetections = new float[1];
    }

    public List<DetectionResult> detect(Bitmap bitmap) {
        Log.d(TAG, "开始物体检测");
        // 预处理图像
        TensorImage tensorImage = TensorImage.fromBitmap(bitmap);
        tensorImage = imageProcessor.process(tensorImage);

        // 运行推理
        long startTime = SystemClock.uptimeMillis();
        Object[] inputArray = {tensorImage.getBuffer()};
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputLocations);
        outputMap.put(1, outputClasses);
        outputMap.put(2, outputScores);
        outputMap.put(3, numDetections);
        tflite.runForMultipleInputsOutputs(inputArray, outputMap);
        long endTime = SystemClock.uptimeMillis();
        Log.d(TAG, "推理耗时: " + (endTime - startTime) + "ms");

        // 处理结果
        List<DetectionResult> results = new ArrayList<>();
        for (int i = 0; i < numDetections[0]; i++) {
            if (outputScores[0][i] > THRESHOLD) {
                RectF location = new RectF(
                    outputLocations[0][i][1] * bitmap.getWidth(),
                    outputLocations[0][i][0] * bitmap.getHeight(),
                    outputLocations[0][i][3] * bitmap.getWidth(),
                    outputLocations[0][i][2] * bitmap.getHeight()
                );
                String label = labels.get((int) outputClasses[0][i]);
                results.add(new DetectionResult(location, label, outputScores[0][i]));
                Log.d(TAG, String.format("检测到物体: %s, 置信度: %.2f", label, outputScores[0][i]));
            }
        }
        Log.d(TAG, "检测完成，共检测到 " + results.size() + " 个物体");
        return results;
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
        }
    }
}
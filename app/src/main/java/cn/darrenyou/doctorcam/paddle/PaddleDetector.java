package cn.darrenyou.doctorcam.paddle;

import android.graphics.Bitmap;

public class PaddleDetector {
    static {
        System.loadLibrary("PaddleDetector");
    }

    private long ctx = 0;

    public synchronized boolean init(String modelDir,
                        String labelPath,
                        int cpuThreadNum,
                        String cpuPowerMode,
                        int inputWidth,
                        int inputHeight,
                        float[] inputMean,
                        float[] inputStd,
                        float scoreThreshold) {
        release();
        ctx = nativeInit(
                modelDir,
                labelPath,
                cpuThreadNum,
                cpuPowerMode,
                inputWidth,
                inputHeight,
                inputMean,
                inputStd,
                scoreThreshold);
        return ctx != 0;
    }

    public synchronized boolean initDefault(String modeFilePath, String labelFilePath, String cpuPowerMode) {
        float[] inputMean = {0.485F, 0.456F, 0.406F};
        float[] inputStd = {0.229F, 0.224F, 0.225F};
        release();
        ctx = nativeInit(
                modeFilePath,
                labelFilePath,
                1,
                cpuPowerMode,
                320,
                320,
                inputMean,
                inputStd,
                0.5F);
        return ctx != 0;
    }

    public synchronized boolean release() {
        if (ctx == 0) {
            return false;
        }
        long context = ctx;
        ctx = 0;
        return nativeRelease(context);
    }

    /** 返回实际创建预测器的 Native 运行库版本，不使用构建文件中的声明值。 */
    public synchronized String getRuntimeVersion() {
        if (ctx == 0) {
            throw new IllegalStateException("Paddle 会话未初始化或已释放");
        }
        String version = nativeGetRuntimeVersion(ctx);
        if (version == null || version.isEmpty()) {
            throw new IllegalStateException("Paddle Native 运行库未返回版本");
        }
        return version;
    }

    public synchronized boolean process(Bitmap ARGB8888ImageBitmap, String savedImagePath) {
        if (ctx == 0) {
            return false;
        }
        // ARGB8888 bitmap is only supported in native, other color formats can be added by yourself.
        return nativeProcess(ctx, ARGB8888ImageBitmap, savedImagePath);
    }

    /**
     * 推理，以数组返回结果
     * [label_index, prob, x, y, w, h]
     * @param ARGB8888ImageBitmap
     * @return
     */
    public synchronized float[][] processWithResults(Bitmap ARGB8888ImageBitmap) {
        if (ctx == 0) {
            return new float[0][];
        }
        float[][] results = nativeProcessWithResults(ctx, ARGB8888ImageBitmap);
        if (results == null) {
            throw new IllegalStateException("Native 推理未返回有效结果");
        }
        return results;
    }

    private static native float[][] nativeProcessWithResults(long ctx, Bitmap ARGB8888ImageBitmap);

    private static native String nativeGetRuntimeVersion(long ctx);

    

    public static native long nativeInit(String modelDir,
                                         String labelPath,
                                         int cpuThreadNum,
                                         String cpuPowerMode,
                                         int inputWidth,
                                         int inputHeight,
                                         float[] inputMean,
                                         float[] inputStd,
                                         float scoreThreshold);

    public static native boolean nativeRelease(long ctx);

    public static native boolean nativeProcess(long ctx, Bitmap ARGB888ImageBitmap, String savedImagePath);
}

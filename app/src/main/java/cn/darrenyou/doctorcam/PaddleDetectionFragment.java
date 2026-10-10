package cn.darrenyou.doctorcam;

import static cn.darrenyou.doctorcam.utils.FileUtils.copyAssetFile;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.darrenyou.doctorcam.base.BaseDetection;
import cn.darrenyou.doctorcam.databinding.FragmentPaddleDetectionBinding;
import cn.darrenyou.doctorcam.paddle.PaddleDetector;
import cn.darrenyou.doctorcam.utils.CameraUtils;
import cn.darrenyou.doctorcam.utils.DetectionUtils;
import cn.darrenyou.doctorcam.utils.ImageProcessingUtils;
import cn.darrenyou.doctorcam.utils.OpenCVInitializer;
import cn.darrenyou.doctorcam.utils.StorageUtils;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;

public class PaddleDetectionFragment extends Fragment {

    private static final String TAG = "PaddleDetectionFragment";
    // 用于计时
    private long inferenceStartTime = 0; // 推理开始时间
    private int frameCount = 0; // 帧计数器
    private long lastFpsTimestamp = 0; // 上一次计算FPS的时间戳

    private long totalInferenceTime = 0; // 总推理时间
    private long inferenceCount = 0;     // 推理次数计数器

    private long totalLatencyTime = 0;   // 总延迟时间（从捕获到显示）
    private long latencyCount = 0;       // 延迟计数器
    private long minInferenceTime = Long.MAX_VALUE; // 单帧推理最短时间
    private long maxInferenceTime = 0;              // 单帧推理最长时间
    private long lastResetTimestamp = 0;            // 上次重置时间戳



    // 用于计时

    private FragmentPaddleDetectionBinding binding;
    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private USBMonitor.UsbControlBlock cameraControlBlock;
    private Surface surface;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean viewActive;
    private boolean permissionRequested;
    private volatile boolean previewRunning;
    private volatile int previewWidth;
    private volatile int previewHeight;

    private int deviceId;
    private int vendorId;
    private int productId;

    // Paddle检测器
    private volatile PaddleDetector paddleDetector;
    private Map<Integer, String> labelMap = new HashMap<>();

    private Handler uiHandler;
    private Paint boxPaint;
    private Paint textPaint;

    // 直接绘制模式
    private SurfaceHolder overlayHolder;

    volatile byte[] lastFrameData;
    volatile float[][] lastDetectionResults;
    private final Object frameLock = new Object();

    // 修改后的帧回调处理
    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            PaddleDetector detector = paddleDetector;
            if (!previewRunning || detector == null) {
                return;
            }

            int width = previewWidth;
            int height = previewHeight;
            long currentFrameStartTime = System.currentTimeMillis();

            // 复制帧数据
            byte[] data = new byte[frame.remaining()];
            frame.duplicate().get(data);

            try {
                // 直接获取Bitmap格式的帧数据
                Bitmap bitmap = ImageProcessingUtils.getFrameAsBitmap(frame, width, height);
                if (bitmap == null) {
                    return;
                }

                // 推理开始时间
                inferenceStartTime = System.currentTimeMillis();

                // 插入：推理前的内存使用情况（转换为MB）
                long beforeMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
                Log.e("MemoryUsage", "推理前内存使用: " + beforeMemory + " MB");

                // 使用Paddle进行推理
                try {
                    float[][] detectionResults = detector.processWithResults(bitmap);
                    synchronized (frameLock) {
                        lastFrameData = data;
                        lastDetectionResults = detectionResults;
                    }

                    // 推理结束时间
                    long inferenceEndTime = System.currentTimeMillis();
                    long inferenceDuration = inferenceEndTime - inferenceStartTime;

                    // 插入：推理后的内存使用情况（转换为MB）
                    long afterMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
                    Log.d("MemoryUsage", "推理后内存使用: " + afterMemory + " MB");

                    // 更新极值
                    minInferenceTime = Math.min(minInferenceTime, inferenceDuration);
                    maxInferenceTime = Math.max(maxInferenceTime, inferenceDuration);

                    // 累积推理时间
                    totalInferenceTime += inferenceDuration;
                    inferenceCount++;

                    Log.d("PaddleDetectionTime", "单帧推理耗时: " + inferenceDuration + " ms");

                    // 打印平均推理耗时（每100帧打印一次）
                    if (inferenceCount % 100 == 0) {
                        long avgInferenceTime = totalInferenceTime / inferenceCount;
                        Log.w("PaddleDetectionTime", "平均推理耗时: " + avgInferenceTime + " ms");
                    }

                    // 将结果转换为List<BaseDetection>格式
                    List<BaseDetection> detections = new ArrayList<>();
                    for (float[] result : detectionResults) {
                        int labelIndex = (int)result[0];
                        float confidence = result[1];
                        float x = result[2];
                        float y = result[3];
                        float w = result[4];
                        float h = result[5];

                        RectF bbox = new RectF(x, y, x + w, y + h);
                        detections.add(new BaseDetection(confidence, bbox,
                                getLabelName(labelIndex), width, height));
                    }

                    // 在UI线程中绘制检测结果
                    if (uiHandler != null) {
                        uiHandler.post(() -> {
                            if (binding != null && previewRunning) {
                                binding.captureButton.setEnabled(true);
                                drawDetectionResults(detections, currentFrameStartTime);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "处理帧时出错", e);
                } finally {
                    bitmap.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "处理摄像头帧时出错", e);
            }
        }
    };

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentPaddleDetectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    private Handler statsHandler = new Handler(Looper.getMainLooper());
    private Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            if (minInferenceTime != Long.MAX_VALUE && maxInferenceTime > 0) {
                Log.e("PaddleDetectionTime", "30秒内单帧推理最短耗时: " + minInferenceTime + " ms");
                Log.e("PaddleDetectionTime", "30秒内单帧推理最长耗时: " + maxInferenceTime + " ms");
            }

            // 重置极值
            minInferenceTime = Long.MAX_VALUE;
            maxInferenceTime = 0;

            // 每30秒执行一次
            statsHandler.postDelayed(this, 1000 * 30);
        }
    };


    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        permissionRequested = false;

        // 设置屏幕方向和系统UI
        setupScreenAndSystemUI();

        // 返回不依赖模型或相机初始化成功。
        binding.captureButton.setEnabled(false);
        setupButtons();

        // 获取设备参数
        if (getArguments() != null) {
            deviceId = getArguments().getInt("deviceId");
            vendorId = getArguments().getInt("vendorId");
            productId = getArguments().getInt("productId");
        }

        // 初始化UI线程Handler
        uiHandler = new Handler(requireActivity().getMainLooper());

        // 初始化OpenCV
        initOpenCV();

        // OpenCV 与模型成功加载后才能接收相机帧。
        if (!OpenCVInitializer.isInitialized() || !initInference()) {
            return;
        }

        // 初始化USB监视器
        initUsbMonitor();

        // 初始化叠加层
        initOverlay();

        // 初始化相机预览
        initCameraPreview();

        // 启动定时器
        lastResetTimestamp = System.currentTimeMillis();
        statsHandler.post(statsRunnable);
    }

    private void setupScreenAndSystemUI() {
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        SystemBarUtils.setFullScreenImmersive(requireActivity(), true);
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);
    }

    private void initOpenCV() {
        if (!OpenCVInitializer.isInitialized()) {
            OpenCVInitializer.initAsync(requireContext(), success -> {
                if (success) {
                    Log.d(TAG, "OpenCV初始化成功");
                } else {
                    Log.e(TAG, "OpenCV初始化失败");
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "OpenCV初始化失败，图像处理功能受限", Toast.LENGTH_SHORT).show());
                    }
                }
            });
        } else {
            Log.d(TAG, "OpenCV已经初始化");
        }
    }

    private boolean initInference() {
        // 初始化画笔
        boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.RED);
        textPaint.setTextSize(40.0f);

        // 初始化Paddle检测器
        try {
            // 确保缓存目录存在
            File paddleDir = new File(requireContext().getCacheDir(), "paddle");
            if (!paddleDir.exists()) {
                boolean result = paddleDir.mkdirs();
            }

            // 检查文件是否存在，不存在则从assets复制
            File modelFile = new File(paddleDir, "model.nb");
            File labelFile = new File(paddleDir, "doctor_label_list.txt");
            copyAssetFile(requireContext(),"paddle/model.nb", modelFile);
            copyAssetFile(requireContext(),"paddle/doctor_label_list.txt", labelFile);

            // 读取标签文件并建立映射
            labelMap.clear();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(labelFile), StandardCharsets.UTF_8))) {
                String label;
                while ((label = reader.readLine()) != null) {
                    labelMap.put(labelMap.size(), label);
                }
            }

            paddleDetector = new PaddleDetector();
            boolean success = paddleDetector.initDefault(
                    modelFile.getAbsolutePath(),
                    labelFile.getAbsolutePath(),
                    "LITE_POWER_HIGH"
            );
            if (!success) {
                throw new IllegalStateException("模型初始化未返回有效上下文");
            }
            return true;
        } catch (Exception | LinkageError e) {
            Log.e(TAG, "模型加载失败", e);
            Toast.makeText(requireContext(), "模型加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void initOverlay() {
        overlayHolder = binding.overlayView.getHolder();
        overlayHolder.setFormat(android.graphics.PixelFormat.TRANSPARENT);
        binding.overlayView.setZOrderMediaOverlay(true);
    }

    private void initCameraPreview() {
        binding.resultView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                // 如果相机已经初始化，设置预览
                if (uvcCamera != null) {
                    try {
                        startCameraPreview();
                    } catch (Exception e) {
                        Log.e(TAG, "设置预览失败", e);
                    }
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // 预览尺寸变化时的处理
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                previewRunning = false;
                if (uvcCamera != null) {
                    uvcCamera.stopPreview();
                }
                if (binding != null) {
                    binding.captureButton.setEnabled(false);
                }
                surface = null;
            }
        });
    }

    private void setupButtons() {
        binding.captureButton.setOnClickListener(v -> captureImage());

        binding.buttonSecond.setOnClickListener(v -> {
            releaseCamera();
            NavHostFragment.findNavController(PaddleDetectionFragment.this).popBackStack();
        });
    }

    private void initUsbMonitor() {
        usbMonitor = new USBMonitor(requireContext(), new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice device) {
                // 设备已连接
                if (isSelectedDevice(device)) {
                    runOnViewThread(() -> requestSelectedDevice());
                }
            }

            @Override
            public void onDettach(UsbDevice device) {
                // 设备已断开
                if (isSelectedDevice(device)) {
                    runOnViewThread(() -> {
                        permissionRequested = false;
                        releaseCamera();
                        Toast.makeText(requireContext(), "设备已断开连接", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                // 设备已获得权限，可以开始使用
                if (isSelectedDevice(device)) {
                    runOnViewThread(() -> {
                        if (uvcCamera == null) {
                            initCamera(ctrlBlock);
                        }
                    });
                }
            }

            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                // 设备已断开连接
                if (isSelectedDevice(device)) {
                    runOnViewThread(() -> {
                        if (uvcCamera != null && uvcCamera.getUsbControlBlock() == ctrlBlock) {
                            releaseCamera();
                        }
                    });
                }
            }

            @Override
            public void onCancel(UsbDevice device) {
                // 用户取消了权限请求
                runOnViewThread(() ->
                        Toast.makeText(requireContext(), "未获得设备权限", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private boolean isSelectedDevice(UsbDevice device) {
        return device != null && device.getDeviceId() == deviceId
                && device.getVendorId() == vendorId && device.getProductId() == productId;
    }

    private void runOnViewThread(Runnable action) {
        mainHandler.post(() -> {
            if (viewActive && binding != null && isAdded()) {
                action.run();
            }
        });
    }

    private void requestSelectedDevice() {
        if (usbMonitor == null || uvcCamera != null) {
            return;
        }
        for (UsbDevice device : usbMonitor.getDeviceList()) {
            if (isSelectedDevice(device)) {
                // USB 授权对话框可能暂停页面；恢复时读取真实授权，拒绝后不重复弹窗。
                if (!permissionRequested || usbMonitor.hasPermission(device)) {
                    permissionRequested = true;
                    usbMonitor.requestPermission(device);
                }
                return;
            }
        }
    }

    private void startCameraPreview() {
        if (uvcCamera == null || surface == null || !surface.isValid()) {
            return;
        }
        uvcCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21);
        uvcCamera.setPreviewDisplay(surface);
        previewRunning = true;
        uvcCamera.startPreview();
    }

    private void initCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        try {
            releaseCamera();

            // 创建UVCCamera实例
            uvcCamera = new UVCCamera();
            cameraControlBlock = ctrlBlock;
            uvcCamera.open(ctrlBlock);

            // 获取屏幕尺寸
            Point screenSize = new Point();
            requireActivity().getWindowManager().getDefaultDisplay().getRealSize(screenSize);

            // 计算最佳预览尺寸
            Size optimalSize = CameraUtils.getOptimalPreviewSize(screenSize.x, screenSize.y);

            try {
                // 尝试设置MJPEG格式
                uvcCamera.setPreviewSize(optimalSize.getWidth(), optimalSize.getHeight(),
                        UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (IllegalArgumentException e) {
                try {
                    // 如果MJPEG不支持，尝试YUV格式
                    uvcCamera.setPreviewSize(optimalSize.getWidth(), optimalSize.getHeight(),
                            UVCCamera.FRAME_FORMAT_YUYV);
                } catch (IllegalArgumentException e2) {
                    // 如果都不支持，使用默认尺寸
                    uvcCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH,
                            UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE);
                }
            }

            com.serenegiant.usb.Size actualSize = uvcCamera.getPreviewSize();
            if (actualSize == null) {
                throw new IllegalStateException("相机未返回有效预览尺寸");
            }
            previewWidth = actualSize.width;
            previewHeight = actualSize.height;
            startCameraPreview();
        } catch (Exception e) {
            releaseCamera();
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "相机初始化失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
        }
    }

    // 绘制检测结果
    private void drawDetectionResults(List<BaseDetection> detections, long frameStartTime) {
        if (binding == null || !previewRunning || overlayHolder == null || !isAdded() || getActivity() == null) {
            Log.e(TAG, "绘制失败：overlayHolder为空或Fragment已分离");
            return;
        }

        // 添加日志，输出检测结果数量
        Log.d("PaddleDetectionTime", "开始绘制检测结果，共 " + (detections != null ? detections.size() : 0) + " 个物体");

        // 计算FPS
        frameCount++;
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFpsTimestamp >= 1000) { // 每秒更新一次FPS
            float fps = frameCount * 1000 / (float)(currentTime - lastFpsTimestamp);
            Log.d("PaddleDetectionTime", "当前FPS: " + fps);
            frameCount = 0;
            lastFpsTimestamp = currentTime;
        }

        Canvas canvas = null;
        try {
            canvas = overlayHolder.lockCanvas();
            if (canvas != null) {
                // 清除画布
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                if (detections != null && !detections.isEmpty() && uvcCamera != null) {
                    // 获取叠加层尺寸
                    int overlayWidth = binding.overlayView.getWidth();
                    int overlayHeight = binding.overlayView.getHeight();

                    // 获取相机预览尺寸
                    int previewWidth = this.previewWidth;
                    int previewHeight = this.previewHeight;

                    // 从YOLODetector获取原始图像尺寸（如果可用）
                    int originalWidth = previewWidth;  // 默认使用预览尺寸
                    int originalHeight = previewHeight;

                    // 如果检测结果不为空，尝试从第一个检测结果中获取原始图像尺寸
                    if (!detections.isEmpty() && detections.get(0) != null && detections.get(0).originalImageWidth > 0) {
                        originalWidth = detections.get(0).originalImageWidth;
                        originalHeight = detections.get(0).originalImageHeight;
                    }

                    // 计算缩放比例（从原始图像尺寸到叠加层尺寸）
                    float scaleX = (float) overlayWidth / originalWidth;
                    float scaleY = (float) overlayHeight / originalHeight;

                    // 记录缩放信息
                    Log.d(TAG, "坐标映射: 原始图像尺寸=" + originalWidth + "x" + originalHeight +
                            ", 叠加层尺寸=" + overlayWidth + "x" + overlayHeight +
                            ", 缩放比例=" + scaleX + "," + scaleY);

                    // 使用DetectionUtils绘制检测结果
                    DetectionUtils.drawDetections(canvas, detections, scaleX, scaleY);

                    // 计算总延迟（从捕获到显示）
                    long totalLatency = System.currentTimeMillis() - frameStartTime;
                    // 打印单帧总延迟
                    Log.d("PaddleDetectionTime", "总延迟时间: " + totalLatency + " ms");
                    // 打印平均延迟（每100帧打印一次）
                    if (latencyCount % 100 == 0 && latencyCount > 0) {
                        long avgLatency = totalLatencyTime / latencyCount;
                        Log.w("PaddleDetectionTime", "平均总延迟时间: " + avgLatency + " ms");
                    }
                } else {
                    Log.d(TAG, "没有检测结果可绘制");
                }
            } else {
                Log.e(TAG, "无法获取画布进行绘制");
            }
        } catch (Exception e) {
            Log.e(TAG, "绘制检测结果时出错", e);
            e.printStackTrace();
        } finally {
            if (canvas != null) {
                try {
                    overlayHolder.unlockCanvasAndPost(canvas);
                    Log.d(TAG, "画布已解锁并提交");
                } catch (Exception e) {
                    Log.e(TAG, "解锁画布时出错", e);
                    e.printStackTrace();
                }
            }
        }
    }

    private void captureImage() {
        if (uvcCamera == null || overlayHolder == null) {
            Toast.makeText(requireContext(), "相机未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] frameData;
        float[][] detectionResults;
        synchronized (frameLock) {
            frameData = lastFrameData;
            detectionResults = lastDetectionResults;
        }
        if (frameData == null || !previewRunning) {
            Toast.makeText(requireContext(), "尚未收到相机图像", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap lastFrameBitmap = ImageProcessingUtils.yuv2BitmapNative(frameData, previewWidth, previewHeight);
        if (lastFrameBitmap == null) {
            Toast.makeText(requireContext(), "获取当前帧失败", Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建一个新的Bitmap用于绘制检测结果
        Bitmap resultBitmap = Bitmap.createBitmap(
                lastFrameBitmap.getWidth(),
                lastFrameBitmap.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        // 绘制原始图像
        Canvas canvas = new Canvas(resultBitmap);
        canvas.drawBitmap(lastFrameBitmap, 0, 0, null);

        // 绘制检测框
        if (paddleDetector != null) {
            if (detectionResults != null) {
                List<BaseDetection> detections = new ArrayList<>();
                for (float[] result : detectionResults) {
                    int labelIndex = (int)result[0];
                    float confidence = result[1];
                    float x = result[2];
                    float y = result[3];
                    float w = result[4];
                    float h = result[5];

                    RectF bbox = new RectF(x, y, x + w, y + h);
                    detections.add(new BaseDetection(confidence, bbox,
                            getLabelName(labelIndex), lastFrameBitmap.getWidth(), lastFrameBitmap.getHeight()));
                }

                // 绘制检测结果
                DetectionUtils.drawDetections(canvas, detections, 1.0f, 1.0f);
            }
        }

        // 保存图片
        StorageUtils.saveImageToGallery(requireContext(), resultBitmap);

        // 回收Bitmap
        lastFrameBitmap.recycle();
        resultBitmap.recycle();

    }

    private void releaseCamera() {
        previewRunning = false;
        UVCCamera camera = uvcCamera;
        uvcCamera = null;
        USBMonitor.UsbControlBlock controlBlock = cameraControlBlock;
        cameraControlBlock = null;
        if (camera != null) {
            try {
                camera.destroy();
            } catch (Exception e) {
                Log.e(TAG, "释放相机时出错", e);
            }
        }
        if (controlBlock != null) {
            controlBlock.close();
        }
        synchronized (frameLock) {
            lastFrameData = null;
            lastDetectionResults = null;
        }
        if (binding != null) {
            binding.captureButton.setEnabled(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        viewActive = true;
        if (usbMonitor != null) {
            usbMonitor.register();
            requestSelectedDevice();
        }
    }

    @Override
    public void onPause() {
        viewActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
        }
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        releaseCamera();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        viewActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();

        // 释放资源
        releaseResources();

        binding = null;

        // 停止定时器
        statsHandler.removeCallbacks(statsRunnable);
    }

    private void releaseResources() {
        // 释放相机资源
        releaseCamera();
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }

        // 释放UI线程Handler
        if (uiHandler != null) {
            uiHandler.removeCallbacksAndMessages(null);
            uiHandler = null;
        }

        // 释放检测器
        if (paddleDetector != null) {
            paddleDetector.release();
            paddleDetector = null;
        }

        // 释放绘图资源
        boxPaint = null;
        textPaint = null;
        overlayHolder = null;
        surface = null;
    }

    String getLabelName(int classIndex) {
        String labelName = labelMap.get(classIndex);
        return labelName != null ? labelName : "未知类别";
    }
}

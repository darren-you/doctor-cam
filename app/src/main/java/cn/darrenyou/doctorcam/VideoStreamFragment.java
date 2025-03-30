package cn.darrenyou.doctorcam;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.darrenyou.doctorcam.databinding.FragmentVideoStreamBinding;
import cn.darrenyou.doctorcam.ml.DetectionResult;
import cn.darrenyou.doctorcam.ml.ObjectDetector;
import cn.darrenyou.doctorcam.utils.CameraUtils;
import cn.darrenyou.doctorcam.utils.FpsCalculator;
import cn.darrenyou.doctorcam.utils.ImageProcessingUtils;
import cn.darrenyou.doctorcam.utils.ObjectPoolManager;
import cn.darrenyou.doctorcam.utils.OpenCVInitializer;
import cn.darrenyou.doctorcam.utils.StorageUtils;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;

public class VideoStreamFragment extends Fragment {

    private static final String TAG = "VideoStreamFragment";
    private FragmentVideoStreamBinding binding;
    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private Surface surface;
    private SurfaceTexture surfaceTexture;

    private int deviceId;
    private int vendorId;
    private int productId;

    // 添加物体检测相关成员变量
    private ObjectDetector objectDetector;
    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    private Paint boxPaint;
    private Paint textPaint;
    private AtomicBoolean isProcessingFrame = new AtomicBoolean(false);

    // 添加 OpenCV 相关成员变量
    private boolean openCVInitialized = false;
    private boolean useOpenCVProcessing = true;

    // 使用工具类
    private FpsCalculator fpsCalculator;
    private ObjectPoolManager objectPoolManager;

    // 性能优化相关变量
    private int frameSkipCount = 0;
    private int FRAME_SKIP_THRESHOLD = 4; // 每5帧处理1帧
    private List<DetectionResult> lastResults = null; // 存储上一次的检测结果
    private float INFERENCE_SCALE_FACTOR = 0.4f; // 推理时将图像缩小到原来的40%

    // 帧处理计数器
    private long totalFrames = 0;
    private long processedFrames = 0;

    // 直接绘制模式
    private SurfaceHolder overlayHolder;

    // FPS 限制
    private boolean limitFps = false;
    private int targetFps = 15;
    private long lastFrameTime = 0;

    // 本地处理
    private boolean useNativeProcessing = true;

    // 添加一个成员变量来存储最新的帧数据
    private byte[] lastFrameData = null;
    private final Object lastFrameLock = new Object();

    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            totalFrames++;

            // 如果启用了 FPS 限制，检查是否应该处理这一帧
            if (limitFps) {
                long now = System.currentTimeMillis();
                long elapsed = now - lastFrameTime;
                long targetFrameTime = 1000 / targetFps;

                if (elapsed < targetFrameTime) {
                    // 帧率过高，跳过这一帧
                    return;
                }

                lastFrameTime = now;
            }

            // 保存最新的帧数据用于拍照
            if (frame != null) {
                synchronized (lastFrameLock) {
                    if (lastFrameData == null || lastFrameData.length != frame.remaining()) {
                        lastFrameData = new byte[frame.remaining()];
                    }
                    frame.get(lastFrameData);
                    frame.rewind(); // 重置position以便后续处理
                }
            }

            if (frame != null && !isProcessingFrame.get()) {
                processFrame(frame);
            }
        }
    };

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentVideoStreamBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        SystemBarUtils.setFullScreenImmersive(requireActivity(), true);

        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);

        binding.captureButton.setEnabled(false);
        binding.buttonSecond.setEnabled(false);

        if (getArguments() != null) {
            deviceId = getArguments().getInt("deviceId");
            vendorId = getArguments().getInt("vendorId");
            productId = getArguments().getInt("productId");
        }

        // 初始化工具类
        fpsCalculator = new FpsCalculator();
        objectPoolManager = new ObjectPoolManager(8); // 增加池大小为8

        // 初始化 OpenCV
        if (!OpenCVInitializer.isInitialized()) {
            OpenCVInitializer.initAsync(requireContext(), success -> {
                if (success) {
                    Log.d(TAG, "OpenCV 初始化成功");
                    openCVInitialized = true;
                } else {
                    Log.e(TAG, "OpenCV 初始化失败");
                    openCVInitialized = false;
                    // 可以在这里显示错误信息
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "OpenCV 初始化失败，图像处理功能受限", Toast.LENGTH_SHORT).show());
                    }
                }
            });
        } else {
            Log.d(TAG, "OpenCV 已经初始化");
            openCVInitialized = true;
        }

        // 初始化推理相关组件
        initInference();

        initUsbMonitor();

        // 初始化叠加层
        overlayHolder = binding.overlayView.getHolder();
        overlayHolder.setFormat(android.graphics.PixelFormat.TRANSPARENT);

        // 使用SurfaceView直接显示相机预览
        binding.resultView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                // 如果相机已经初始化，设置预览
                if (uvcCamera != null) {
                    try {
                        uvcCamera.setPreviewDisplay(surface);
                        uvcCamera.startPreview();
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
                surface = null;
            }
        });

        binding.captureButton.setOnClickListener(v -> captureImage());

        binding.buttonSecond.setOnClickListener(v -> {
            releaseCamera();
            NavHostFragment.findNavController(VideoStreamFragment.this)
                    .navigate(R.id.action_VideoStreamFragment_to_FirstFragment);
        });

        binding.settingsButton.setOnClickListener(v -> showPerformanceSettings());
        
        // 隐藏不需要的ImageView，只显示直接绘制模式的视图
        binding.cameraStreamPreview.setVisibility(View.INVISIBLE);
        binding.overlayView.setVisibility(View.VISIBLE);
    }

    private void initInference() {
        // 初始化推理线程
        inferenceThread = new HandlerThread("InferenceThread");
        inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());

        // 初始化画笔
        boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.RED);
        textPaint.setTextSize(40.0f);

        // 初始化对象检测器
        try {
            objectDetector = new ObjectDetector(requireContext());
        } catch (IOException e) {
            Toast.makeText(requireContext(), "模型加载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void initUsbMonitor() {
        usbMonitor = new USBMonitor(requireContext(), new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice device) {
                // 设备已连接
                if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                    usbMonitor.requestPermission(device);
                }
            }

            @Override
            public void onDettach(UsbDevice device) {
                // 设备已断开
                if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                    releaseCamera();
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "设备已断开连接", Toast.LENGTH_SHORT).show());
                }
            }

            @Override
            public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                // 设备已获得权限，可以开始使用
                if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                    initCamera(ctrlBlock);
                }
            }

            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                // 设备已断开连接
                if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                    releaseCamera();
                }
            }

            @Override
            public void onCancel(UsbDevice device) {
                // 用户取消了权限请求
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "未获得设备权限", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void initCamera(USBMonitor.UsbControlBlock ctrlBlock) {
        try {
            releaseCamera();

            // 创建UVCCamera实例
            uvcCamera = new UVCCamera();
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

            // 设置帧回调
            uvcCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21);

            // 开始预览
            if (surface != null) {
                uvcCamera.setPreviewDisplay(surface);
                uvcCamera.startPreview();

                // 更新UI，同时启用两个按钮
                requireActivity().runOnUiThread(() -> {
                    binding.captureButton.setEnabled(true);
                    binding.buttonSecond.setEnabled(true);
                    binding.settingsButton.setEnabled(true);
                });
            }
        } catch (Exception e) {
            releaseCamera();
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "相机初始化失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
        }
    }

    @SuppressLint("DefaultLocale")
    private void processFrame(ByteBuffer frame) {
        if (frame == null || objectDetector == null || uvcCamera == null || !isAdded() || isDetached()) {
            Log.d(TAG, "跳过帧处理：frame=" + (frame != null) +
                    ", detector=" + (objectDetector != null) +
                    ", camera=" + (uvcCamera != null) +
                    ", isAdded=" + isAdded() +
                    ", isDetached=" + isDetached());
            return;
        }

        if (isProcessingFrame.get()) {
            Log.d(TAG, "正在处理上一帧，跳过当前帧");
            return;
        }

        isProcessingFrame.set(true);
        Log.d(TAG, "开始处理新帧，大小：" + frame.remaining());

        // 更新帧率
        boolean fpsUpdated = fpsCalculator.update();
        if (fpsUpdated && binding != null) {
            requireActivity().runOnUiThread(() -> {
                binding.fpsText.setText(String.format("FPS: %.1f (处理率: %.1f%%)",
                        fpsCalculator.getCurrentFps(),
                        totalFrames > 0 ? (processedFrames * 100.0f / totalFrames) : 0));
            });
        }

        // 使用缓冲池获取 ByteBuffer
        final ByteBuffer frameBuffer = objectPoolManager.getByteBufferFromPool(frame.remaining());
        frameBuffer.put(frame);
        frameBuffer.rewind();

        inferenceHandler.post(() -> {
            try {
                processedFrames++;
                byte[] data = new byte[frameBuffer.remaining()];
                frameBuffer.get(data);

                // 返回 ByteBuffer 到池
                objectPoolManager.returnByteBufferToPool(frameBuffer);

                int width = uvcCamera.getPreviewSize().width;
                int height = uvcCamera.getPreviewSize().height;

                // 使用 OpenCV 转换 YUV 到 Bitmap
                Bitmap bitmap;
                if (useNativeProcessing && openCVInitialized) {
                    // 使用 OpenCV 的本地方法加速转换
                    bitmap = ImageProcessingUtils.yuv2BitmapNative(data, width, height);
                } else {
                    bitmap = ImageProcessingUtils.yuv2Bitmap(data, width, height);
                }

                // 可选：使用 OpenCV 进行图像预处理
                if (useOpenCVProcessing && openCVInitialized) {
                    bitmap = ImageProcessingUtils.preprocessImage(bitmap);
                }

                // 根据跳帧计数决定是否执行检测
                List<DetectionResult> results;
                boolean shouldRunInference = (frameSkipCount == 0);

                if (shouldRunInference) {
                    // 缩小图像以加速推理
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            (int)(bitmap.getWidth() * INFERENCE_SCALE_FACTOR),
                            (int)(bitmap.getHeight() * INFERENCE_SCALE_FACTOR),
                            true);

                    // 执行检测
                    results = objectDetector.detect(scaledBitmap);

                    // 调整检测框坐标以匹配原始图像
                    for (DetectionResult result : results) {
                        RectF location = result.getLocation();
                        location.left /= INFERENCE_SCALE_FACTOR;
                        location.top /= INFERENCE_SCALE_FACTOR;
                        location.right /= INFERENCE_SCALE_FACTOR;
                        location.bottom /= INFERENCE_SCALE_FACTOR;
                    }

                    // 回收缩小的图像
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle();
                    }

                    lastResults = results; // 保存结果供后续帧使用
                } else {
                    // 使用上一帧的检测结果
                    results = lastResults;
                }

                // 更新跳帧计数
                frameSkipCount = (frameSkipCount + 1) % (FRAME_SKIP_THRESHOLD + 1);

                // 在UI线程中绘制结果
                if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
                    final Bitmap finalBitmap = bitmap;
                    final List<DetectionResult> finalResults = results;
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            try {
                                // 直接在 SurfaceView 上绘制检测结果
                                Canvas canvas = overlayHolder.lockCanvas();
                                if (canvas != null) {
                                    try {
                                        // 清除画布
                                        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);

                                        // 计算缩放比例
                                        int viewWidth = binding.overlayView.getWidth();
                                        int viewHeight = binding.overlayView.getHeight();
                                        float scaleX = (float) viewWidth / finalBitmap.getWidth();
                                        float scaleY = (float) viewHeight / finalBitmap.getHeight();

                                        // 绘制检测结果
                                        if (finalResults != null) {
                                            for (DetectionResult result : finalResults) {
                                                RectF location = result.getLocation();
                                                RectF scaledLocation = new RectF(
                                                        location.left * scaleX,
                                                        location.top * scaleY,
                                                        location.right * scaleX,
                                                        location.bottom * scaleY
                                                );

                                                canvas.drawRect(scaledLocation, boxPaint);
                                                canvas.drawText(
                                                        String.format("%s: %.2f",
                                                                result.getLabel(),
                                                                result.getConfidence()),
                                                        scaledLocation.left,
                                                        scaledLocation.top - 10,
                                                        textPaint
                                                );
                                            }
                                        }
                                    } finally {
                                        overlayHolder.unlockCanvasAndPost(canvas);
                                    }
                                }

                                // 回收处理后的Bitmap
                                objectPoolManager.returnBitmapToPool(finalBitmap);
                            } catch (Exception e) {
                                Log.e(TAG, "绘制失败", e);
                                if (finalBitmap != null) {
                                    objectPoolManager.returnBitmapToPool(finalBitmap);
                                }
                            } finally {
                                isProcessingFrame.set(false);
                            }
                        } else {
                            if (finalBitmap != null) {
                                objectPoolManager.returnBitmapToPool(finalBitmap);
                            }
                            isProcessingFrame.set(false);
                        }
                    });
                } else {
                    if (bitmap != null) {
                        objectPoolManager.returnBitmapToPool(bitmap);
                    }
                    isProcessingFrame.set(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "处理帧时出错", e);
                isProcessingFrame.set(false);
            }
        });
    }

    private void showPerformanceSettings() {
        if (!isAdded() || getActivity() == null) return;
    
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_performance_settings, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.CustomDialogStyle);
        builder.setTitle("性能设置");
        builder.setView(dialogView);

        // 获取对话框中的控件
        SeekBar frameSkipSeekBar = dialogView.findViewById(R.id.frame_skip_seekbar);
        TextView frameSkipValueText = dialogView.findViewById(R.id.frame_skip_value);
        SeekBar scaleFactorSeekBar = dialogView.findViewById(R.id.scale_factor_seekbar);
        TextView scaleFactorValueText = dialogView.findViewById(R.id.scale_factor_value);
        CheckBox limitFpsCheckBox = dialogView.findViewById(R.id.limit_fps_checkbox);
        SeekBar targetFpsSeekBar = dialogView.findViewById(R.id.target_fps_seekbar);
        TextView targetFpsValueText = dialogView.findViewById(R.id.target_fps_value);

        // 设置初始值
        frameSkipSeekBar.setMax(9);
        frameSkipSeekBar.setProgress(FRAME_SKIP_THRESHOLD);
        frameSkipValueText.setText(String.valueOf(FRAME_SKIP_THRESHOLD + 1));

        scaleFactorSeekBar.setMax(10);
        int scaleProgress = (int)(INFERENCE_SCALE_FACTOR * 10);
        scaleFactorSeekBar.setProgress(scaleProgress);
        scaleFactorValueText.setText(String.format("%.1f", INFERENCE_SCALE_FACTOR));

        limitFpsCheckBox.setChecked(limitFps);

        targetFpsSeekBar.setMax(30);
        targetFpsSeekBar.setProgress(targetFps);
        targetFpsValueText.setText(String.valueOf(targetFps));

        // 设置监听器
        frameSkipSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                frameSkipValueText.setText(String.valueOf(progress + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        scaleFactorSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = Math.max(0.1f, progress / 10.0f);
                scaleFactorValueText.setText(String.format("%.1f", value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        targetFpsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(5, progress);
                targetFpsValueText.setText(String.valueOf(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 设置 FPS 限制相关控件的联动
        limitFpsCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            targetFpsSeekBar.setEnabled(isChecked);
            targetFpsValueText.setEnabled(isChecked);
        });
        targetFpsSeekBar.setEnabled(limitFps);
        targetFpsValueText.setEnabled(limitFps);

        // 设置按钮
        builder.setPositiveButton("确定", (dialog, which) -> {
            // 应用设置
            FRAME_SKIP_THRESHOLD = frameSkipSeekBar.getProgress();
            INFERENCE_SCALE_FACTOR = Math.max(0.1f, scaleFactorSeekBar.getProgress() / 10.0f);
            limitFps = limitFpsCheckBox.isChecked();
            targetFps = Math.max(5, targetFpsSeekBar.getProgress());

            // 重置计数器
            frameSkipCount = 0;
            totalFrames = 0;
            processedFrames = 0;

            Toast.makeText(requireContext(), "设置已应用", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", null);

        // 创建并显示对话框
        AlertDialog dialog = builder.create();
        dialog.show();

        // 设置对话框窗口参数
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT; // 使用WRAP_CONTENT而不是MATCH_PARENT

            // 设置对话框在屏幕中的位置，可以通过gravity和y偏移来控制
            params.gravity = Gravity.CENTER;

            // 如果您想要限制对话框的最大高度，可以使用以下方式
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int screenHeight = displayMetrics.heightPixels;
            params.height = (int)(screenHeight * 0.8); // 设置为屏幕高度的80%

            window.setAttributes(params);
        }
    
        // 设置对话框按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorAccent));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
    }

    private void releaseCamera() {
        UVCCamera camera = uvcCamera;  // 创建本地引用
        uvcCamera = null;  // 立即将全局引用置空

        if (camera != null) {
            try {
                camera.stopPreview();
                camera.close();
                camera.destroy();
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }

        // 更新UI，同时禁用两个按钮
        if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
            requireActivity().runOnUiThread(() -> {
                if (binding != null) {
                    binding.captureButton.setEnabled(false);
                    binding.buttonSecond.setEnabled(false);
                    binding.settingsButton.setEnabled(false);
                }
            });
        }
    }

    private void captureImage() {
        if (uvcCamera == null) {
            Toast.makeText(requireContext(), "相机未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Bitmap bitmap = null;

            // 需要从当前处理的帧和检测结果创建一个新的Bitmap
            if (lastResults == null) {
                Toast.makeText(requireContext(), "等待检测结果...", Toast.LENGTH_SHORT).show();
                return;
            }

            // 获取相机预览尺寸
            int width = uvcCamera.getPreviewSize().width;
            int height = uvcCamera.getPreviewSize().height;

            // 使用最近一次的帧数据
            byte[] data = null;
            synchronized (lastFrameLock) {
                if (lastFrameData != null) {
                    data = lastFrameData.clone(); // 创建一个副本以避免并发修改
                }
            }

            if (data != null) {
                // 转换为Bitmap - 确保使用与预览相同的处理流程
                if (useNativeProcessing && openCVInitialized) {
                    // 使用与预览相同的OpenCV本地方法
                    bitmap = ImageProcessingUtils.yuv2BitmapNative(data, width, height);
                    
                    // 确保色彩空间正确 - 可能需要转换色彩空间
                    if (bitmap != null) {
                        bitmap = ImageProcessingUtils.correctColorSpace(bitmap);
                    }
                } else {
                    bitmap = ImageProcessingUtils.yuv2Bitmap(data, width, height);
                    
                    // 如果使用Java方法，可能需要额外的色彩校正
                    if (bitmap != null) {
                        bitmap = ImageProcessingUtils.adjustColorBalance(bitmap);
                    }
                }

                // 可选：使用OpenCV进行图像预处理 - 确保与预览使用相同的处理
                if (useOpenCVProcessing && openCVInitialized && bitmap != null) {
                    bitmap = ImageProcessingUtils.preprocessImage(bitmap);
                }

                // 在Bitmap上绘制检测结果
                if (bitmap != null && lastResults != null) {
                    Canvas canvas = new Canvas(bitmap);
                    for (DetectionResult result : lastResults) {
                        RectF location = result.getLocation();
                        canvas.drawRect(location, boxPaint);
                        canvas.drawText(
                                String.format("%s: %.2f", result.getLabel(), result.getConfidence()),
                                location.left, location.top - 10, textPaint);
                    }
                }
            }

            if (bitmap != null) {
                // 保存图片到相册
                StorageUtils.saveImageToGallery(requireContext(), bitmap);
                Toast.makeText(requireContext(), "图片已保存", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "无法获取图像", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "拍照失败", e);
            Toast.makeText(requireContext(), "拍照失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 恢复 USB 监听
        if (usbMonitor != null) {
            usbMonitor.register();
        }

        // 恢复相机预览
        if (uvcCamera != null && surface != null) {
            try {
                uvcCamera.setPreviewDisplay(surface);
                uvcCamera.startPreview();
                // 允许继续处理帧
                isProcessingFrame.set(false);
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }

        // 重新设置状态栏样式
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);
    }

    @Override
    public void onPause() {
        super.onPause();
        // 先停止相机预览
        if (uvcCamera != null) {
            try {
                uvcCamera.stopPreview();
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
        
        // 停止帧处理
        isProcessingFrame.set(true);
        
        // 取消 USB 监听
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseCamera();

        if (inferenceThread != null) {
            inferenceThread.quitSafely();
            try {
                inferenceThread.join();
                inferenceThread = null;
                inferenceHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (objectDetector != null) {
            objectDetector.close();
            objectDetector = null;
        }

        if (objectPoolManager != null) {
            objectPoolManager.release();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (surface != null) {
            surface.release();
            surface = null;
        }

        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        binding = null;
    }
}
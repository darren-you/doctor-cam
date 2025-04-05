package cn.darrenyou.doctorcam;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.darrenyou.doctorcam.databinding.FragmentOnnxDetectionBinding;
import cn.darrenyou.doctorcam.onnx.OnnxDetection;
import cn.darrenyou.doctorcam.onnx.OnnxYOLODetector;
import cn.darrenyou.doctorcam.utils.CameraUtils;
import cn.darrenyou.doctorcam.utils.ImageProcessingUtils;
import cn.darrenyou.doctorcam.utils.OpenCVInitializer;
import cn.darrenyou.doctorcam.utils.StorageUtils;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;
import cn.darrenyou.doctorcam.utils.DetectionUtils;

public class OnnxDetectionFragment extends Fragment {

    private static final String TAG = "OnnxDetectionLog";
    private FragmentOnnxDetectionBinding binding;
    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private Surface surface;

    private int deviceId;
    private int vendorId;
    private int productId;

    // YOLO检测器
    private OnnxYOLODetector yoloDetector;
    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    private Handler uiHandler;
    private Paint boxPaint;
    private Paint textPaint;
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private boolean isDetectionEnabled = true;

    // OpenCV相关
    private boolean openCVInitialized = false;

    // 性能优化相关
    private int frameSkipCount = 0;
    private int FRAME_SKIP_THRESHOLD = 1; // 每5帧处理1帧
    private List<OnnxDetection> lastResults = null; // 存储上一次的检测结果

    // 直接绘制模式
    private SurfaceHolder overlayHolder;

    // 最新帧数据
    private byte[] lastFrameData = null;
    private final Object lastFrameLock = new Object();

    // 修改后的帧回调处理
    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            if (uvcCamera == null || !isDetectionEnabled) {
                return;
            }

            // 检查是否正在处理上一帧
            if (isProcessingFrame.get()) {
                return;
            }

            isProcessingFrame.set(true);

            try {
                // 获取摄像头预览尺寸
                final int width = uvcCamera.getPreviewSize().width;
                final int height = uvcCamera.getPreviewSize().height;

                // 复制帧数据
                byte[] data = new byte[frame.remaining()];
                frame.get(data);
                frame.rewind();

                // 保存最新帧数据
                synchronized (lastFrameLock) {
                    lastFrameData = data;
                }

                // 帧跳过逻辑，提高性能
                frameSkipCount = (frameSkipCount + 1) % (FRAME_SKIP_THRESHOLD + 1);
                if (frameSkipCount != 0) {
                    // 如果有上一次的检测结果，直接绘制
                    if (lastResults != null) {
                        drawDetectionResults(lastResults);
                    }
                    isProcessingFrame.set(false);
                    return;
                }

                // 使用推理线程处理图像
                inferenceHandler.post(() -> {
                    try {
                        // 将NV21数据转换为Bitmap
                        Bitmap bitmap = ImageProcessingUtils.yuv2BitmapNative(data, width, height);
                        if (bitmap == null) {
                            isProcessingFrame.set(false);
                            return;
                        }

                        // 直接使用原始图像进行推理
                        List<OnnxDetection> detections = yoloDetector.detect(bitmap);

                        // 保存检测结果
                        lastResults = detections;

                        // 在UI线程中绘制检测结果
                        if (uiHandler != null) {
                            uiHandler.post(() -> {
                                drawDetectionResults(detections);
                                // 回收位图
                                bitmap.recycle();
                                isProcessingFrame.set(false);
                            });
                        } else {
                            bitmap.recycle();
                            isProcessingFrame.set(false);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "处理帧时出错", e);
                        isProcessingFrame.set(false);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "处理摄像头帧时出错", e);
                isProcessingFrame.set(false);
            }
        }
    };

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentOnnxDetectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 设置屏幕方向和系统UI
        setupScreenAndSystemUI();

        // 禁用按钮，直到相机初始化完成
        binding.captureButton.setEnabled(false);
        binding.buttonSecond.setEnabled(false);

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

        // 初始化推理相关组件
        initInference();

        // 初始化USB监视器
        initUsbMonitor();

        // 初始化叠加层
        initOverlay();

        // 初始化相机预览
        initCameraPreview();

        // 设置按钮点击事件
        setupButtons();
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
                    openCVInitialized = true;
                } else {
                    Log.e(TAG, "OpenCV初始化失败");
                    openCVInitialized = false;
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "OpenCV初始化失败，图像处理功能受限", Toast.LENGTH_SHORT).show());
                    }
                }
            });
        } else {
            Log.d(TAG, "OpenCV已经初始化");
            openCVInitialized = true;
        }
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

        // 初始化YOLO检测器
        try {
            yoloDetector = new OnnxYOLODetector(requireContext());
        } catch (Exception e) {
            Log.e(TAG, "模型加载失败", e);
            Toast.makeText(requireContext(), "模型加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initOverlay() {
        overlayHolder = binding.overlayView.getHolder();
        overlayHolder.setFormat(android.graphics.PixelFormat.TRANSPARENT);
    }

    private void initCameraPreview() {
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
    }

    private void setupButtons() {
        binding.captureButton.setOnClickListener(v -> captureImage());

        binding.buttonSecond.setOnClickListener(v -> {
            releaseCamera();
            NavHostFragment.findNavController(OnnxDetectionFragment.this)
                    .navigate(R.id.action_OnnxDetectionFragment_to_UvcDevicesFragment);
        });
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
                });
            }
        } catch (Exception e) {
            releaseCamera();
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(), "相机初始化失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
        }
    }

    // 绘制检测结果
    private void drawDetectionResults(List<OnnxDetection> detections) {
        if (overlayHolder == null || !isAdded() || getActivity() == null) {
            Log.e(TAG, "绘制失败：overlayHolder为空或Fragment已分离");
            return;
        }

        // 添加日志，输出检测结果数量
        Log.d(TAG, "开始绘制检测结果，共 " + (detections != null ? detections.size() : 0) + " 个物体");

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
                    int previewWidth = uvcCamera.getPreviewSize().width;
                    int previewHeight = uvcCamera.getPreviewSize().height;
                    
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
        if (uvcCamera == null) {
            Toast.makeText(requireContext(), "相机未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 获取最新的帧数据
            byte[] data;
            synchronized (lastFrameLock) {
                if (lastFrameData == null) {
                    Toast.makeText(requireContext(), "无可用的图像数据", Toast.LENGTH_SHORT).show();
                    return;
                }
                data = lastFrameData.clone();
            }

            // 获取预览尺寸
            int previewWidth = uvcCamera.getPreviewSize().width;
            int previewHeight = uvcCamera.getPreviewSize().height;

            // 转换为Bitmap
            Bitmap bitmap = null;
            Bitmap resultBitmap = null;

            try {
                // 使用工具类转换图像
                bitmap = ImageProcessingUtils.yuv2BitmapNative(data, previewWidth, previewHeight);
                
                if (bitmap == null) {
                    Toast.makeText(requireContext(), "图像转换失败", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 创建一个新的位图用于绘制检测结果
                resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                Canvas canvas = new Canvas(resultBitmap);

                // 绘制检测结果
                if (lastResults != null && !lastResults.isEmpty()) {
                    // 使用DetectionUtils绘制检测结果到位图上
                    DetectionUtils.drawDetectionsOnBitmap(canvas, lastResults);
                }

                // 保存图像前添加日志
                Log.d(TAG, "保存图像，尺寸: " + resultBitmap.getWidth() + "x" + resultBitmap.getHeight() + 
                    ", 配置: " + resultBitmap.getConfig());
                    
                // 保存图像
                StorageUtils.saveImageToGallery(requireContext(), resultBitmap);
                Toast.makeText(requireContext(), "图片已保存", Toast.LENGTH_SHORT).show();
            } finally {
                // 回收位图
                if (bitmap != null && bitmap != resultBitmap) {
                    bitmap.recycle();
                }
                if (resultBitmap != null) {
                    resultBitmap.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "捕获图像时出错", e);
            Toast.makeText(requireContext(), "捕获图像失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void releaseCamera() {
        if (uvcCamera != null) {
            try {
                uvcCamera.stopPreview();
                uvcCamera.close();
                uvcCamera = null;
            } catch (Exception e) {
                Log.e(TAG, "释放相机时出错", e);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (usbMonitor != null) {
            usbMonitor.register();
        }
    }

    @Override
    public void onPause() {
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        releaseCamera();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        // 释放资源
        releaseResources();
        
        binding = null;
    }
    
    private void releaseResources() {
        // 释放相机资源
        releaseCamera();
        
        // 释放推理线程
        if (inferenceThread != null) {
            inferenceThread.quitSafely();
            try {
                inferenceThread.join();
                inferenceThread = null;
                inferenceHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "中断推理线程时出错", e);
            }
        }

        // 释放UI线程Handler
        uiHandler = null;

        // 释放检测器
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }
        
        // 清除最后一帧数据
        synchronized (lastFrameLock) {
            lastFrameData = null;
        }
        
        // 清除最后的检测结果
        lastResults = null;

        // 释放绘图资源
        boxPaint = null;
        textPaint = null;
    }
}
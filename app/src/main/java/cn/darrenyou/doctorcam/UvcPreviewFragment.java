package cn.darrenyou.doctorcam;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import cn.darrenyou.doctorcam.databinding.FragmentUvcPreviewBinding;
import cn.darrenyou.doctorcam.utils.CameraUtils;
import cn.darrenyou.doctorcam.utils.StorageUtils;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;

public class UvcPreviewFragment extends Fragment {

    private FragmentUvcPreviewBinding binding;
    private USBMonitor usbMonitor;
    private UVCCamera uvcCamera;
    private USBMonitor.UsbControlBlock cameraControlBlock;
    private Surface surface;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean viewActive;
    private boolean permissionRequested;
    
    private int deviceId;
    private int vendorId;
    private int productId;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentUvcPreviewBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        permissionRequested = false;

        // 强制横屏并设置全屏模式（允许延伸到刘海区域）
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        SystemBarUtils.setFullScreenImmersive(requireActivity(), true);

        // 设置状态栏透明和深色图标
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);

        // 返回不依赖相机连接状态。
        binding.captureButton.setEnabled(false);

        // 获取传递的设备信息
        if (getArguments() != null) {
            deviceId = getArguments().getInt("deviceId");
            vendorId = getArguments().getInt("vendorId");
            productId = getArguments().getInt("productId");
        }
        
        // 初始化USB监视器
        initUsbMonitor();
        
        // 设置TextureView准备就绪的监听器
        binding.cameraPreview.setSurfaceTextureListener(new CameraTextureListener());
        
        // 设置拍照按钮点击事件
        binding.captureButton.setOnClickListener(v -> captureImage());
        
        // 设置返回按钮点击事件
        binding.buttonSecond.setOnClickListener(v -> {
            releaseCamera();
            NavHostFragment.findNavController(UvcPreviewFragment.this).popBackStack();
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
            
            // 开始预览
            if (surface != null) {
                uvcCamera.setPreviewDisplay(surface);
                uvcCamera.startPreview();
                
                binding.captureButton.setEnabled(true);
            }
        } catch (Exception e) {
            releaseCamera();
            requireActivity().runOnUiThread(() -> 
                Toast.makeText(requireContext(), "相机初始化失败: " + e.getMessage(), 
                        Toast.LENGTH_SHORT).show());
        }
    }
    
    private void releaseCamera() {
        UVCCamera camera = uvcCamera;  // 创建本地引用
        uvcCamera = null;  // 立即将全局引用置空
        USBMonitor.UsbControlBlock controlBlock = cameraControlBlock;
        cameraControlBlock = null;

        if (camera != null) {
            try {
                camera.destroy();
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
        if (controlBlock != null) {
            controlBlock.close();
        }
        
        // 停止预览后禁用抓拍，返回仍可使用。
        if (isAdded() && getActivity() != null && !getActivity().isFinishing()) {
            requireActivity().runOnUiThread(() -> {
                if (binding != null) {
                    binding.captureButton.setEnabled(false);
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
            // 从相机获取当前帧
            Bitmap bitmap = binding.cameraPreview.getBitmap();
            if (bitmap != null) {
                // 保存图片到相册
                StorageUtils.saveImageToGallery(requireContext(), bitmap);
            } else {
                Toast.makeText(requireContext(), "无法获取图像", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "拍照失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // TextureView准备就绪的监听器
    private class CameraTextureListener implements android.view.TextureView.SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            surface = new Surface(surfaceTexture);
            // 如果相机已经初始化，设置预览
            if (uvcCamera != null) {
                try {
                    uvcCamera.setPreviewDisplay(surface);
                    uvcCamera.startPreview();
                    binding.captureButton.setEnabled(true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (viewActive) {
                requestSelectedDevice();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // 预览尺寸变化时的处理
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            releaseCamera();
            permissionRequested = false;
            if (surface != null) {
                surface.release();
                surface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            // 预览更新时的处理
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

        // 设置状态栏透明和深色图标
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);
    }
    
    @Override
    public void onPause() {
        viewActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        try {
            if (usbMonitor != null) {
                usbMonitor.unregister();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        releaseCamera();

        super.onPause();
    }

    @Override
    public void onDestroyView() {
        viewActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        // 恢复竖屏和系统UI
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        SystemBarUtils.exitFullScreenImmersive(requireActivity());

        try {
            if (usbMonitor != null) {
                usbMonitor.destroy();
                usbMonitor = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if (surface != null) {
                surface.release();
                surface = null;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        binding = null;
        super.onDestroyView();
    }
}

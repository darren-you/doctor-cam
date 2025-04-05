package cn.darrenyou.doctorcam;

import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
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
    private Surface surface;
    
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

        // 强制横屏并设置全屏模式（允许延伸到刘海区域）
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        SystemBarUtils.setFullScreenImmersive(requireActivity(), true);

        // 设置状态栏透明和深色图标
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);

        // 初始化时禁用两个按钮
        binding.captureButton.setEnabled(false);
        binding.buttonSecond.setEnabled(false);

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
            if (uvcCamera != null) {
                try {
                    uvcCamera.stopPreview();
                    uvcCamera.close();
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
            NavHostFragment.findNavController(UvcPreviewFragment.this)
                    .navigate(R.id.action_UvcPreviewFragment_to_UvcDevicesFragment);
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
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // 预览尺寸变化时的处理
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
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
        if (usbMonitor != null) {
            usbMonitor.register();
        }

        // 设置状态栏透明和深色图标
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), true);
        SystemBarUtils.transparentNavigationBar(requireActivity());
        SystemBarUtils.setNavigationBarButtonColor(requireActivity(), true);
    }
    
    @Override
    public void onPause() {
        try {
            if (usbMonitor != null) {
                usbMonitor.unregister();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // 在 super.onPause() 之前释放相机资源
        UVCCamera camera = uvcCamera;  // 创建本地引用
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.close();
                camera.destroy();
            } catch (final Exception e) {
                e.printStackTrace();
            } finally {
                uvcCamera = null;
            }
        }

        super.onPause();
    }

    @Override
    public void onDestroyView() {
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
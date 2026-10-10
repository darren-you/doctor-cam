package cn.darrenyou.doctorcam;

import android.content.Context;
import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import java.util.ArrayList;
import java.util.List;

import cn.darrenyou.doctorcam.databinding.FragmentUvcDevicesBinding;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;
import cn.darrenyou.doctorcam.manager.UsbDeviceManager;

public class UvcDevicesFragment extends Fragment implements UsbDeviceManager.DeviceListListener {

    private FragmentUvcDevicesBinding binding;
    private UsbDeviceManager usbDeviceManager;
    private DeviceListAdapter adapter;
    private final List<UsbDeviceManager.UsbDeviceInfo> deviceList = new ArrayList<>();
    private Bundle pendingNavigationArguments;
    private int pendingNavigationAction;
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    navigateToSelectedDevice();
                } else {
                    pendingNavigationArguments = null;
                    if (binding != null) {
                        android.widget.Toast.makeText(requireContext(), "UVC 摄像头需要相机权限", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentUvcDevicesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 设置状态栏占位View的高度
        View statusBarHeight = view.findViewById(R.id.status_bar_height);
        ViewGroup.LayoutParams params = statusBarHeight.getLayoutParams();
        params.height = SystemBarUtils.getStatusBarHeight(requireContext());
        statusBarHeight.setLayoutParams(params);

        // 设置状态栏透明和深色图标
        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), false);
        SystemBarUtils.transparentNavigationBar(requireActivity());

        // 初始化USB设备管理器
        usbDeviceManager = new UsbDeviceManager(requireContext());
        usbDeviceManager.setDeviceListListener(this);
        
        // 初始化列表适配器
        adapter = new DeviceListAdapter(requireContext(), deviceList);
        binding.deviceListView.setAdapter(adapter);
        
        // 设置列表项点击和长按事件
        binding.deviceListView.setOnItemClickListener((parent, view1, position, id) -> {
            navigateToPreview(position, R.id.action_UvcDevicesFragment_to_UvcPreviewFragment);
        });
        
        binding.deviceListView.setOnItemLongClickListener((parent, view1, position, id) -> {
            // onnx推理
//            navigateToPreview(position, R.id.action_UvcDevicesFragment_to_OnnxDetectionFragment);
//            // PaddleLite推理
            navigateToPreview(position, R.id.action_UvcDevicesFragment_to_PaddleDetectionFragment);
            return true;
        });
        
        // 更新设备列表
        updateEmptyView();
    }

    // 添加导航辅助方法
    private void navigateToPreview(int position, int actionId) {
        UsbDeviceManager.UsbDeviceInfo deviceInfo = deviceList.get(position);
        
        // 传递设备信息到目标Fragment
        Bundle args = new Bundle();
        args.putInt("deviceId", deviceInfo.getDeviceId());
        args.putInt("vendorId", deviceInfo.getVendorId());
        args.putInt("productId", deviceInfo.getProductId());

        pendingNavigationArguments = args;
        pendingNavigationAction = actionId;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            navigateToSelectedDevice();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void navigateToSelectedDevice() {
        if (binding == null || pendingNavigationArguments == null) {
            return;
        }
        int selectedDeviceId = pendingNavigationArguments.getInt("deviceId");
        for (UsbDeviceManager.UsbDeviceInfo device : deviceList) {
            if (device.getDeviceId() == selectedDeviceId) {
                Bundle arguments = pendingNavigationArguments;
                pendingNavigationArguments = null;
                // USB 权限由目标页面的 monitor 请求，避免导航时丢失授权回调。
                NavHostFragment.findNavController(this).navigate(pendingNavigationAction, arguments);
                return;
            }
        }
        pendingNavigationArguments = null;
        android.widget.Toast.makeText(requireContext(), "所选摄像头已断开", android.widget.Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        if (usbDeviceManager != null) {
            usbDeviceManager.register();
        }

        // 恢复竖屏和系统UI
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        SystemBarUtils.exitFullScreenImmersive(requireActivity());

        SystemBarUtils.transparentStatusBar(requireActivity());
        SystemBarUtils.setStatusBarTextColor(requireActivity(), false);
        SystemBarUtils.transparentNavigationBar(requireActivity());
    }
    
    @Override
    public void onPause() {
        super.onPause();
        if (usbDeviceManager != null) {
            usbDeviceManager.unregister();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (usbDeviceManager != null) {
            usbDeviceManager.release();
            usbDeviceManager = null;
        }
        pendingNavigationArguments = null;
        binding = null;
    }
    
    @Override
    public void onDeviceListUpdated(List<UsbDeviceManager.UsbDeviceInfo> devices) {
        // 确保在主线程中执行UI更新
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                deviceList.clear();
                deviceList.addAll(devices);
                adapter.notifyDataSetChanged();
                updateEmptyView();
            });
        }
    }
    
    private void updateEmptyView() {
        if (deviceList.isEmpty()) {
            binding.emptyView.setVisibility(View.VISIBLE);
            binding.deviceListView.setVisibility(View.GONE);
        } else {
            binding.emptyView.setVisibility(View.GONE);
            binding.deviceListView.setVisibility(View.VISIBLE);
        }
    }
    
    // 设备列表适配器
    private static class DeviceListAdapter extends ArrayAdapter<UsbDeviceManager.UsbDeviceInfo> {
        
        public DeviceListAdapter(Context context, List<UsbDeviceManager.UsbDeviceInfo> devices) {
            super(context, 0, devices);
        }
        
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(
                        R.layout.item_uvc_device, parent, false);
            }
            
            UsbDeviceManager.UsbDeviceInfo device = getItem(position);
            
            TextView deviceName = convertView.findViewById(R.id.device_name);
            TextView deviceInfo = convertView.findViewById(R.id.device_info);
            
            if (device != null) {
                deviceName.setText(device.getProductName());
                deviceInfo.setText(device.toString());
            }
            
            return convertView;
        }
    }
}

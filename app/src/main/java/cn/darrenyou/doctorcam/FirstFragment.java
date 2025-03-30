package cn.darrenyou.doctorcam;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import java.util.ArrayList;
import java.util.List;

import cn.darrenyou.doctorcam.databinding.FragmentFirstBinding;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;
import cn.darrenyou.doctorcam.utils.UsbDeviceManager;

public class FirstFragment extends Fragment implements UsbDeviceManager.DeviceListListener {

    private FragmentFirstBinding binding;
    private UsbDeviceManager usbDeviceManager;
    private DeviceListAdapter adapter;
    private final List<UsbDeviceManager.UsbDeviceInfo> deviceList = new ArrayList<>();

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
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
            navigateToPreview(position, R.id.action_FirstFragment_to_SecondFragment);
        });
        
        binding.deviceListView.setOnItemLongClickListener((parent, view1, position, id) -> {
            navigateToPreview(position, R.id.action_FirstFragment_to_VideoStreamFragment);
            return true;  // 返回true表示已处理长按事件
        });
        
        // 更新设备列表
        updateEmptyView();
    }

    // 添加导航辅助方法
    private void navigateToPreview(int position, int actionId) {
        UsbDeviceManager.UsbDeviceInfo deviceInfo = deviceList.get(position);
        
        // 请求USB设备权限
        usbDeviceManager.requestPermission(deviceInfo.getDevice());

        // 传递设备信息到目标Fragment
        Bundle args = new Bundle();
        args.putInt("deviceId", deviceInfo.getDeviceId());
        args.putInt("vendorId", deviceInfo.getVendorId());
        args.putInt("productId", deviceInfo.getProductId());

        // 导航到目标页面
        NavHostFragment.findNavController(FirstFragment.this)
                .navigate(actionId, args);
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
        }
        binding = null;
    }
    
    @Override
    public void onDeviceListUpdated(List<UsbDeviceManager.UsbDeviceInfo> devices) {
        // 确保在主线程中执行UI更新
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
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
                        R.layout.item_device, parent, false);
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
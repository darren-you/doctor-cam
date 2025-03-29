package cn.darrenyou.doctorcam.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionUtils {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // 需要申请的权限列表
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };
    
    // Android 13及以上需要的权限
    private static final String[] REQUIRED_PERMISSIONS_API_33 = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES
    };
    
    // 请求所有必要权限
    public static void requestAllPermissions(Activity activity) {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? 
                REQUIRED_PERMISSIONS_API_33 : REQUIRED_PERMISSIONS;
        
        List<String> permissionsToRequest = new ArrayList<>();
        
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(activity, 
                    permissionsToRequest.toArray(new String[0]), 
                    PERMISSION_REQUEST_CODE);
        }
    }
    
    // 检查是否已授予所有权限
    public static boolean hasAllPermissions(Context context) {
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? 
                REQUIRED_PERMISSIONS_API_33 : REQUIRED_PERMISSIONS;
                
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != 
                    PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
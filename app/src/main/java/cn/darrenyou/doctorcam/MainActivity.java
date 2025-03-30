package cn.darrenyou.doctorcam;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;

import cn.darrenyou.doctorcam.databinding.ActivityMainBinding;
import cn.darrenyou.doctorcam.utils.OpenCVInitializer;
import cn.darrenyou.doctorcam.utils.PermissionUtils;
import cn.darrenyou.doctorcam.utils.SystemBarUtils;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置导航栏完全透明
        SystemBarUtils.transparentStatusBar(this);
        SystemBarUtils.setStatusBarTextColor(this, false);
        SystemBarUtils.transparentNavigationBar(this);
        SystemBarUtils.setNavigationBarButtonColor(this, false);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化 OpenCV
        if (!OpenCVInitializer.isInitialized()) {
            OpenCVInitializer.initAsync(this, success -> {
                if (success) {
                    Log.d(TAG, "OpenCV 初始化成功");
                    // 在这里可以启用依赖 OpenCV 的功能
                } else {
                    Log.e(TAG, "OpenCV 初始化失败");
                    // 显示错误信息或禁用依赖 OpenCV 的功能
                    Toast.makeText(this, "OpenCV 初始化失败，部分功能可能无法正常工作", Toast.LENGTH_LONG).show();
                }
            });
        } else {
            Log.d(TAG, "OpenCV 已经初始化");
        }

        // 初始化导航控制器
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_main);
        
        // 请求必要权限
        PermissionUtils.requestAllPermissions(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
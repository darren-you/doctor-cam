package cn.darrenyou.doctorcam.utils;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

public class SystemBarUtils {

    /**
     * 设置状态栏透明
     */
    public static void transparentStatusBar(Activity activity) {
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        
        int systemUiVisibility = window.getDecorView().getSystemUiVisibility();
        systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN 
                          | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        window.setStatusBarColor(Color.TRANSPARENT);
        
        // 默认根据日间/夜间模式设置状态栏文字颜色
        setStatusBarTextColor(activity, isDarkMode(activity));
    }

    /**
     * 设置导航栏透明
     */
    public static void transparentNavigationBar(Activity activity) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        
        int systemUiVisibility = window.getDecorView().getSystemUiVisibility();
        systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        window.setNavigationBarColor(Color.TRANSPARENT);
        
        // 默认根据日间/夜间模式设置导航栏按钮颜色
        setNavigationBarButtonColor(activity, isDarkMode(activity));
    }

    /**
     * 设置状态栏文字颜色
     * @param light true为白色文字，false为黑色文字
     */
    public static void setStatusBarTextColor(Activity activity, boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Window window = activity.getWindow();
            int systemUiVisibility = window.getDecorView().getSystemUiVisibility();
            if (light) {
                systemUiVisibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        }
    }

    /**
     * 设置导航栏按钮颜色
     * @param light true为白色按钮，false为黑色按钮
     */
    public static void setNavigationBarButtonColor(Activity activity, boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Window window = activity.getWindow();
            int systemUiVisibility = window.getDecorView().getSystemUiVisibility();
            if (light) {
                systemUiVisibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                systemUiVisibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(systemUiVisibility);
        }
    }

    /**
     * 获取状态栏高度
     */
    public static int getStatusBarHeight(Context context) {
        int resourceId = context.getResources().getIdentifier(
                "status_bar_height", "dimen", "android");
        return resourceId > 0 ? 
                context.getResources().getDimensionPixelSize(resourceId) : 0;
    }

    /**
     * 为View添加状态栏高度的上边距
     */
    public static void addStatusBarMargin(View... views) {
        for (View view : views) {
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams marginParams = 
                        (ViewGroup.MarginLayoutParams) layoutParams;
                marginParams.topMargin += getStatusBarHeight(view.getContext());
                view.requestLayout();
            }
        }
    }

    /**
     * 为View添加状态栏高度的上内边距
     */
    public static void addStatusBarPadding(View view) {
        view.setPadding(
                view.getPaddingLeft(),
                view.getPaddingTop() + getStatusBarHeight(view.getContext()),
                view.getPaddingRight(),
                view.getPaddingBottom()
        );
    }

    /**
     * 判断是否为夜间模式
     */
    private static boolean isDarkMode(Context context) {
        // 这里简单返回false，实际使用时可以根据具体需求判断
        return false;
    }

     /**
     * 设置全屏沉浸式模式
     * @param activity Activity实例
     * @param extendToCutout 是否延伸到刘海区域
     */
    public static void setFullScreenImmersive(Activity activity, boolean extendToCutout) {
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        
        // 设置是否允许内容延伸到刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = extendToCutout ? 
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES :
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(lp);
        }
        
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    /**
     * 设置全屏沉浸式模式（默认不延伸到刘海区域）
     */
    public static void setFullScreenImmersive(Activity activity) {
        setFullScreenImmersive(activity, false);
    }

    /**
     * 退出全屏模式，恢复系统UI显示
     */
    public static void exitFullScreenImmersive(Activity activity) {
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    /**
     * 检查当前是否处于全屏模式
     */
    public static boolean isFullScreenImmersive(Activity activity) {
        int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
        return ((flags | View.SYSTEM_UI_FLAG_FULLSCREEN) == flags) &&
               ((flags | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == flags);
    }
}
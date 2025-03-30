package cn.darrenyou.doctorcam.utils;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

/**
 * UI工具类，提供UI相关的通用操作
 */
public class UiUtils {
    
    /**
     * 设置横屏模式并配置全屏沉浸式状态栏
     * @param activity 活动
     * @param darkStatusBar 是否使用深色状态栏图标
     */
    public static void setupLandscapeFullscreen(Activity activity, boolean darkStatusBar) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        SystemBarUtils.setFullScreenImmersive(activity, true);
        
        SystemBarUtils.transparentStatusBar(activity);
        SystemBarUtils.setStatusBarTextColor(activity, darkStatusBar);
        SystemBarUtils.transparentNavigationBar(activity);
        SystemBarUtils.setNavigationBarButtonColor(activity, darkStatusBar);
    }
    
    /**
     * 创建用于绘制检测框的画笔
     * @param color 颜色
     * @param strokeWidth 线宽
     * @return 画笔对象
     */
    public static Paint createBoxPaint(int color, float strokeWidth) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        return paint;
    }
    
    /**
     * 创建用于绘制文本的画笔
     * @param color 颜色
     * @param textSize 文本大小
     * @return 画笔对象
     */
    public static Paint createTextPaint(int color, float textSize) {
        Paint paint = new Paint();
        paint.setColor(color);
        paint.setTextSize(textSize);
        return paint;
    }
    
    /**
     * 显示Toast消息
     * @param context 上下文
     * @param message 消息内容
     * @param duration 显示时长
     */
    public static void showToast(Context context, String message, int duration) {
        Toast.makeText(context, message, duration).show();
    }
    
    /**
     * 在Fragment中安全地运行UI线程代码
     * @param fragment Fragment对象
     * @param runnable 要运行的代码
     */
    public static void runOnUiThreadSafely(Fragment fragment, Runnable runnable) {
        if (fragment != null && fragment.isAdded() && fragment.getActivity() != null && !fragment.getActivity().isFinishing()) {
            fragment.requireActivity().runOnUiThread(runnable);
        }
    }
    
    /**
     * 更新视图可见性
     * @param view 视图
     * @param visible 是否可见
     */
    public static void setViewVisibility(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
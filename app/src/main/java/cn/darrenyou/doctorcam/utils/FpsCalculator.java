package cn.darrenyou.doctorcam.utils;

import android.os.SystemClock;

/**
 * 帧率计算工具类
 */
public class FpsCalculator {
    private static final int FPS_UPDATE_INTERVAL_MS = 1000; // 1秒更新一次FPS
    
    private int frameCount = 0;
    private long lastFpsUpdateTime = 0;
    private float currentFps = 0;
    
    public FpsCalculator() {
        reset();
    }
    
    /**
     * 重置计算器
     */
    public void reset() {
        frameCount = 0;
        lastFpsUpdateTime = SystemClock.elapsedRealtime();
        currentFps = 0;
    }
    
    /**
     * 更新帧计数并计算FPS
     * @return 如果FPS值已更新则返回true
     */
    public boolean update() {
        frameCount++;
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastFpsUpdateTime;
        
        if (elapsed >= FPS_UPDATE_INTERVAL_MS) {
            currentFps = (float) frameCount * 1000 / elapsed;
            frameCount = 0;
            lastFpsUpdateTime = now;
            return true;
        }
        
        return false;
    }
    
    /**
     * 获取当前FPS值
     */
    public float getCurrentFps() {
        return currentFps;
    }
    
    /**
     * 获取格式化的FPS字符串
     */
    public String getFormattedFps() {
        return String.format("%.1f FPS", currentFps);
    }
}
package cn.darrenyou.doctorcam.base;

import android.graphics.RectF;

public class BaseDetection {
    public float confidence;
    public RectF bbox;
    public String className;
    public int originalImageWidth;
    public int originalImageHeight;

    public BaseDetection(float confidence, RectF bbox, String className) {
        this.confidence = confidence;
        this.bbox = bbox;
        this.className = className;
        this.originalImageWidth = 0;  // 默认值
        this.originalImageHeight = 0; // 默认值
    }

    public BaseDetection(float confidence, RectF bbox, String className, int originalImageWidth, int originalImageHeight) {
        this.confidence = confidence;
        this.bbox = bbox;
        this.className = className;
        this.originalImageWidth = originalImageWidth;
        this.originalImageHeight = originalImageHeight;
    }
}

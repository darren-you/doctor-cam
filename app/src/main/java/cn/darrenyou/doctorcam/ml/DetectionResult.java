package cn.darrenyou.doctorcam.ml;

import android.graphics.RectF;

public class DetectionResult {
    private final RectF location;
    private final String label;
    private final float confidence;

    public DetectionResult(RectF location, String label, float confidence) {
        this.location = location;
        this.label = label;
        this.confidence = confidence;
    }

    public RectF getLocation() { return location; }
    public String getLabel() { return label; }
    public float getConfidence() { return confidence; }
}
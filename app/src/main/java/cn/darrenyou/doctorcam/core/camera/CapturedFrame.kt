package cn.darrenyou.doctorcam.core.camera

import android.graphics.Bitmap
import cn.darrenyou.doctorcam.core.inference.Detection

/** 独立位图及同一帧的检测结果；调用方持有位图，由媒体保存入口消费并回收。 */
data class CapturedFrame(
    val bitmap: Bitmap,
    val detections: List<Detection>,
)

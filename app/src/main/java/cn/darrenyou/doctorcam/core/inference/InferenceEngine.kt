package cn.darrenyou.doctorcam.core.inference

import android.graphics.Bitmap
import java.io.Closeable

interface InferenceEngine : Closeable {
    fun detect(bitmap: Bitmap): List<Detection>
}

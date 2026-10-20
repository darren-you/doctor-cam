package com.silicicsoft.doctorcam.core.inference

import android.content.Context
import com.silicicsoft.doctorcam.utils.OpenCVInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class InferenceEngineFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun create(mode: DetectionMode): InferenceEngine? {
        OpenCVInitializer.initAsync(context, null)
        check(OpenCVInitializer.isInitialized()) { "OpenCV 初始化失败" }
        return when (mode) {
            DetectionMode.PREVIEW -> null
            DetectionMode.PADDLE -> PaddleInferenceEngine(context)
            DetectionMode.ONNX -> OnnxInferenceEngine(context)
        }
    }
}

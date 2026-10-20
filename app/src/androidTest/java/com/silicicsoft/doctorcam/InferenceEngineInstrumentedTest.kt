package com.silicicsoft.doctorcam

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.silicicsoft.doctorcam.core.inference.Detection
import com.silicicsoft.doctorcam.core.inference.DetectionMode
import com.silicicsoft.doctorcam.core.inference.InferenceEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 直接运行重构后的真实引擎和包内原模型；合成图像不提供检测准确率证据。 */
@RunWith(AndroidJUnit4::class)
class InferenceEngineInstrumentedTest {
    @Test
    fun paddleWrapperRunsPackagedModelAndRebuildsAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels = context.assets.open("paddle/doctor_label_list.txt").bufferedReader(Charsets.UTF_8)
            .use { it.readLines().filter(String::isNotBlank) }
        exerciseEngine(DetectionMode.PADDLE, labels, minimumConfidence = 0.5f)
    }

    @Test
    fun hiddenOnnxWrapperRunsPackagedModelAndRebuildsAfterClose() {
        exerciseEngine(
            DetectionMode.ONNX,
            labels = listOf("声门裂", "杓状软骨", "混合识别"),
            minimumConfidence = 0.1f,
        )
    }

    private fun exerciseEngine(mode: DetectionMode, labels: List<String>, minimumConfidence: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = InferenceEngineFactory(context)
        assertNull("普通预览不需要实例化推理引擎", factory.create(DetectionMode.PREVIEW))
        val square = createBitmap(320, 320)
        val landscape = createBitmap(641, 359)
        val engine = requireNotNull(factory.create(mode))
        try {
            assertDetections(engine.detect(square), square, labels, minimumConfidence)
            assertDetections(engine.detect(landscape), landscape, labels, minimumConfidence)
            engine.close()
            engine.close()
            assertThrows("已关闭的引擎必须拒绝继续推理", IllegalStateException::class.java) {
                engine.detect(square)
            }

            val reopenedEngine = requireNotNull(factory.create(mode))
            try {
                assertNotSame("重新创建必须得到独立引擎", engine, reopenedEngine)
                assertDetections(reopenedEngine.detect(landscape), landscape, labels, minimumConfidence)
            } finally {
                reopenedEngine.close()
                reopenedEngine.close()
            }
        } finally {
            engine.close()
            square.recycle()
            landscape.recycle()
        }
    }

    private fun createBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb(x * 255 / width, y * 255 / height, (x + y) * 255 / (width + height))
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun assertDetections(
        detections: List<Detection>,
        source: Bitmap,
        labels: List<String>,
        minimumConfidence: Float,
    ) {
        for (detection in detections) {
            assertTrue(detection.classId in labels.indices)
            assertEquals(labels[detection.classId], detection.className)
            assertEquals(source.width, detection.sourceWidth)
            assertEquals(source.height, detection.sourceHeight)
            assertTrue(detection.confidence.isFinite())
            assertTrue(detection.confidence in minimumConfidence..1f)
            assertTrue(detection.left.isFinite() && detection.top.isFinite())
            assertTrue(detection.right.isFinite() && detection.bottom.isFinite())
            assertTrue(detection.left in 0f..source.width.toFloat())
            assertTrue(detection.top in 0f..source.height.toFloat())
            assertTrue(detection.right in detection.left..source.width.toFloat())
            assertTrue(detection.bottom in detection.top..source.height.toFloat())
        }
    }
}

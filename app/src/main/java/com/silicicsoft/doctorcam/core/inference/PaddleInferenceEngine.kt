package com.silicicsoft.doctorcam.core.inference

import android.content.Context
import android.graphics.Bitmap
import com.silicicsoft.doctorcam.paddle.PaddleDetector
import java.io.File
import java.util.UUID

class PaddleInferenceEngine(context: Context) : InferenceEngine {
    private val detector = PaddleDetector()
    private val labels: List<String>
    private val directory = File(context.cacheDir, "paddle-${UUID.randomUUID()}")
    private var closed = false

    init {
        try {
            check(directory.mkdirs()) { "无法创建模型缓存目录" }
            val model = File(directory, "model.nb")
            val labelFile = File(directory, "doctor_label_list.txt")
            context.assets.open("paddle/model.nb").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
            context.assets.open("paddle/doctor_label_list.txt").use { input ->
                labelFile.outputStream().use { output -> input.copyTo(output) }
            }
            labels = labelFile.readLines(Charsets.UTF_8)
            check(labels.any { it.isNotBlank() }) { "模型标签为空" }
            check(detector.initDefault(model.absolutePath, labelFile.absolutePath, "LITE_POWER_HIGH")) {
                "Paddle 模型初始化失败"
            }
        } catch (error: Exception) {
            releaseAfterFailedInitialization(error)
            throw error
        } catch (error: LinkageError) {
            releaseAfterFailedInitialization(error)
            throw error
        }
    }

    @Synchronized
    override fun detect(bitmap: Bitmap): List<Detection> {
        check(!closed) { "Paddle 会话已释放" }
        return detector.processWithResults(bitmap).map { result ->
            check(result.size == 6 && result.all { it.isFinite() }) { "Paddle 检测结果格式错误" }
            val classId = result[0].toInt()
            check(classId in labels.indices && labels[classId].isNotBlank()) { "Paddle 标签索引无效" }
            check(result[1] in 0f..1f && result[4] >= 0 && result[5] >= 0) { "Paddle 检测框或置信度无效" }
            Detection(
                classId = classId,
                className = labels[classId],
                confidence = result[1],
                left = result[2].coerceIn(0f, bitmap.width.toFloat()),
                top = result[3].coerceIn(0f, bitmap.height.toFloat()),
                right = (result[2] + result[4]).coerceIn(0f, bitmap.width.toFloat()),
                bottom = (result[3] + result[5]).coerceIn(0f, bitmap.height.toFloat()),
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
            )
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            try {
                detector.release()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun releaseAfterFailedInitialization(error: Throwable) {
        try {
            detector.release()
        } catch (cleanupError: Exception) {
            error.addSuppressed(cleanupError)
        } catch (cleanupError: LinkageError) {
            error.addSuppressed(cleanupError)
        } finally {
            directory.deleteRecursively()
        }
    }
}

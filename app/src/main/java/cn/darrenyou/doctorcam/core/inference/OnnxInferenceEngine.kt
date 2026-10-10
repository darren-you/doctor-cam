package cn.darrenyou.doctorcam.core.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.system.Os
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OnnxInferenceEngine(context: Context) : InferenceEngine {
    private val environment = run {
        // 官方进程级禁用入口必须先于首次 ORT 初始化，端侧推理不创建遥测上传器。
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        OrtEnvironment.getEnvironment()
    }
    private val session: OrtSession = context.assets.open(MODEL_FILE).use {
        environment.createSession(it.readBytes())
    }
    private var closed = false

    @Synchronized
    override fun detect(bitmap: Bitmap): List<Detection> {
        check(!closed) { "ONNX 会话已释放" }
        val scale = min(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val resizedWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val offsetX = (INPUT_SIZE - resizedWidth) / 2f
        val offsetY = (INPUT_SIZE - resizedHeight) / 2f
        val input = preprocess(bitmap, resizedWidth, resizedHeight, offsetX, offsetY)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 320, 320)).use { tensor ->
            session.run(mapOf("images" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result[0].value as? Array<Array<FloatArray>>
                    ?: error("ONNX 模型输出格式错误")
                check(output.size == 1) { "ONNX 模型批次不符合单帧合同" }
                val candidates = output[0].mapNotNull { row ->
                    check(row.size >= 5 + LABELS.size) { "ONNX 检测结果字段缺失" }
                    if (!row.take(5 + LABELS.size).all { it.isFinite() }) return@mapNotNull null
                    val classId = LABELS.indices.maxBy { row[5 + it] }
                    val confidence = row[4] * row[5 + classId]
                    if (row[4] <= CONFIDENCE_THRESHOLD || confidence <= CONFIDENCE_THRESHOLD
                        || row[2] <= 0 || row[3] <= 0
                    ) return@mapNotNull null
                    Detection(
                        classId = classId,
                        className = LABELS[classId],
                        confidence = confidence,
                        left = row[0] - row[2] / 2,
                        top = row[1] - row[3] / 2,
                        right = row[0] + row[2] / 2,
                        bottom = row[1] + row[3] / 2,
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                    )
                }.sortedByDescending { it.confidence }
                val kept = mutableListOf<Detection>()
                for (candidate in candidates) {
                    if (kept.none { it.classId == candidate.classId && intersectionOverUnion(it, candidate) > IOU_THRESHOLD }) {
                        kept += candidate
                    }
                }
                return kept.map { detection ->
                    detection.copy(
                        left = ((detection.left - offsetX) / scale).coerceIn(0f, bitmap.width.toFloat()),
                        top = ((detection.top - offsetY) / scale).coerceIn(0f, bitmap.height.toFloat()),
                        right = ((detection.right - offsetX) / scale).coerceIn(0f, bitmap.width.toFloat()),
                        bottom = ((detection.bottom - offsetY) / scale).coerceIn(0f, bitmap.height.toFloat()),
                    )
                }
            }
        }
    }

    private fun preprocess(bitmap: Bitmap, width: Int, height: Int, offsetX: Float, offsetY: Float): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val padded = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            padded.eraseColor(Color.rgb(114, 114, 114))
            Canvas(padded).drawBitmap(resized, offsetX, offsetY, null)
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            padded.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            return FloatArray(pixels.size * 3).also { input ->
                pixels.forEachIndexed { index, pixel ->
                    input[index] = Color.red(pixel) / 255f
                    input[pixels.size + index] = Color.green(pixel) / 255f
                    input[pixels.size * 2 + index] = Color.blue(pixel) / 255f
                }
            }
        } finally {
            padded.recycle()
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val intersection = max(0f, min(first.right, second.right) - max(first.left, second.left)) *
            max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val union = (first.right - first.left) * (first.bottom - first.top) +
            (second.right - second.left) * (second.bottom - second.top) - intersection
        return if (union > 0) intersection / union else 0f
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            session.close()
        }
        // OrtEnvironment 是进程共享实例，当前引擎只拥有自身 session。
    }

    private companion object {
        const val MODEL_FILE = "onnx/best_320x320.onnx"
        const val INPUT_SIZE = 320
        const val CONFIDENCE_THRESHOLD = 0.1f
        const val IOU_THRESHOLD = 0.6f
        val LABELS = listOf("声门裂", "杓状软骨", "混合识别")
    }
}

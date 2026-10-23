package com.silicicsoft.doctorcam.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.silicicsoft.doctorcam.core.camera.CapturedFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class MediaRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 消费控制器捕获的独立帧；保存成功或失败都回收位图，返回结果由 ViewModel 呈现。 */
    suspend fun save(frame: CapturedFrame): Result<Uri> {
        try {
            return withContext(Dispatchers.IO) {
                try {
                    coroutineContext.ensureActive()
                    drawDetections(frame)
                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
                    val fileName = "uvc-vision-$timestamp.jpg"
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveWithMediaStore(frame.bitmap, fileName) { coroutineContext.ensureActive() }
                    } else {
                        saveLegacy(frame.bitmap, fileName) { coroutineContext.ensureActive() }
                    }
                    Result.success(uri)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        } finally {
            frame.bitmap.recycle()
        }
    }

    private fun drawDetections(frame: CapturedFrame) {
        val canvas = Canvas(frame.bitmap)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
        }
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        for (detection in frame.detections) {
            val color = colors[detection.classId.coerceAtLeast(0) % colors.size]
            boxPaint.color = color
            backgroundPaint.color = color
            canvas.drawRect(detection.left, detection.top, detection.right, detection.bottom, boxPaint)
            val text = String.format(Locale.ROOT, "%s %.2f", detection.className, detection.confidence)
            val baseline = (detection.top - 4f).coerceAtLeast(textPaint.textSize)
            canvas.drawRect(
                detection.left,
                baseline - textPaint.textSize - 4f,
                detection.left + textPaint.measureText(text) + 8f,
                baseline + 4f,
                backgroundPaint,
            )
            canvas.drawText(text, detection.left + 4f, baseline, textPaint)
        }
    }

    private fun saveWithMediaStore(bitmap: Bitmap, fileName: String, checkCancellation: () -> Unit): Uri {
        checkCancellation()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建相册图片")
        var committed = false
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("无法打开相册图片")
            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) throw IOException("JPEG 编码失败")
            }
            checkCancellation()
            val finished = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, finished, null, null) != 1) throw IOException("无法完成相册图片保存")
            committed = true
            return uri
        } finally {
            if (!committed) resolver.delete(uri, null, null)
        }
    }

    private fun saveLegacy(bitmap: Bitmap, fileName: String, checkCancellation: () -> Unit): Uri {
        checkCancellation()
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建相册目录")
        val file = File(directory, fileName)
        var committed = false
        try {
            file.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) throw IOException("JPEG 编码失败")
            }
            checkCancellation()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法登记相册图片")
            committed = true
            return uri
        } finally {
            if (!committed) file.delete()
        }
    }
}

package com.silicicsoft.doctorcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.silicicsoft.doctorcam.core.camera.CapturedFrame
import com.silicicsoft.doctorcam.core.inference.Detection
import com.silicicsoft.doctorcam.core.media.MediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证独立相册写入与绘制合同；只清理本测试返回的 URI，不操作既有图片。 */
@RunWith(AndroidJUnit4::class)
class MediaRepositoryInstrumentedTest {
    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun savesAndReadsBackOnlyItsOwnImageWithDetectionOverlay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(120, 120, 120)) }
        val detection = Detection(
            classId = 0,
            className = "TEST",
            confidence = 0.9f,
            left = 40f,
            top = 80f,
            right = 240f,
            bottom = 200f,
            sourceWidth = 320,
            sourceHeight = 240,
        )
        var createdUri: Uri? = null
        var decoded: Bitmap? = null
        try {
            val uri = runBlocking {
                MediaRepository(context).save(CapturedFrame(source, listOf(detection))).getOrThrow()
            }
            createdUri = uri
            assertTrue("保存入口必须消费并回收独立输入位图", source.isRecycled)
            requireNotNull(resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.IS_PENDING),
                null,
                null,
                null,
            )).use { cursor ->
                assertTrue("保存后的 URI 必须可以查询", cursor.moveToFirst())
                assertEquals("image/jpeg", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)))
                assertEquals("完成写入后必须清除 IS_PENDING", 0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)))
            }
            val image = requireNotNull(resolver.openInputStream(uri)).use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
            decoded = image
            assertEquals(320, image.width)
            assertEquals(240, image.height)
            val border = image.getPixel(40, 140)
            assertTrue("存储图片必须带有红色检测框", Color.red(border) >= 200)
            assertTrue("红框色序不能被交换", Color.green(border) < 80 && Color.blue(border) < 80)
            assertEquals(255, Color.alpha(border))
            val background = image.getPixel(300, 220)
            assertTrue("检测框外必须保留原图背景", Color.red(background) in 115..125)
            assertTrue(Color.green(background) in 115..125 && Color.blue(background) in 115..125)
        } finally {
            decoded?.recycle()
            if (!source.isRecycled) source.recycle()
            createdUri?.let {
                assertEquals("只删除本次测试生成的图片", 1, resolver.delete(it, null, null))
            }
        }
    }
}

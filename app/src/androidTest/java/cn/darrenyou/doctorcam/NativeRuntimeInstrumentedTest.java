package cn.darrenyou.doctorcam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.serenegiant.usb.UVCCamera;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import cn.darrenyou.doctorcam.paddle.PaddleDetector;
import cn.darrenyou.doctorcam.utils.ImageProcessingUtils;
import cn.darrenyou.doctorcam.utils.OpenCVInitializer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 在真实 Android 运行时验证打包的 Native 库、既有模型及资源释放。
 * 合成图像只用于执行推理链路，不验证医学识别准确率；UVC 不打开虚构 USB 设备。
 */
@RunWith(AndroidJUnit4.class)
public class NativeRuntimeInstrumentedTest {
    @Test
    public void openCvLoadsAndExecutesNativeOperation() {
        assertTrue("OpenCV Native 库必须可以从应用包加载", OpenCVLoader.initDebug());
        Mat identity = Mat.eye(3, 3, CvType.CV_32FC1);
        try {
            assertEquals(3, identity.rows());
            assertEquals(3, identity.cols());
            assertEquals(3.0, Core.sumElems(identity).val[0], 0.0);
        } finally {
            identity.release();
        }
    }

    @Test
    public void nv21ConversionPreservesRedAndBorrowedBufferState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch initialized = new CountDownLatch(1);
        AtomicBoolean initializationSucceeded = new AtomicBoolean();
        OpenCVInitializer.initAsync(context, success -> {
            initializationSucceeded.set(success);
            initialized.countDown();
        });
        assertTrue("应用 OpenCV 初始化必须完成", initialized.await(10, TimeUnit.SECONDS));
        assertTrue("应用 OpenCV 初始化必须成功", initializationSucceeded.get());
        assertTrue(OpenCVInitializer.isInitialized());

        // 2×2 NV21：四个 Y 后跟 V/U；这是红色样本，能识别此前的红蓝通道互换。
        byte[] redFrame = {82, 82, 82, 82, (byte) 240, 90};
        ByteBuffer borrowedFrame = ByteBuffer.allocateDirect(10);
        borrowedFrame.put((byte) 17).put((byte) 34).put(redFrame);
        borrowedFrame.position(2);
        borrowedFrame.limit(8);

        Bitmap arrayBitmap = ImageProcessingUtils.yuv2BitmapNative(redFrame, 2, 2);
        assertNotNull("数组入口必须生成位图", arrayBitmap);
        try {
            assertRedBitmap(arrayBitmap);
            for (int iteration = 0; iteration < 3; iteration++) {
                Bitmap bufferBitmap = ImageProcessingUtils.getFrameAsBitmap(borrowedFrame, 2, 2);
                try {
                    assertNotNull("Buffer 入口必须从当前 position 转换帧", bufferBitmap);
                    assertRedBitmap(bufferBitmap);
                    assertTrue("两个 NV21 转换入口必须输出相同像素", arrayBitmap.sameAs(bufferBitmap));
                    assertEquals("不能消费借出的 Buffer position", 2, borrowedFrame.position());
                    assertEquals("不能改写借出的 Buffer limit", 8, borrowedFrame.limit());
                } finally {
                    if (bufferBitmap != null) {
                        bufferBitmap.recycle();
                    }
                }
            }

            assertNull("缺少完整 NV21 帧必须失败", ImageProcessingUtils.getFrameAsBitmap(
                    ByteBuffer.wrap(new byte[5]), 2, 2));
            assertNull(ImageProcessingUtils.yuv2BitmapNative(new byte[5], 2, 2));
            assertNull("奇数宽度不是合法 NV21 帧", ImageProcessingUtils.getFrameAsBitmap(
                    borrowedFrame, 3, 2));
            assertNull("奇数高度不是合法 NV21 帧", ImageProcessingUtils.getFrameAsBitmap(
                    borrowedFrame, 2, 3));
            assertNull(ImageProcessingUtils.yuv2BitmapNative(redFrame, 3, 2));
            assertNull(ImageProcessingUtils.yuv2BitmapNative(redFrame, 2, 3));
            assertEquals(2, borrowedFrame.position());
            assertEquals(8, borrowedFrame.limit());
        } finally {
            arrayBitmap.recycle();
        }
    }

    @Test
    public void uvcCreatesAndDestroysNativeContextWithoutUsbDevice() {
        AtomicReference<NativeCamera> cameraReference = new AtomicReference<>();
        // UVCCamera 要求在拥有 Looper 的线程构造，使用真实主线程。
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> cameraReference.set(new NativeCamera()));
        NativeCamera camera = cameraReference.get();
        assertNotNull(camera);
        try {
            assertTrue("UVC Native 构造必须分配上下文", camera.hasNativeContext());
            assertNull("测试没有打开 USB 设备", camera.getDevice());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(camera::destroy);
            assertFalse("destroy 必须清除 Native 上下文", camera.hasNativeContext());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(camera::destroy);
            assertFalse("重复 destroy 必须保持已释放状态", camera.hasNativeContext());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(camera::destroy);
        }
    }

    @Test
    public void paddleRunsPackagedModelRejectsUnsupportedBitmapAndReinitializes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File assetDirectory = File.createTempFile("native-runtime-", "", context.getCacheDir());
        assertTrue(assetDirectory.delete());
        assertTrue(assetDirectory.mkdir());
        File modelFile = new File(assetDirectory, "model.nb");
        File labelFile = new File(assetDirectory, "doctor_label_list.txt");
        PaddleDetector detector = new PaddleDetector();
        Bitmap squareBitmap = createTestBitmap(320, 320);
        Bitmap landscapeBitmap = createTestBitmap(641, 359);
        Bitmap unsupportedBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.RGB_565);
        try {
            // 每次从当前 APK assets 复制，不复用应用页面或前一次运行留下的缓存。
            copyAsset(context, "paddle/model.nb", modelFile);
            copyAsset(context, "paddle/doctor_label_list.txt", labelFile);
            int labelCount = countLabels(labelFile);
            assertTrue("包内模型文件不能为空", modelFile.length() > 0);
            assertTrue("包内标签文件不能为空", labelCount > 0);

            // 与当前 Paddle 页面一致：initDefault 保留 320 输入、默认均值/方差与 0.5 阈值。
            assertTrue("非零 Native 上下文表示初始化成功", detector.initDefault(
                    modelFile.getAbsolutePath(), labelFile.getAbsolutePath(), "LITE_POWER_HIGH"));
            assertValidResults(detector.processWithResults(squareBitmap), labelCount);
            int originalPixel = landscapeBitmap.getPixel(320, 179);
            assertValidResults(detector.processWithResults(landscapeBitmap), labelCount);
            assertEquals("仅返回结果的推理不能改写输入位图", originalPixel,
                    landscapeBitmap.getPixel(320, 179));

            assertThrows("RGB565 不支持时必须明确报告失败", IllegalStateException.class,
                    () -> detector.processWithResults(unsupportedBitmap));
            assertFalse(detector.process(unsupportedBitmap, ""));
            assertValidResults(detector.processWithResults(squareBitmap), labelCount);

            assertTrue("第一次 release 必须释放已初始化的上下文", detector.release());
            assertFalse("重复 release 不能再次释放 Native 指针", detector.release());
            assertEquals("释放后的推理必须安全返回空结果", 0,
                    detector.processWithResults(squareBitmap).length);
            assertFalse(detector.process(squareBitmap, ""));

            assertTrue("释放后应能重新加载同一包内模型", detector.initDefault(
                    modelFile.getAbsolutePath(), labelFile.getAbsolutePath(), "LITE_POWER_HIGH"));
            assertValidResults(detector.processWithResults(landscapeBitmap), labelCount);
            assertTrue(detector.release());
            assertFalse(detector.release());
        } finally {
            detector.release();
            squareBitmap.recycle();
            landscapeBitmap.recycle();
            unsupportedBitmap.recycle();
            modelFile.delete();
            labelFile.delete();
            assetDirectory.delete();
        }
    }

    private static Bitmap createTestBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = Color.rgb(
                        x * 255 / width, y * 255 / height, (x + y) * 255 / (width + height));
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private static void assertRedBitmap(Bitmap bitmap) {
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.getConfig());
        assertEquals(2, bitmap.getWidth());
        assertEquals(2, bitmap.getHeight());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int pixel = bitmap.getPixel(x, y);
                assertTrue("NV21 红色样本必须保留红色通道", Color.red(pixel) >= 245);
                assertTrue("NV21 红色样本不能出现绿色", Color.green(pixel) < 10);
                assertTrue("NV21 红色样本不能变成蓝色", Color.blue(pixel) < 10);
                assertEquals(255, Color.alpha(pixel));
            }
        }
    }

    private static void copyAsset(Context context, String assetPath, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static int countLabels(File labelFile) throws Exception {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(labelFile), StandardCharsets.UTF_8))) {
            String label;
            while ((label = reader.readLine()) != null) {
                // 当前 assets 标签末尾有空行，实际四个类别仍保持原来的顺序与内容。
                if (!label.trim().isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertValidResults(float[][] results, int labelCount) {
        assertNotNull("Native 推理失败不能冒充空检测结果", results);
        for (float[] result : results) {
            assertNotNull(result);
            assertEquals("每项检测必须包含 label、prob、x、y、w、h", 6, result.length);
            for (float value : result) {
                assertFalse("检测结果必须是有限数值", Float.isNaN(value) || Float.isInfinite(value));
            }
            assertEquals((float) (int) result[0], result[0], 0.0f);
            assertTrue("标签索引必须来自当前模型标签", result[0] >= 0 && result[0] < labelCount);
            assertTrue("概率必须满足默认阈值且不超过 1", result[1] >= 0.5f && result[1] <= 1.0f);
            assertTrue("检测框宽度不能为负", result[4] >= 0);
            assertTrue("检测框高度不能为负", result[5] >= 0);
        }
    }

    private static final class NativeCamera extends UVCCamera {
        boolean hasNativeContext() {
            return mNativePtr != 0;
        }
    }
}

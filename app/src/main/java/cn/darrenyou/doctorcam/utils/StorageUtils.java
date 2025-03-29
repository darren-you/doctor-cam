package cn.darrenyou.doctorcam.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StorageUtils {

    // 保存图片到相册
    public static void saveImageToGallery(Context context, Bitmap bitmap) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "DoctorCam_" + timeStamp + ".jpg";
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageOnApi29AndAbove(context, bitmap, imageFileName);
        } else {
            saveImageLegacy(context, bitmap, imageFileName);
        }
    }
    
    // Android 10 (API 29)及以上版本保存图片
    private static void saveImageOnApi29AndAbove(Context context, Bitmap bitmap, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
        
        Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
        
        if (imageUri != null) {
            try (OutputStream outputStream = resolver.openOutputStream(imageUri)) {
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
                    showToastOnMainThread(context, "图片已保存到相册");
                }
            } catch (IOException e) {
                showToastOnMainThread(context, "保存失败: " + e.getMessage());
            }
        } else {
            showToastOnMainThread(context, "保存失败: 无法创建文件");
        }
    }
    
    // 旧版本Android保存图片
    private static void saveImageLegacy(Context context, Bitmap bitmap, String fileName) {
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File imageFile = new File(directory, fileName);
        
        try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            
            // 通知媒体库更新
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DATA, imageFile.getAbsolutePath());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            
            showToastOnMainThread(context, "图片已保存到相册");
        } catch (IOException e) {
            showToastOnMainThread(context, "保存失败: " + e.getMessage());
        }
    }
    
    // 确保Toast在主线程中显示
    private static void showToastOnMainThread(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        });
    }
}
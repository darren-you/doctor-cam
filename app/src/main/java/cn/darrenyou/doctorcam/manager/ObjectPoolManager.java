package cn.darrenyou.doctorcam.manager;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 对象池管理工具类，用于管理Bitmap和ByteBuffer的复用
 */
public class ObjectPoolManager {
    private static final String TAG = "ObjectPoolManager";
    private static final int DEFAULT_MAX_POOL_SIZE = 5;
    
    private final int maxPoolSize;
    private final Queue<ByteBuffer> byteBufferPool = new LinkedList<>();
    private final Queue<Bitmap> bitmapPool = new LinkedList<>();
    
    public ObjectPoolManager() {
        this(DEFAULT_MAX_POOL_SIZE);
    }
    
    public ObjectPoolManager(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }
    
    /**
     * 从对象池中获取ByteBuffer
     */
    public synchronized ByteBuffer getByteBufferFromPool(int capacity) {
        ByteBuffer buffer = null;
        
        // 尝试从池中获取合适大小的ByteBuffer
        if (!byteBufferPool.isEmpty()) {
            ByteBuffer pooledBuffer = byteBufferPool.peek();
            if (pooledBuffer.capacity() >= capacity) {
                buffer = byteBufferPool.poll();
                buffer.clear();
            }
        }
        
        // 如果没有合适的，创建新的
        if (buffer == null) {
            buffer = ByteBuffer.allocate(capacity);
        }
        
        return buffer;
    }
    
    /**
     * 将ByteBuffer返回到对象池
     */
    public synchronized void returnByteBufferToPool(ByteBuffer buffer) {
        if (buffer == null) return;
        
        if (byteBufferPool.size() < maxPoolSize) {
            byteBufferPool.offer(buffer);
        }
    }
    
    /**
     * 从对象池中获取Bitmap
     */
    public synchronized Bitmap getBitmapFromPool(int width, int height) {
        Bitmap bitmap = null;
        
        // 尝试从池中获取合适大小的Bitmap
        if (!bitmapPool.isEmpty()) {
            Bitmap pooledBitmap = bitmapPool.peek();
            if (pooledBitmap.getWidth() == width && pooledBitmap.getHeight() == height) {
                bitmap = bitmapPool.poll();
                bitmap.eraseColor(0);
            }
        }
        
        // 如果没有合适的，创建新的
        if (bitmap == null) {
            try {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "创建Bitmap失败: " + e.getMessage());
                // 尝试清空池并重新创建
                clearBitmapPool();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
        }
        
        return bitmap;
    }
    
    /**
     * 将Bitmap返回到对象池
     */
    public synchronized void returnBitmapToPool(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        
        if (bitmapPool.size() < maxPoolSize) {
            bitmapPool.offer(bitmap);
        } else {
            bitmap.recycle();
        }
    }
    
    /**
     * 清空Bitmap对象池
     */
    public synchronized void clearBitmapPool() {
        while (!bitmapPool.isEmpty()) {
            Bitmap bitmap = bitmapPool.poll();
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }
    
    /**
     * 清空ByteBuffer对象池
     */
    public synchronized void clearByteBufferPool() {
        byteBufferPool.clear();
    }
    
    /**
     * 释放所有资源
     */
    public synchronized void release() {
        clearBitmapPool();
        clearByteBufferPool();
    }
}
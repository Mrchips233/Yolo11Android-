package com.Chips.yolo11ncnn;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Cache fallback for providers that MediaExtractor cannot open directly. */
public final class FileUtils {
    private FileUtils() { }

    /** Call on a worker thread. Closing the handle removes its temporary copy. */
    public static CachedVideo copyVideoToCache(Context context, Uri uri) throws IOException {
        File temporary = File.createTempFile("frame-input-", ".video", context.getCacheDir());
        boolean complete = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("无法读取视频 URI");
                byte[] buffer = new byte[64 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消视频读取");
                    output.write(buffer, 0, length);
                }
                if (temporary.length() == 0) throw new IOException("视频文件为空");
            }
            complete = true;
            return new CachedVideo(temporary);
        } finally {
            if (!complete && !temporary.delete()) {
                Log.w("FileUtils", "无法删除未完成的临时视频: " + temporary.getName());
            }
        }
    }

    public static final class CachedVideo implements Closeable {
        public final File file;

        private CachedVideo(File file) {
            this.file = file;
        }

        @Override public void close() throws IOException {
            if (file.exists() && !file.delete()) throw new IOException("无法删除临时视频: " + file.getName());
        }
    }
}

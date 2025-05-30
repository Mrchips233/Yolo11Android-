package com.Chips.yolo11ncnn;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


public class Frame {
    private static final String TAG = "FrameEX";
    private static final Executor executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    static {
        System.loadLibrary("opencv_java4");
    }
    public interface Callback{
        void onComplete(int totalFrames , File outputDir);
        void onError(String message);
    }


    public static void extract(Context context,
                               String videoPath,
                               String currentVideoName,
                               Callback callback){
        executor.execute(()->{
            try{
                File outputDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), currentVideoName);
                if(!outputDir.exists() && !outputDir.mkdirs()){
                    postError(callback, "无法创建目录: " + outputDir.getAbsolutePath());
                    return;
                }


                VideoCapture videoCapture = new VideoCapture();
                if(!videoCapture.open(videoPath)){
                    postError(callback, "无法打开视频: " + videoPath);
                    return;
                }

                Mat frame = new Mat();
                int index = 0;

                while(videoCapture.read(frame)){
                    Mat rotated = new Mat();
                    Core.rotate(frame, rotated, Core.ROTATE_90_CLOCKWISE);
                    String fileName = "frame" + index + ".JPEG";
                    File out = new File(outputDir , fileName);
                    boolean saved = Imgcodecs.imwrite(out.getAbsolutePath(), rotated);

                    Log.d(TAG, "帧 " + index + (saved ? " 写入成功: " : " 写入失败: ") + out.getAbsolutePath());


                    index++;
                }

                videoCapture.release();

                int finalIndex = index;
                mainHandler.post(() -> callback.onComplete(finalIndex, outputDir));
            }catch (Exception e){
                postError(callback, "提取帧时异常: " + e.getMessage());
            }
        });
    }


    private static void postError(Callback callback, String message) {
        Log.e(TAG, message);
        mainHandler.post(() -> callback.onError(message));
    }
}

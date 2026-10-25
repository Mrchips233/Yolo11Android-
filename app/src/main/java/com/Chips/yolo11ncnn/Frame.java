package com.Chips.yolo11ncnn;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** URI-based streaming extraction. Callback methods run on the extraction worker. */
public final class Frame {
    private static final String TAG = "FrameEX";
    private static final long CODEC_TIMEOUT_US = 10000;
    private static final long STALL_TIMEOUT_MILLIS = 30000;
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> new Thread(runnable, "frame-extractor"));

    private Frame() { }

    public interface Callback {
        void onComplete(int totalFrames, File outputDir);
        void onError(String message);
    }

    /** Cancel the returned future to stop decoding and delete incomplete outputs. */
    public static Future<?> extract(Context context, Uri videoUri, String sessionName, Callback callback) {
        Context application = context.getApplicationContext();
        return EXECUTOR.submit(() -> {
            File outputDir;
            int count;
            try {
                if (sessionName == null || sessionName.isEmpty() || sessionName.contains("/")
                        || sessionName.equals(".") || sessionName.equals("..")) {
                    throw new IOException("无效的录制名称");
                }
                File pictures = application.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (pictures == null) throw new IOException("外部存储不可用");
                outputDir = new File(pictures, sessionName);
                if (!outputDir.isDirectory() && !outputDir.mkdirs()) throw new IOException("无法创建抽帧目录");
                System.loadLibrary("opencv_java4");
                count = extractFrames(application, videoUri, outputDir);
            } catch (IOException | RuntimeException | LinkageError error) {
                Log.e(TAG, "抽帧失败", error);
                callback.onError(error.getMessage() == null ? "视频解码失败" : error.getMessage());
                return;
            }
            callback.onComplete(count, outputDir);
        });
    }

    private static int extractFrames(Context context, Uri uri, File directory) throws IOException {
        File partialTimeline = new File(directory, "frames.csv.partial");
        File timeline = new File(directory, "frames.csv");
        if (timeline.exists() || new File(directory, "frame0.JPEG").exists()
                || !partialTimeline.createNewFile()) {
            throw new IOException("该录制已有抽帧结果，请勿重复处理");
        }
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        FileUtils.CachedVideo cache = null;
        Mat yuv = null;
        Mat bgr = null;
        Mat rotated = null;
        int generated = 0;
        boolean codecStarted = false;
        boolean complete = false;
        try {
            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, uri, null);
            } catch (IOException | RuntimeException directFailure) {
                checkCancelled();
                extractor.release();
                extractor = new MediaExtractor();
                cache = FileUtils.copyVideoToCache(context, uri);
                extractor.setDataSource(cache.file.getAbsolutePath());
            }
            int videoTrack = -1;
            MediaFormat format = null;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = track;
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IOException("文件中没有视频轨道");
            extractor.selectTrack(videoTrack);
            int rotation = format.containsKey(MediaFormat.KEY_ROTATION)
                    ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
            rotation = ((rotation % 360) + 360) % 360;
            if (rotation % 90 != 0) throw new IOException("不支持的视频旋转角度: " + rotation);
            format.setInteger(MediaFormat.KEY_ROTATION, 0);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            codecStarted = true;
            yuv = new Mat();
            bgr = new Mat();
            rotated = new Mat();
            byte[] imageData = null;
            long firstPts = Long.MIN_VALUE;
            long previousPts = Long.MIN_VALUE;
            long lastProgress = SystemClock.elapsedRealtime();
            boolean inputEnded = false;
            boolean outputEnded = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(partialTimeline), StandardCharsets.UTF_8))) {
                writer.write("frame,pts_us,timestamp_source,original_pts_us\n");
                while (!outputEnded) {
                    checkCancelled();
                    if (!inputEnded) {
                        int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                        if (inputIndex >= 0) {
                            ByteBuffer input = codec.getInputBuffer(inputIndex);
                            if (input == null) throw new IOException("视频解码器未提供输入缓冲");
                            input.clear();
                            int size = extractor.readSampleData(input, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEnded = true;
                            } else {
                                if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                                    throw new IOException("不支持受保护的视频");
                                }
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                            lastProgress = SystemClock.elapsedRealtime();
                        }
                    }
                    int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                    if (outputIndex >= 0) {
                        try {
                            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            if (info.size > 0 && !config) {
                                Image image = codec.getOutputImage(outputIndex);
                                if (image == null) throw new IOException("该设备解码器不支持图像抽帧");
                                try {
                                    if (image.getFormat() != ImageFormat.YUV_420_888) {
                                        throw new IOException("不支持的解码图像格式: " + image.getFormat());
                                    }
                                    Rect crop = image.getCropRect();
                                    int width = crop.width();
                                    int height = crop.height();
                                    if ((width & 1) != 0 || (height & 1) != 0
                                            || (crop.left & 1) != 0 || (crop.top & 1) != 0) {
                                        throw new IOException("不支持奇数像素的 YUV 裁切区域");
                                    }
                                    long byteCount = (long) width * height * 3 / 2;
                                    if (byteCount <= 0 || byteCount > Integer.MAX_VALUE) throw new IOException("视频尺寸无效");
                                    if (imageData == null || imageData.length != (int) byteCount) {
                                        imageData = new byte[(int) byteCount];
                                    }
                                    copyImage(image, crop, imageData);
                                    yuv.create(height + height / 2, width, CvType.CV_8UC1);
                                    yuv.put(0, 0, imageData);
                                    Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_I420);
                                    Mat output = bgr;
                                    if (rotation != 0) {
                                        Core.rotate(bgr, rotated, rotation == 90 ? Core.ROTATE_90_CLOCKWISE
                                                : rotation == 180 ? Core.ROTATE_180 : Core.ROTATE_90_COUNTERCLOCKWISE);
                                        output = rotated;
                                    }
                                    long pts = info.presentationTimeUs;
                                    if (firstPts == Long.MIN_VALUE) firstPts = pts;
                                    if (pts < 0 || pts <= previousPts) throw new IOException("视频解码时间戳无效，无法可靠匹配 GPS");
                                    previousPts = pts;
                                    String frameName = "frame" + generated + ".JPEG";
                                    generated++;
                                    if (!Imgcodecs.imwrite(new File(directory, frameName).getAbsolutePath(), output)) {
                                        throw new IOException("图片写入失败，请检查剩余存储空间");
                                    }
                                    writer.write(frameName + "," + (pts - firstPts)
                                            + ",decoder_presentation_time_us," + pts + "\n");
                                } finally {
                                    image.close();
                                }
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false);
                        }
                        lastProgress = SystemClock.elapsedRealtime();
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        lastProgress = SystemClock.elapsedRealtime();
                    }
                    if (SystemClock.elapsedRealtime() - lastProgress > STALL_TIMEOUT_MILLIS) {
                        throw new IOException("视频解码超时");
                    }
                }
                if (generated == 0) throw new IOException("视频中没有可解码的帧");
            }
            checkCancelled();
            if (!partialTimeline.renameTo(timeline)) throw new IOException("无法保存帧时间戳文件");
            complete = true;
            return generated;
        } finally {
            if (codec != null) {
                try {
                    if (codecStarted) codec.stop();
                } catch (RuntimeException stopFailure) {
                    Log.w(TAG, "停止解码器失败", stopFailure);
                } finally {
                    try {
                        codec.release();
                    } catch (RuntimeException releaseFailure) {
                        Log.w(TAG, "释放解码器失败", releaseFailure);
                    }
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (RuntimeException releaseFailure) {
                    Log.w(TAG, "释放视频轨道失败", releaseFailure);
                }
            }
            releaseMat(yuv);
            releaseMat(bgr);
            releaseMat(rotated);
            if (!complete) {
                for (int index = 0; index < generated; index++) {
                    deleteOutput(new File(directory, "frame" + index + ".JPEG"));
                }
                deleteOutput(partialTimeline);
            }
            if (cache != null) {
                try {
                    cache.close();
                } catch (IOException | RuntimeException cleanupFailure) {
                    Log.w(TAG, "删除临时视频失败", cleanupFailure);
                }
            }
        }
    }

    private static void copyImage(Image image, Rect crop, byte[] output) {
        Image.Plane[] planes = image.getPlanes();
        int lumaSize = crop.width() * crop.height();
        for (int channel = 0; channel < 3; channel++) {
            Image.Plane plane = planes[channel];
            int subsample = channel == 0 ? 1 : 2;
            int offset = channel == 0 ? 0 : lumaSize + (channel - 1) * lumaSize / 4;
            Yuv420Copier.copyPlane(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride(),
                    crop.left / subsample, crop.top / subsample, crop.width() / subsample,
                    crop.height() / subsample, output, offset);
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("抽帧已取消");
    }

    private static void deleteOutput(File file) {
        try {
            if (file.exists() && !file.delete()) Log.w(TAG, "无法删除未完成的文件: " + file.getName());
        } catch (RuntimeException cleanupFailure) {
            Log.w(TAG, "清理未完成的文件失败: " + file.getName(), cleanupFailure);
        }
    }

    private static void releaseMat(Mat matrix) {
        if (matrix == null) return;
        try {
            matrix.release();
        } catch (RuntimeException cleanupFailure) {
            Log.w(TAG, "释放图像缓冲失败", cleanupFailure);
        }
    }
}

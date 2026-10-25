package com.Chips.yolo11ncnn;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous, bounded-memory video recognition. Invoke on a worker, never the UI thread.
 * Each decoded frame is inferred once, annotated and written before decoding the next frame.
 * Results are retained on cancellation/failure; run.json is authoritative for completion.
 */
public final class VideoRecognitionProcessor {
    private static final long CODEC_TIMEOUT_US = 10_000;
    private static final long STALL_TIMEOUT_MS = 30_000;
    private final ContentResolver resolver;

    public VideoRecognitionProcessor(ContentResolver resolver) {
        if (resolver == null) throw new IllegalArgumentException("ContentResolver is required");
        this.resolver = resolver;
    }

    public interface Detector {
        /** Read-only upright RGB bitmap. Boxes must use this bitmap's pixel coordinates. */
        List<Detection> detect(Bitmap uprightRgb) throws Exception;

        /** Optional frozen configuration, e.g. input_mode=960 and backend=CPU. */
        default Map<String, String> metadata() { return Collections.emptyMap(); }
    }

    public interface ProgressListener {
        /** Worker callback: timestampUs is elapsed from first decoded PTS; durationUs may be -1. */
        void onProgress(long framesWritten, long timestampUs, long durationUs);
    }

    public static final class Detection {
        public final int labelId;
        public final String classCode;
        public final float confidence;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public Detection(int labelId, String classCode, float confidence,
                         float left, float top, float right, float bottom) {
            this.labelId = labelId;
            this.classCode = classCode;
            this.confidence = confidence;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static final class Result {
        public final Uri outputDirectory;
        public final Uri csvUri;
        public final Uri manifestUri;
        public final long framesWritten;
        public final long detections;
        /** completed, cancelled or failed. */
        public final String status;
        public final String error;

        private Result(Session session) {
            outputDirectory = session.directory;
            csvUri = session.csv;
            manifestUri = session.manifest;
            framesWritten = session.frames;
            detections = session.detections;
            status = session.status;
            error = session.error;
        }
    }

    /**
     * outputTree must be a writable SAF tree (ACTION_OPEN_DOCUMENT_TREE) or its child directory.
     * Creates a fresh unique run directory. Only inability to create the initial directory/manifest
     * throws IOException; later errors return a failed Result, retaining partial outputs.
     */
    public Result process(Uri sourceVideo, Uri outputTree, String runName, Detector detector,
                          AtomicBoolean cancelled, ProgressListener progress) throws IOException {
        if (sourceVideo == null || outputTree == null || detector == null || cancelled == null) {
            throw new IllegalArgumentException("Source, destination, detector and cancel flag are required");
        }
        String safeName = runName == null ? "video" : runName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) safeName = "video";
        safeName = safeName.substring(0, Math.min(64, safeName.length()));
        Uri parent = directoryUri(outputTree);
        Session session = new Session();
        session.source = sourceVideo;
        session.directory = create(parent, DocumentsContract.Document.MIME_TYPE_DIR,
                safeName + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8));
        session.manifest = create(session.directory, "application/json", "run.json");
        writeManifest(session); // Incomplete record exists even if the app later dies.
        try {
            Map<String, String> metadata = detector.metadata();
            session.detectorMetadata = metadata == null ? Collections.emptyMap() : new HashMap<>(metadata);
            session.csv = create(session.directory, "text/csv", "detections.csv");
            OutputStream csvOutput = resolver.openOutputStream(session.csv, "w");
            if (csvOutput == null) throw new IOException("无法写入 CSV");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(csvOutput, StandardCharsets.UTF_8))) {
                writer.write('\ufeff');
                writer.write("frame_index,timestamp_us,timestamp,elapsed_us,elapsed_timestamp,image_name,class_id,class_code,confidence,"
                        + "x1,y1,x2,y2,source_width,source_height,frame_status\r\n");
                writer.flush();
                decode(sourceVideo, detector, cancelled, progress, session, writer);
                checkCancelled(cancelled);
                if (session.frames == 0) throw new IOException("视频没有可解码的图像帧");
            }
            session.status = "completed";
        } catch (InterruptedIOException exception) {
            session.status = "cancelled";
            session.error = "已取消；已写出的图片和表格保留为部分结果";
        } catch (OutOfMemoryError exception) {
            session.status = "failed";
            session.error = "视频帧处理内存不足；已保留部分结果，请改用 1080p 视频或释放手机内存后重试";
        } catch (Exception | LinkageError exception) {
            session.status = cancelled.get() || Thread.currentThread().isInterrupted() ? "cancelled" : "failed";
            session.error = "cancelled".equals(session.status) ? "已取消；保留部分结果"
                    : exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        } finally {
            try {
                writeManifest(session);
            } catch (IOException failure) {
                session.status = "failed";
                session.error = (session.error.isEmpty() ? "" : session.error + "；")
                        + "状态文件无法更新，run.json 中的 running 表示结果不完整：" + failure.getMessage();
            }
        }
        return new Result(session);
    }

    private void decode(Uri source, Detector detector, AtomicBoolean cancelled,
                        ProgressListener progress, Session session, BufferedWriter writer) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean codecStarted = false;
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(source, "r")) {
            if (descriptor == null) throw new IOException("无法打开所选视频");
            if (descriptor.getDeclaredLength() >= 0) {
                extractor.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getDeclaredLength());
            } else if (descriptor.getStartOffset() == 0) {
                extractor.setDataSource(descriptor.getFileDescriptor());
            } else {
                throw new IOException("视频提供方没有可定位的文件长度，请先把视频保存到本机");
            }
            MediaFormat format = null;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat candidate = extractor.getTrackFormat(track);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(track);
                    format = candidate;
                    break;
                }
            }
            if (format == null) throw new IOException("文件中没有视频轨道");
            session.rotation = integer(format, MediaFormat.KEY_ROTATION, 0);
            session.rotation = ((session.rotation % 360) + 360) % 360;
            if (session.rotation % 90 != 0) throw new IOException("不支持该视频的旋转角度");
            session.durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1;
            session.colorStandard = integer(format, MediaFormat.KEY_COLOR_STANDARD, 2);
            session.colorRange = integer(format, MediaFormat.KEY_COLOR_RANGE, 2);
            rejectHdr(format);
            writeManifest(session);
            format.setInteger(MediaFormat.KEY_ROTATION, 0); // Rotate exactly once after decoding.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            codecStarted = true;
            boolean inputEnded = false;
            boolean outputEnded = false;
            long lastCodecProgress = SystemClock.elapsedRealtime();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEnded) {
                checkCancelled(cancelled);
                if (!inputEnded) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("解码器未提供输入缓冲");
                        input.clear();
                        int bytes = extractor.readSampleData(input, 0);
                        if (bytes < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                                throw new IOException("不支持受保护的视频");
                            }
                            codec.queueInputBuffer(inputIndex, 0, bytes, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        lastCodecProgress = SystemClock.elapsedRealtime();
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex >= 0) {
                    Bitmap bitmap = null;
                    long timestampUs = info.presentationTimeUs;
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            try (Image image = codec.getOutputImage(outputIndex)) {
                                if (image == null) throw new IOException("当前视频解码器不支持逐帧图像输出");
                                bitmap = uprightBitmap(image, session);
                            }
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                    if (bitmap != null) {
                        try {
                            checkCancelled(cancelled);
                            exportFrame(bitmap, timestampUs, detector, cancelled, session, writer);
                            if (progress != null) progress.onProgress(session.frames,
                                    Math.max(0, timestampUs - session.firstPtsUs), session.durationUs);
                        } finally {
                            bitmap.recycle();
                        }
                    }
                    lastCodecProgress = SystemClock.elapsedRealtime();
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = codec.getOutputFormat();
                    rejectHdr(actual);
                    session.colorStandard = integer(actual, MediaFormat.KEY_COLOR_STANDARD, session.colorStandard);
                    session.colorRange = integer(actual, MediaFormat.KEY_COLOR_RANGE, session.colorRange);
                    lastCodecProgress = SystemClock.elapsedRealtime();
                }
                if (SystemClock.elapsedRealtime() - lastCodecProgress > STALL_TIMEOUT_MS) {
                    throw new IOException("视频解码超过 30 秒没有进展");
                }
            }
        } finally {
            if (codec != null) {
                try { if (codecStarted) codec.stop(); } catch (RuntimeException ignored) { /* release below */ }
                try { codec.release(); } catch (RuntimeException ignored) { /* resources already gone */ }
            }
            try { extractor.release(); } catch (RuntimeException ignored) { /* release best effort */ }
        }
    }

    private static Bitmap uprightBitmap(Image image, Session session) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888) throw new IOException("仅支持 YUV420 SDR 解码输出");
        Rect crop = image.getCropRect();
        if (crop.left < 0 || crop.top < 0 || crop.right > image.getWidth() || crop.bottom > image.getHeight()) {
            throw new IOException("解码器返回了无效裁切区域");
        }
        Image.Plane[] imagePlanes = image.getPlanes();
        if (imagePlanes.length != 3) throw new IOException("解码图像的 YUV 平面数量无效");
        ByteBuffer[] planes = new ByteBuffer[3];
        int[] rows = new int[3];
        int[] pixels = new int[3];
        for (int i = 0; i < 3; i++) {
            planes[i] = imagePlanes[i].getBuffer();
            rows[i] = imagePlanes[i].getRowStride();
            pixels[i] = imagePlanes[i].getPixelStride();
        }
        int[] argb = VideoExportSupport.yuvToArgb(planes, rows, pixels, crop.left, crop.top,
                crop.width(), crop.height(), session.colorStandard, session.colorRange);
        Bitmap bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888);
        try {
            bitmap.setPixels(argb, 0, crop.width(), 0, 0, crop.width(), crop.height());
        } catch (RuntimeException | Error failure) {
            bitmap.recycle();
            throw failure;
        }
        argb = null;
        if (session.rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(session.rotation);
        Bitmap rotated;
        try {
            rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
        } finally {
            bitmap.recycle();
        }
        // Matrix-created bitmaps are not guaranteed mutable; Canvas needs an explicit mutable copy.
        if (!rotated.isMutable()) {
            Bitmap mutable;
            try { mutable = rotated.copy(Bitmap.Config.ARGB_8888, true); }
            finally { rotated.recycle(); }
            if (mutable == null) throw new IOException("无法分配视频帧缓冲");
            return mutable;
        }
        return rotated;
    }

    private void exportFrame(Bitmap bitmap, long timestampUs, Detector detector, AtomicBoolean cancelled,
                             Session session, BufferedWriter writer) throws Exception {
        List<Detection> raw = detector.detect(bitmap);
        checkCancelled(cancelled);
        List<Detection> detections = validate(raw, bitmap.getWidth(), bitmap.getHeight());
        long frameIndex = session.frames + 1;
        String requestedName = VideoExportSupport.frameName(frameIndex);
        draw(bitmap, detections);
        Uri imageUri = create(session.directory, "image/jpeg", requestedName);
        String actualName = displayName(imageUri, requestedName);
        try (OutputStream output = resolver.openOutputStream(imageUri, "w")) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw new IOException("JPEG 写入失败，请检查目录权限和剩余空间");
            }
        }
        long firstPtsUs = session.frames == 0 ? timestampUs : session.firstPtsUs;
        String prefix = VideoExportSupport.framePrefix(frameIndex, timestampUs, firstPtsUs, actualName);
        String suffix = "," + bitmap.getWidth() + "," + bitmap.getHeight();
        if (detections.isEmpty()) {
            writer.write(VideoExportSupport.emptyFrameRow(frameIndex, timestampUs, firstPtsUs,
                    actualName, bitmap.getWidth(), bitmap.getHeight()));
        } else {
            for (Detection detection : detections) {
                writer.write(prefix + detection.labelId + "," + VideoExportSupport.csv(detection.classCode) + ","
                        + VideoExportSupport.number(detection.confidence) + ","
                        + VideoExportSupport.number(detection.left) + "," + VideoExportSupport.number(detection.top) + ","
                        + VideoExportSupport.number(detection.right) + "," + VideoExportSupport.number(detection.bottom)
                        + suffix + ",detected\r\n");
            }
        }
        writer.flush(); // A committed frame has both an image and flushed CSV row(s).
        if (session.frames == 0) session.firstPtsUs = timestampUs;
        session.lastPtsUs = timestampUs;
        session.frames = frameIndex;
        session.detections += detections.size();
        session.width = bitmap.getWidth();
        session.height = bitmap.getHeight();
    }

    private static List<Detection> validate(List<Detection> raw, int width, int height) throws IOException {
        if (raw == null) throw new IOException("检测器没有返回结果列表");
        if (raw.size() > 300) throw new IOException("检测器超出部署约定的每帧 300 框上限");
        List<Detection> result = new ArrayList<>(raw.size());
        for (Detection d : raw) {
            if (d == null || d.labelId < 0 || d.labelId >= 45 || d.classCode == null || d.classCode.isEmpty()
                    || !finite(d.confidence) || d.confidence < 0 || d.confidence > 1
                    || !finite(d.left) || !finite(d.top) || !finite(d.right) || !finite(d.bottom)) {
                throw new IOException("检测器返回无效类别、置信度或坐标");
            }
            float left = Math.max(0, Math.min(width, d.left));
            float top = Math.max(0, Math.min(height, d.top));
            float right = Math.max(0, Math.min(width, d.right));
            float bottom = Math.max(0, Math.min(height, d.bottom));
            if (right <= left || bottom <= top) throw new IOException("检测器返回了空框或反向框");
            result.add(new Detection(d.labelId, d.classCode, d.confidence, left, top, right, bottom));
        }
        return result;
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }

    private static void draw(Bitmap bitmap, List<Detection> detections) {
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float textSize = Math.max(12, Math.min(48, bitmap.getWidth() / 45f));
        paint.setTextSize(textSize);
        for (Detection d : detections) {
            paint.setColor(Color.rgb(255, 65, 54));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, bitmap.getWidth() / 500f));
            canvas.drawRect(d.left, d.top, d.right, d.bottom, paint);
            String text = d.classCode + " " + String.format(Locale.ROOT, "%.3f", d.confidence);
            float x = Math.max(0, Math.min(d.left, bitmap.getWidth() - paint.measureText(text)));
            float baseline = Math.max(textSize, d.top);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawRect(x, baseline - textSize, Math.min(bitmap.getWidth(), x + paint.measureText(text)), baseline + 3, paint);
            paint.setColor(Color.WHITE);
            canvas.drawText(text, x, baseline, paint);
        }
    }

    private Uri directoryUri(Uri tree) throws IOException {
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException("请选择可写入的文件夹，而不是单个文件");
        String id = tree.getPathSegments().contains("document")
                ? DocumentsContract.getDocumentId(tree) : DocumentsContract.getTreeDocumentId(tree);
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private Uri create(Uri directory, String mime, String name) throws IOException {
        try {
            Uri result = DocumentsContract.createDocument(resolver, directory, mime, name);
            if (result == null) throw new IOException("无法创建输出文件：" + name);
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("无法创建输出文件：" + name, exception);
        }
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { /* Keep stable requested name when provider cannot query. */ }
        return fallback;
    }

    private void writeManifest(Session session) throws IOException {
        try {
            JSONObject json = new JSONObject();
            json.put("format_version", 1);
            json.put("status", session.status);
            json.put("incomplete", !"completed".equals(session.status));
            json.put("error", session.error);
            json.put("source_uri", session.source.toString());
            json.put("csv_uri", session.csv == null ? JSONObject.NULL : session.csv.toString());
            json.put("completed_frames", session.frames);
            json.put("detections", session.detections);
            json.put("frame_index_base", 1);
            json.put("timestamp_source", "decoder_original_presentation_time_us");
            json.put("duration_us", session.durationUs);
            json.put("first_pts_us", session.frames == 0 ? JSONObject.NULL : session.firstPtsUs);
            json.put("last_pts_us", session.frames == 0 ? JSONObject.NULL : session.lastPtsUs);
            json.put("rotation_degrees", session.rotation);
            json.put("last_frame_width", session.width);
            json.put("last_frame_height", session.height);
            json.put("color_standard", session.colorStandard);
            json.put("color_range", session.colorRange);
            json.put("confidence_threshold", 0.465);
            json.put("class_aware_nms_iou", 0.7);
            json.put("max_detections", 300);
            json.put("detector", new JSONObject(session.detectorMetadata));
            json.put("image_content", "upright cropped video frame with detection overlays; not raw evaluation input");
            json.put("partial_output_note", "Only completed_frames have committed CSV rows; an extra JPEG or partial CSV row may remain after an I/O failure. running means interrupted/incomplete.");
            try (OutputStream output = resolver.openOutputStream(session.manifest, "wt")) {
                if (output == null) throw new IOException("无法写入状态文件");
                output.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("无法保存状态文件", exception);
        }
    }

    private static int integer(MediaFormat format, String key, int fallback) {
        return format.containsKey(key) ? format.getInteger(key) : fallback;
    }

    private static void rejectHdr(MediaFormat format) throws IOException {
        int transfer = integer(format, MediaFormat.KEY_COLOR_TRANSFER, 3);
        if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
            throw new IOException("暂不支持 HDR 视频，请导出 SDR 视频后重试，避免颜色转换影响检测");
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
    }

    private static final class Session {
        Uri directory;
        Uri manifest;
        Uri csv;
        Uri source;
        String status = "running";
        String error = "";
        long frames;
        long detections;
        long durationUs = -1;
        long firstPtsUs;
        long lastPtsUs;
        int rotation;
        int width;
        int height;
        int colorStandard = 2;
        int colorRange = 2;
        Map<String, String> detectorMetadata = Collections.emptyMap();
    }
}

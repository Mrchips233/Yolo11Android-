package com.Chips.yolo11ncnn;

import android.content.Context;
import android.content.ContentResolver;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Real codec + SAF contract integration, without touching the user's files or requiring a picker. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class VideoRecognitionProcessorDeviceTest {
    private File root;
    private File video;
    private ContentResolver resolver;
    private Uri tree;
    private Uri videoUri;
    private List<Long> sourcePts;

    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(context.getCacheDir(), "video-recognition-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        video = new File(root, "input.mp4");
        encodeVideo(video);
        sourcePts = readPts(video);
        assertEquals(4, sourcePts.size());
        LocalDocumentsProvider provider = new LocalDocumentsProvider(root);
        ProviderInfo info = new ProviderInfo();
        info.authority = "com.Chips.yolo11ncnn.test.video.local";
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        resolver = ContentResolver.wrap(provider);
        tree = DocumentsContract.buildTreeDocumentUri(info.authority, "root");
        videoUri = DocumentsContract.buildDocumentUriUsingTree(tree, "root/input.mp4");
    }

    @After public void tearDown() { removeTestFiles(root); }

    @Test public void exportsEveryRotatedFrameWithActualPtsEmptyAndMultipleRows() throws Exception {
        final int[] calls = {0};
        final List<Long> elapsed = new ArrayList<>();
        VideoRecognitionProcessor.Result result = new VideoRecognitionProcessor(resolver).process(
                videoUri, tree, "fixture", bitmap -> {
                    assertEquals(64, bitmap.getWidth());
                    assertEquals(96, bitmap.getHeight());
                    calls[0]++;
                    if (calls[0] == 2) return Arrays.asList(detection(0, "i2"), detection(1, "i4"));
                    if (calls[0] == 3) return Collections.singletonList(detection(2, "i5"));
                    return Collections.emptyList();
                }, new AtomicBoolean(), (frames, pts, duration) -> elapsed.add(pts));
        assertEquals(result.error, "completed", result.status);
        assertEquals(4, result.framesWritten);
        assertEquals(3, result.detections);
        assertEquals(4, calls[0]);
        byte[] csvBytes = read(result.csvUri);
        assertEquals((byte)0xef, csvBytes[0]);
        assertEquals((byte)0xbb, csvBytes[1]);
        assertEquals((byte)0xbf, csvBytes[2]);
        String[] rows = new String(csvBytes, StandardCharsets.UTF_8).substring(1).split("\r\n");
        assertEquals(6, rows.length); // header, frame1, two frame2 objects, frame3, frame4
        assertTrue(rows[1].endsWith(",64,96,empty"));
        assertTrue(rows[2].contains(",0,\"i2\",0.900000,"));
        assertTrue(rows[3].contains(",1,\"i4\",0.900000,"));
        assertTrue(rows[4].startsWith("3," + sourcePts.get(2) + ","));
        assertTrue(rows[5].endsWith(",64,96,empty"));
        assertEquals(Long.valueOf(0), elapsed.get(0));
        assertEquals(Long.valueOf(sourcePts.get(3) - sourcePts.get(0)), elapsed.get(3));
        JSONObject manifest = new JSONObject(new String(read(result.manifestUri), StandardCharsets.UTF_8));
        assertEquals("completed", manifest.getString("status"));
        assertFalse(manifest.getBoolean("incomplete"));
        assertEquals(90, manifest.getInt("rotation_degrees"));
        assertEquals(sourcePts.get(0).longValue(), manifest.getLong("first_pts_us"));
        assertEquals(4, jpegCount(result.outputDirectory));
    }

    @Test public void cancellationRetainsCommittedFrameAndMarksManifestPartial() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        VideoRecognitionProcessor.Result result = new VideoRecognitionProcessor(resolver).process(
                videoUri, tree, "cancel", bitmap -> Collections.emptyList(), cancelled,
                (frames, pts, duration) -> cancelled.set(true));
        assertEquals("cancelled", result.status);
        assertEquals(1, result.framesWritten);
        assertEquals(1, jpegCount(result.outputDirectory));
        JSONObject manifest = new JSONObject(new String(read(result.manifestUri), StandardCharsets.UTF_8));
        assertTrue(manifest.getBoolean("incomplete"));
        assertEquals("cancelled", manifest.getString("status"));
        assertEquals(1, manifest.getLong("completed_frames"));
    }

    @Test public void detectorFailureRetainsEarlierRowsAndMarksManifestFailed() throws Exception {
        final int[] calls = {0};
        VideoRecognitionProcessor.Result result = new VideoRecognitionProcessor(resolver).process(
                videoUri, tree, "failure", bitmap -> {
                    if (++calls[0] == 2) throw new IOException("fixture detector failure");
                    return Collections.emptyList();
                }, new AtomicBoolean(), null);
        assertEquals("failed", result.status);
        assertEquals(1, result.framesWritten);
        assertEquals(1, jpegCount(result.outputDirectory));
        JSONObject manifest = new JSONObject(new String(read(result.manifestUri), StandardCharsets.UTF_8));
        assertTrue(manifest.getBoolean("incomplete"));
        assertEquals("failed", manifest.getString("status"));
        assertTrue(manifest.getString("error").contains("fixture detector failure"));
    }

    private static VideoRecognitionProcessor.Detection detection(int id, String code) {
        return new VideoRecognitionProcessor.Detection(id, code, 0.9f, 4, 5, 20, 25);
    }

    private byte[] read(Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Missing test output");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private int jpegCount(Uri directory) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory));
        int count = 0;
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            assertNotNull(cursor);
            while (cursor.moveToNext()) if ("image/jpeg".equals(cursor.getString(0))) count++;
        }
        return count;
    }

    private static List<Long> readPts(File video) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(video.getAbsolutePath());
            extractor.selectTrack(0);
            List<Long> pts = new ArrayList<>();
            while (extractor.getSampleTime() >= 0) { pts.add(extractor.getSampleTime()); extractor.advance(); }
            return pts;
        } finally { extractor.release(); }
    }

    private static void encodeVideo(File destination) throws IOException {
        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        MediaMuxer muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean encoderStarted = false;
        boolean muxerStarted = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 96, 64);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 300_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            encoderStarted = true;
            muxer.setOrientationHint(90);
            long[] pts = {1_000_000, 1_040_000, 1_120_000, 1_240_000};
            int next = 0;
            int track = -1;
            boolean queuedEos = false;
            boolean ended = false;
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!ended) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Test fixture encoder stalled");
                if (!queuedEos) {
                    int index = encoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = encoder.getInputBuffer(index);
                        if (buffer == null) throw new IOException("Test encoder input missing");
                        buffer.clear();
                        if (next == pts.length) {
                            encoder.queueInputBuffer(index, 0, 0, 1_280_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            queuedEos = true;
                        } else {
                            byte[] yuv = new byte[96 * 64 * 3 / 2];
                            Arrays.fill(yuv, 0, 96 * 64, (byte)(50 + next * 30));
                            Arrays.fill(yuv, 96 * 64, yuv.length, (byte)128);
                            buffer.put(yuv);
                            encoder.queueInputBuffer(index, 0, yuv.length, pts[next++], 0);
                        }
                    }
                }
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ByteBuffer output = encoder.getOutputBuffer(index);
                            if (output == null || !muxerStarted) throw new IOException("Test muxer not ready");
                            output.position(info.offset);
                            output.limit(info.offset + info.size);
                            muxer.writeSampleData(track, output, info);
                        }
                        ended = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { encoder.releaseOutputBuffer(index, false); }
                }
            }
        } finally {
            try { if (encoderStarted) encoder.stop(); } finally { encoder.release(); }
            try { if (muxerStarted) muxer.stop(); } finally { muxer.release(); }
        }
    }

    private static void removeTestFiles(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeTestFiles(child);
        file.delete();
    }

    /** Local provider exercises DocumentsContract without exposing a production component. */
    private static final class LocalDocumentsProvider extends DocumentsProvider {
        private final File directory;
        LocalDocumentsProvider(File directory) { this.directory = directory; }
        @Override public boolean onCreate() { return directory.mkdirs() || directory.isDirectory(); }
        @Override public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
        }
        @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            MatrixCursor result = cursor(projection);
            add(result, id, file(id));
            return result;
        }
        @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
            MatrixCursor result = cursor(projection);
            File[] files = file(parent).listFiles();
            if (files != null) for (File child : files) add(result, parent + "/" + child.getName(), child);
            return result;
        }
        @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            if (name.contains("/") || name.equals(".") || name.equals("..")) throw new FileNotFoundException("Invalid name");
            String id = parent + "/" + name;
            File output = file(id);
            try {
                boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? output.mkdir() : output.createNewFile();
                if (!created) throw new FileNotFoundException("Output already exists");
                return id;
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
        }
        private File file(String id) throws FileNotFoundException {
            if (!id.equals("root") && !id.startsWith("root/")) throw new FileNotFoundException("Unknown document");
            File file = id.equals("root") ? directory : new File(directory, id.substring(5));
            try {
                if (!file.getCanonicalPath().equals(directory.getCanonicalPath())
                        && !file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) {
                    throw new FileNotFoundException("Outside fixture root");
                }
            } catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
            return file;
        }
        private static MatrixCursor cursor(String[] projection) {
            return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE} : projection);
        }
        private static void add(MatrixCursor cursor, String id, File file) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE:
                        row.add(file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR
                                : file.getName().endsWith(".jpg") ? "image/jpeg" : "application/octet-stream"); break;
                    case DocumentsContract.Document.COLUMN_FLAGS:
                        row.add(file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE); break;
                    case DocumentsContract.Document.COLUMN_SIZE: row.add(file.length()); break;
                    default: row.add(null);
                }
            }
        }
    }
}

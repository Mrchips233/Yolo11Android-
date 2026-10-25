package com.Chips.yolo11ncnn;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.Chips.yolo11ncnn.databinding.VideoRecognitionBinding;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground, sequential offline processing; no camera, location or network required. */
public class VideoRecognitionActivity extends AppCompatActivity {
    private static final String TAG = "VideoRecognition";
    private VideoRecognitionBinding binding;
    private final YOLO11Ncnn detector = new YOLO11Ncnn();
    private NativeRuntime.Session session;
    private AtomicBoolean cancelled;
    private Uri sourceVideo;
    private Uri outputTree;
    private boolean running;
    private boolean destroyed;
    private VideoRecognitionProcessor.Result lastResult;

    private final ActivityResultLauncher<Intent> selectVideo = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                sourceVideo = result.getData().getData();
                persist(sourceVideo, result.getData().getFlags());
                binding.videoSource.setText(displayName(sourceVideo));
                updateButtons();
            });
    private final ActivityResultLauncher<Intent> selectDirectory = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                outputTree = result.getData().getData();
                persist(outputTree, result.getData().getFlags());
                binding.videoOutput.setText(outputTree == null ? "" : outputTree.toString());
                updateButtons();
            });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        binding = VideoRecognitionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        session = NativeRuntime.openSession(() -> {
            detector.closeCamera();
            detector.setOutputWindow(null);
        });
        if (state != null) {
            String source = state.getString("source");
            String output = state.getString("output");
            if (source != null) sourceVideo = Uri.parse(source);
            if (output != null) outputTree = Uri.parse(output);
            binding.videoSource.setText(displayName(sourceVideo));
            if (outputTree != null) binding.videoOutput.setText(outputTree.toString());
        }
        binding.toolbarBack.setOnClickListener(v -> finish());
        binding.chooseVideo.setOnClickListener(v -> selectVideo.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)));
        binding.chooseOutput.setOnClickListener(v -> selectDirectory.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)));
        binding.startProcessing.setOnClickListener(v -> startProcessing());
        binding.cancelProcessing.setOnClickListener(v -> cancelProcessing());
        binding.openTable.setOnClickListener(v -> openResult(false));
        binding.openOutput.setOnClickListener(v -> openResult(true));
        updateButtons();
    }

    private void persist(Uri uri, int flags) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (SecurityException error) {
            Log.w(TAG, "Provider did not grant persistent access", error);
        }
    }

    private String displayName(Uri uri) {
        if (uri == null) return getString(R.string.video_no_source);
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read source name", error);
        }
        return uri.toString();
    }

    private void startProcessing() {
        if (running || sourceVideo == null || outputTree == null) return;
        if (!session.isCurrent()) {
            session = NativeRuntime.openSession(() -> {
                detector.closeCamera();
                detector.setOutputWindow(null);
            });
        }
        running = true;
        lastResult = null;
        cancelled = new AtomicBoolean();
        AtomicBoolean cancellation = cancelled;
        Uri video = sourceVideo;
        Uri tree = outputTree;
        int model = binding.videoModel.getSelectedItemPosition();
        int backend = binding.videoBackend.getSelectedItemPosition();
        binding.videoStatus.setText(R.string.video_loading);
        binding.tablePreview.removeAllViews();
        binding.videoProgress.setIndeterminate(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
        session.submit(() -> {
            try {
                if (cancellation.get() || !session.isCurrent()) {
                    finishError(getString(R.string.video_cancelled));
                    return;
                }
                detector.closeCamera();
                detector.setOutputWindow(null);
                if (!detector.loadModel(getAssets(), 0, model, backend)) {
                    throw new IllegalStateException(getString(R.string.model_load_failed));
                }
                List<String> labels = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        getAssets().open("tt100k45.labels.txt"), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) labels.add(line.trim());
                }
                String runName = "yolo_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                        .format(new Date()) + "_" + (model == 0 ? "960" : "640") + "_b" + backend;
                VideoRecognitionProcessor processor = new VideoRecognitionProcessor(getContentResolver());
                final long[] lastUpdate = {0};
                VideoRecognitionProcessor.Detector videoDetector = new VideoRecognitionProcessor.Detector() {
                    @Override public List<VideoRecognitionProcessor.Detection> detect(Bitmap bitmap) {
                        if (!session.isCurrent()) cancellation.set(true);
                        return VideoRecognitionActivity.this.detect(bitmap, labels);
                    }

                    @Override public Map<String, String> metadata() {
                        Map<String, String> values = new HashMap<>();
                        values.put("model_id", "tt100k45_yolo11n_improved_20261016_163748_best");
                        values.put("input_long_edge", model == 0 ? "960" : "640");
                        values.put("backend", backend == 0 ? "CPU" : backend == 1 ? "Vulkan" : "Turnip");
                        values.put("labels", "tt100k45.labels.txt");
                        values.put("weights_sha256", "b1f9200d79e0008ffbd713297013ecc529de53bd3dcfeffad31b6d9c3c3775f8");
                        return values;
                    }
                };
                VideoRecognitionProcessor.Result result = processor.process(video, tree, runName, videoDetector,
                        cancellation, (frames, pts, duration) -> {
                    if (!session.isCurrent()) cancellation.set(true);
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastUpdate[0] < 250) return;
                    lastUpdate[0] = now;
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        binding.videoProgress.setIndeterminate(duration <= 0);
                        if (duration > 0) binding.videoProgress.setProgress((int) Math.min(99,
                                Math.max(0, pts) * 100.0 / duration));
                        binding.videoStatus.setText(getString(R.string.video_progress, frames,
                                pts / 1_000_000.0, duration / 1_000_000.0));
                    });
                });
                List<String[]> preview = readPreview(result.csvUri);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    running = false;
                    lastResult = result;
                    binding.videoProgress.setIndeterminate(false);
                    if ("completed".equals(result.status)) binding.videoProgress.setProgress(100);
                    String status = "completed".equals(result.status) ? getString(R.string.video_complete)
                            : "cancelled".equals(result.status) ? getString(R.string.video_cancelled)
                            : getString(R.string.video_failed);
                    binding.videoStatus.setText(status + "\n" + getString(R.string.video_result,
                            result.framesWritten, result.detections) + "\n" + result.outputDirectory
                            + (result.error == null ? "" : "\n" + result.error));
                    showPreview(preview);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updateButtons();
                });
            } catch (Exception | LinkageError | OutOfMemoryError error) {
                Log.e(TAG, "Video processing failed", error);
                finishError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    private List<VideoRecognitionProcessor.Detection> detect(Bitmap bitmap, List<String> labels) {
        float[] values = detector.detectBitmap(bitmap);
        if (values == null || values.length % 6 != 0) throw new IllegalStateException("无效的推理输出");
        List<VideoRecognitionProcessor.Detection> detections = new ArrayList<>();
        for (int offset = 0; offset < values.length; offset += 6) {
            int label = (int) values[offset];
            if (label < 0 || label >= labels.size()) throw new IllegalStateException("模型类别与标签不匹配");
            detections.add(new VideoRecognitionProcessor.Detection(label, labels.get(label), values[offset + 1],
                    values[offset + 2], values[offset + 3], values[offset + 2] + values[offset + 4],
                    values[offset + 3] + values[offset + 5]));
        }
        return detections;
    }

    private List<String[]> readPreview(Uri csv) {
        List<String[]> rows = new ArrayList<>();
        if (csv == null) return rows;
        try (InputStream stream = getContentResolver().openInputStream(csv)) {
            if (stream == null) return rows;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (int i = 0; i < 16; i++) {
                    String line = reader.readLine();
                    if (line == null) break;
                    rows.add(parseCsvRow(line.replace("\ufeff", "")));
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Cannot preview CSV", error);
        }
        return rows;
    }

    private static String[] parseCsvRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else field.append(value);
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    private void showPreview(List<String[]> rows) {
        binding.tablePreview.removeAllViews();
        if (rows.isEmpty()) return;
        String[] keys = {"image_name", "class_code", "frame_index", "elapsed_timestamp", "confidence"};
        String[] titles = {"图像名称", "识别标志（官方代码）", "帧号", "视频时间", "置信度"};
        int[] columns = new int[keys.length];
        java.util.Arrays.fill(columns, -1);
        for (int i = 0; i < keys.length; i++) {
            for (int j = 0; j < rows.get(0).length; j++) {
                if (keys[i].equals(rows.get(0)[j])) columns[i] = j;
            }
        }
        addPreviewRow(titles, true);
        for (int i = 1; i < rows.size(); i++) {
            String[] source = rows.get(i);
            String[] cells = new String[keys.length];
            for (int j = 0; j < keys.length; j++) {
                cells[j] = columns[j] >= 0 && columns[j] < source.length ? source[columns[j]] : "";
            }
            if (cells[1].isEmpty()) cells[1] = "无检测目标";
            addPreviewRow(cells, false);
        }
    }

    private void addPreviewRow(String[] cells, boolean header) {
        TableRow row = new TableRow(this);
        int padding = (int) (10 * getResources().getDisplayMetrics().density);
        for (String cell : cells) {
            TextView view = new TextView(this);
            view.setText(cell);
            view.setTextSize(12);
            view.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ui_text));
            view.setPadding(padding, padding, padding, padding);
            view.setTextIsSelectable(true);
            if (header) view.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(view);
        }
        binding.tablePreview.addView(row);
    }

    private void finishError(String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            running = false;
            binding.videoStatus.setText(message);
            binding.videoProgress.setIndeterminate(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateButtons();
        });
    }

    private void cancelProcessing() {
        if (cancelled != null) cancelled.set(true);
        if (running) binding.videoStatus.setText(R.string.video_cancelling);
        binding.cancelProcessing.setEnabled(false);
    }

    private void updateButtons() {
        binding.chooseVideo.setEnabled(!running);
        binding.chooseOutput.setEnabled(!running);
        binding.videoModel.setEnabled(!running);
        binding.videoBackend.setEnabled(!running);
        binding.startProcessing.setEnabled(!running && sourceVideo != null && outputTree != null);
        binding.cancelProcessing.setEnabled(running);
        binding.openTable.setEnabled(!running && lastResult != null && lastResult.csvUri != null);
        binding.openOutput.setEnabled(!running && lastResult != null);
    }

    private void openResult(boolean directory) {
        if (lastResult == null) return;
        Uri uri = directory ? lastResult.outputDirectory : lastResult.csvUri;
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                directory ? DocumentsContract.Document.MIME_TYPE_DIR : "text/csv")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, R.string.video_open_fallback, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onStop() {
        if (running) cancelProcessing();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (cancelled != null) cancelled.set(true);
        session.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (sourceVideo != null) state.putString("source", sourceVideo.toString());
        if (outputTree != null) state.putString("output", outputTree.toString());
        super.onSaveInstanceState(state);
    }
}

package com.Chips.yolo11ncnn;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.Chips.yolo11ncnn.databinding.VideoBinding;
import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.MyLocationStyle;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class V extends AppCompatActivity implements AMapLocationListener {
    private static final String TAG = "VideoCapture";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    private static final int OPTIONAL_PERMISSION_REQUEST = 12;
    private static final int GPS_BUFFER_SIZE = 32;
    private static final long MAX_GPS_DIFFERENCE_MILLIS = 2000;

    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final List<Future<?>> extractionTasks = new ArrayList<>();
    private VideoBinding viewBinding;
    private VideoCapture<Recorder> videoCapture;
    private ProcessCameraProvider cameraProvider;
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private MapView mapView;
    private AMap map;
    private LocationSource.OnLocationChangedListener mapLocationListener;
    private AMapLocationClient locationClient;
    private RecordingAttempt currentAttempt;
    private int cameraGeneration;
    private boolean resumed;
    private volatile boolean destroyed;

    /** Immutable metadata passed to background processing. */
    private static final class Session {
        final String name;
        final long startTimeMillis;
        final File outputDir;
        final File gpsFile;

        Session(String name, long startTimeMillis, File outputDir) {
            this.name = name;
            this.startTimeMillis = startTimeMillis;
            this.outputDir = outputDir;
            gpsFile = new File(outputDir, "GPS_" + name + ".csv");
        }
    }

    /** Main-thread state; CameraX callbacks capture their own attempt. */
    private static final class RecordingAttempt {
        final String name;
        final File outputDir;
        final List<String> gpsBuffer = new ArrayList<>(GPS_BUFFER_SIZE);
        Recording recording;
        Session session;
        long lastGpsTimeMillis;
        boolean collectingGps;
        boolean stopping;
        boolean finalized;

        RecordingAttempt(String name, File outputDir) {
            this.name = name;
            this.outputDir = outputDir;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inflation constructs MapView, so privacy setup must precede it.
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        viewBinding = VideoBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());
        mapView = viewBinding.mapCamera;
        mapView.onCreate(savedInstanceState);
        map = mapView.getMap();
        // Reuse our GPS fixes for the map instead of starting a second SDK location client.
        map.setLocationSource(new LocationSource() {
            @Override public void activate(OnLocationChangedListener listener) {
                mapLocationListener = listener;
            }

            @Override public void deactivate() {
                mapLocationListener = null;
            }
        });
        map.setOnMapTouchListener(event -> mapView.getParent().requestDisallowInterceptTouchEvent(
                event.getActionMasked() != android.view.MotionEvent.ACTION_UP
                        && event.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL));
        MyLocationStyle style = new MyLocationStyle()
                .myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE).interval(1000);
        map.setMyLocationStyle(style);
        map.getUiSettings().setMyLocationButtonEnabled(true);
        map.moveCamera(CameraUpdateFactory.zoomTo(15));
        viewBinding.toolbarBack.setOnClickListener(view -> finish());
        viewBinding.imageViewStart.setOnClickListener(view -> startRecording());
        viewBinding.imageViewStop.setOnClickListener(view -> stopRecording());
        viewBinding.imageViewSwitch.setOnClickListener(view -> switchCamera());
        updateControls(false, false);
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera();
            requestLocationPermission();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void requestLocationPermission() {
        if (hasLocationPermission()) {
            startLocation();
            requestOptionalPermissions();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
    }

    private void requestOptionalPermissions() {
        List<String> permissions = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]),
                    OPTIONAL_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (destroyed) return;
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera();
            else showMessage("请授予相机权限后重新进入视频采集");
            requestLocationPermission();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (hasLocationPermission()) startLocation();
            else showMessage("未授予定位权限，视频仍可录制，GPS 数据将为空");
            requestOptionalPermissions();
        } else if (requestCode == OPTIONAL_PERMISSION_REQUEST
                && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            showMessage("未授予麦克风权限，将静音录制");
        }
    }

    private void switchCamera() {
        if (currentAttempt != null) return;
        cameraSelector = cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
                ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        startCamera();
    }

    private void startCamera() {
        if (destroyed || !hasPermission(Manifest.permission.CAMERA)) return;
        int generation = ++cameraGeneration;
        videoCapture = null;
        updateControls(false, false);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (destroyed || generation != cameraGeneration || currentAttempt != null) return;
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewBinding.previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build();
                videoCapture = VideoCapture.withOutput(recorder);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
                updateControls(false, false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cameraFailed(exception);
            } catch (ExecutionException | RuntimeException exception) {
                cameraFailed(exception);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void cameraFailed(Exception exception) {
        videoCapture = null;
        updateControls(false, false);
        updateRecordingStatus(R.string.recording_failed, R.string.recording_hint_failed);
        Log.e(TAG, "无法启动相机", exception);
        showMessage("无法启动相机，请关闭占用相机的应用后重试");
    }

    private void startLocation() {
        if (destroyed || !resumed || !hasLocationPermission()) return;
        map.setMyLocationEnabled(true);
        if (locationClient == null) {
            try {
                locationClient = new AMapLocationClient(getApplicationContext());
                AMapLocationClientOption options = new AMapLocationClientOption();
                options.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                options.setInterval(1000);
                options.setNeedAddress(false);
                options.setLocationCacheEnable(false);
                options.setSensorEnable(true);
                options.setGpsFirst(true);
                locationClient.setLocationOption(options);
                locationClient.setLocationListener(this);
            } catch (Exception exception) {
                Log.e(TAG, "无法初始化定位", exception);
                showMessage("定位初始化失败，录制时将保留缺失定位状态");
                return;
            }
        }
        locationClient.startLocation();
    }

    private void stopLocation() {
        if (locationClient != null) locationClient.stopLocation();
        if (map != null) map.setMyLocationEnabled(false);
    }

    private void startRecording() {
        if (currentAttempt != null || videoCapture == null || destroyed) return;
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showMessage("请先授予相机权限");
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, OPTIONAL_PERMISSION_REQUEST);
            showMessage("Android 9 及以下需要存储权限才能保存视频");
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!hasLocationPermission() || manager == null
                || (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
            showMessage("定位未开启，视频仍会保存，缺失的位置将标记为空");
        }
        File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (picturesDir == null) {
            showMessage("存储空间不可用，无法开始录制");
            return;
        }
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis());
        RecordingAttempt attempt = new RecordingAttempt(name, new File(picturesDir, name));
        currentAttempt = attempt;
        updateControls(true, false);
        startLocation();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-video");
        }
        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build();
        try {
            PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled();
            }
            attempt.recording = pending.start(ContextCompat.getMainExecutor(this),
                    event -> onRecordingEvent(attempt, event));
        } catch (RuntimeException exception) {
            failRecording(attempt, exception);
        }
    }

    private void stopRecording() {
        RecordingAttempt attempt = currentAttempt;
        if (attempt == null || attempt.stopping || attempt.finalized) return;
        attempt.stopping = true;
        attempt.collectingGps = false;
        flushGps(attempt);
        stopLocation();
        updateRecordingStatus(R.string.recording_saving, R.string.recording_hint_saving);
        updateControls(false, true);
        try {
            if (attempt.recording != null) attempt.recording.stop();
        } catch (RuntimeException exception) {
            failRecording(attempt, exception);
        }
    }

    private void onRecordingEvent(RecordingAttempt attempt, VideoRecordEvent event) {
        if (attempt.finalized) return;
        if (event instanceof VideoRecordEvent.Start) {
            long startTimeMillis = System.currentTimeMillis()
                    - event.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
            attempt.session = new Session(attempt.name, startTimeMillis, attempt.outputDir);
            attempt.collectingGps = !attempt.stopping && !destroyed;
            if (currentAttempt == attempt && !attempt.stopping) {
                updateRecordingStatus(R.string.recording_active, R.string.recording_hint_active);
                updateControls(true, false);
            }
        } else if (event instanceof VideoRecordEvent.Finalize) {
            attempt.finalized = true;
            attempt.collectingGps = false;
            attempt.recording = null; // Finalize releases CameraX's recording handle.
            flushGps(attempt);
            if (currentAttempt == attempt) {
                currentAttempt = null;
                stopLocation();
                updateControls(false, false);
            }
            VideoRecordEvent.Finalize result = (VideoRecordEvent.Finalize) event;
            if (result.hasError()) {
                updateRecordingStatus(R.string.recording_failed, R.string.recording_hint_failed);
                Log.e(TAG, "视频录制失败，错误码: " + result.getError(), result.getCause());
                showMessage("视频录制失败，请检查存储空间后重试");
                return;
            }
            updateRecordingStatus(R.string.recording_complete, R.string.recording_hint_complete);
            showMessage("视频已保存，正在后台抽帧并匹配 GPS");
            Session session = attempt.session;
            if (session != null && !destroyed) {
                Uri outputUri = result.getOutputResults().getOutputUri();
                // Queued GPS batches finish before this starts extraction.
                fileExecutor.execute(() -> extractAndMatch(session, outputUri));
            }
        }
    }

    private void failRecording(RecordingAttempt attempt, RuntimeException exception) {
        if (attempt.finalized) return;
        attempt.finalized = true;
        attempt.collectingGps = false;
        flushGps(attempt);
        Recording handle = attempt.recording;
        attempt.recording = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (RuntimeException closeError) {
                Log.w(TAG, "无法关闭失败的录制", closeError);
            }
        }
        if (currentAttempt == attempt) currentAttempt = null;
        stopLocation();
        updateControls(false, false);
        updateRecordingStatus(R.string.recording_failed, R.string.recording_hint_failed);
        Log.e(TAG, "录制未能完成", exception);
        showMessage("无法完成录制，请重试");
    }

    @Override
    public void onLocationChanged(AMapLocation location) {
        if (destroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> onLocationChanged(location));
            return;
        }
        LocationAcc.updateAccuracyText(location, viewBinding.accuracyCamera);
        if (!LocationAcc.isValidLocation(location)) {
            if (location != null) {
                Log.w(TAG, "定位失败: " + location.getErrorCode() + " " + location.getErrorInfo());
            }
            return;
        }
        if (mapLocationListener != null) mapLocationListener.onLocationChanged(location);
        RecordingAttempt attempt = currentAttempt;
        if (attempt == null || !attempt.collectingGps || attempt.session == null) return;
        long timestamp = location.getTime();
        if (timestamp < attempt.session.startTimeMillis || timestamp <= attempt.lastGpsTimeMillis) return;
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        attempt.lastGpsTimeMillis = timestamp;
        attempt.gpsBuffer.add(String.format(Locale.US, "%d,%s,%.8f,%.8f,%.2f", timestamp,
                TimeUtils.formatTimestamp(timestamp), latitude, longitude, location.getAccuracy()));
        if (attempt.gpsBuffer.size() >= GPS_BUFFER_SIZE) flushGps(attempt);
    }

    private void flushGps(RecordingAttempt attempt) {
        if (attempt.gpsBuffer.isEmpty() || attempt.session == null) return;
        Session session = attempt.session;
        List<String> batch = new ArrayList<>(attempt.gpsBuffer);
        attempt.gpsBuffer.clear();
        fileExecutor.execute(() -> {
            try {
                ensureDirectory(session.outputDir);
                boolean writeHeader = !session.gpsFile.exists() || session.gpsFile.length() == 0;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(session.gpsFile, true), StandardCharsets.UTF_8))) {
                    if (writeHeader) {
                        writer.write("timestampMillis,ISO8601,lat,lon,accuracy");
                        writer.newLine();
                    }
                    for (String record : batch) {
                        writer.write(record);
                        writer.newLine();
                    }
                }
            } catch (IOException exception) {
                Log.e(TAG, "无法写入 GPS 数据: " + session.name, exception);
                showMessage("GPS 数据保存失败，请检查存储空间");
            }
        });
    }

    private void extractAndMatch(Session session, Uri outputUri) {
        if (destroyed || Thread.currentThread().isInterrupted()) return;
        Future<?> task = Frame.extract(getApplicationContext(), outputUri, session.name, new Frame.Callback() {
            @Override
            public void onComplete(int totalFrames, File outputDir) {
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                try {
                    List<Match.GpsPoint> points = Match.readGpsFile(session.gpsFile,
                            new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(session.startTimeMillis));
                    writeFrameMatches(session, points);
                    showMessage(session.name + " 处理完成，共 " + totalFrames + " 帧");
                } catch (IOException exception) {
                    Log.e(TAG, "GPS 匹配失败: " + session.name, exception);
                    showMessage("GPS 匹配失败: " + exception.getMessage());
                }
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "抽帧失败: " + session.name + ": " + message);
                showMessage("抽帧失败: " + message);
            }
        });
        synchronized (extractionTasks) {
            extractionTasks.removeIf(Future::isDone);
            if (destroyed) task.cancel(true);
            else extractionTasks.add(task);
        }
    }

    private void writeFrameMatches(Session session, List<Match.GpsPoint> points) throws IOException {
        File timestamps = new File(session.outputDir, "frames.csv");
        File temporaryCsv = new File(session.outputDir, "匹配结果.csv.part");
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(timestamps), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                         new FileOutputStream(temporaryCsv), StandardCharsets.UTF_8))) {
                writer.write('\ufeff');
                writer.write("帧名,时间,纬度,经度,精度(米),GPS时间,时间差(毫秒),状态");
                writer.newLine();
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted() || destroyed) {
                        throw new IOException("处理已取消");
                    }
                    String[] columns = line.split(",", -1);
                    if (columns.length < 2) throw new IOException("视频帧时间戳格式无效");
                    long ptsMicros;
                    try {
                        ptsMicros = Long.parseLong(columns[1]);
                    } catch (NumberFormatException exception) {
                        throw new IOException("视频帧时间戳格式无效", exception);
                    }
                    long frameTime = session.startTimeMillis + ptsMicros / 1000;
                    String timestamp = TimeUtils.formatTimestamp(frameTime);
                    Match.GpsPoint point = Match.findClosestGpsRecord(points, frameTime,
                            MAX_GPS_DIFFERENCE_MILLIS);
                    if (point == null) {
                        writer.write(columns[0] + "," + timestamp + ",,,,,,未匹配");
                    } else {
                        writer.write(String.format(Locale.US, "%s,%s,%.8f,%.8f,%.2f,%s,%d,已匹配",
                                columns[0], timestamp, point.latitude, point.longitude, point.accuracy,
                                TimeUtils.formatTimestamp(point.timeMillis),
                                Math.abs(point.timeMillis - frameTime)));
                    }
                    writer.newLine();
                }
            }
            if (!temporaryCsv.renameTo(new File(session.outputDir, "匹配结果.csv"))) {
                throw new IOException("无法保存 GPS 匹配结果");
            }
        } finally {
            if (temporaryCsv.exists() && !temporaryCsv.delete()) {
                Log.w(TAG, "无法删除未完成的匹配结果: " + temporaryCsv);
            }
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建目录: " + directory.getAbsolutePath());
        }
    }

    private void updateRecordingStatus(int statusRes, int hintRes) {
        if (destroyed) return;
        viewBinding.recordingStatus.setText(statusRes);
        boolean alert = statusRes == R.string.recording_active || statusRes == R.string.recording_failed;
        viewBinding.recordingStatus.setTextColor(ContextCompat.getColor(this,
                alert ? R.color.ui_danger : R.color.ui_primary));
        viewBinding.recordingHint.setText(hintRes);
    }

    private void updateControls(boolean recording, boolean saving) {
        if (destroyed) return;
        boolean ready = videoCapture != null && !recording && !saving;
        viewBinding.imageViewStart.setVisibility(recording ? View.GONE : View.VISIBLE);
        viewBinding.imageViewStop.setVisibility(recording ? View.VISIBLE : View.GONE);
        viewBinding.imageViewStart.setEnabled(ready);
        viewBinding.imageViewStart.setAlpha(ready ? 1f : 0.4f);
        viewBinding.imageViewSwitch.setEnabled(!recording && !saving
                && hasPermission(Manifest.permission.CAMERA));
        viewBinding.cameraSwitchControl.setVisibility(recording || saving ? View.GONE : View.VISIBLE);
    }

    private void showMessage(String message) {
        runOnUiThread(() -> {
            if (!destroyed) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (mapView != null) mapView.onResume();
        startLocation();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopRecording();
        stopLocation();
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        stopRecording();
        destroyed = true;
        if (locationClient != null) {
            locationClient.stopLocation();
            locationClient.onDestroy();
            locationClient = null;
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        synchronized (extractionTasks) {
            for (Future<?> task : extractionTasks) task.cancel(true);
            extractionTasks.clear();
        }
        // Finish queued GPS writes; cancelled extraction performs its own cleanup.
        fileExecutor.shutdown();
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }
}

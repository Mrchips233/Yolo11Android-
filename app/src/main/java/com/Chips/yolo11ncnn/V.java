package com.Chips.yolo11ncnn;

import static com.Chips.yolo11ncnn.TimeUtils.convertMillisToTime;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
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

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.Chips.yolo11ncnn.databinding.VideoBinding;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.MyLocationStyle;
import com.google.common.util.concurrent.ListenableFuture;

public class V extends AppCompatActivity implements AMapLocationListener {
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private CameraSelector cameraSelector;
    private VideoBinding viewBinding;
    private ExecutorService cameraExecutor;
    private static String[] REQUIRED_PERMISSIONS;
    private static final String TAG = "Test";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private LocationManager locationManager;
    private String currentVideoName;
    private MapView mMapView;
    private AMap aMap;
    public AMapLocationClient mapLocationClient;
    public AMapLocationClientOption mapLocationClientOption;
    private boolean isRecording;
    private static final int BUFFER_SIZE = 100; // 缓存100条记录后写入
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 单个文件最大10MB
    private List<String> gpsBuffer = new ArrayList<>(BUFFER_SIZE);
    private String currentFileName;
    private long videoStartTimeMillis;



    private AMapLocation lastLocation = null;

    @Override
    protected void onCreate(Bundle saveInstanceState) {
        super.onCreate(saveInstanceState);
        viewBinding = VideoBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (allPermissionsGranted()) {
            iniLocation();
            mapLocationClient.startLocation();
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        //Amap
        mMapView = findViewById(R.id.mapCamera);
        mMapView.onCreate(saveInstanceState);
        if (aMap == null) {
            aMap = mMapView.getMap();
        }
        MyLocationStyle myLocationStyle;
        myLocationStyle = new MyLocationStyle();
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE);
        myLocationStyle.interval(1000);
        aMap.getUiSettings().setMyLocationButtonEnabled(true);
        aMap.setMyLocationStyle(myLocationStyle);
        aMap.setMyLocationEnabled(true);
        aMap.moveCamera(CameraUpdateFactory.zoomTo(15));

        //Private Permission
        MapsInitializer.updatePrivacyAgree(this, true);
        MapsInitializer.updatePrivacyShow(this, true, true);

        //View Options
        viewBinding.imageViewStart.setOnClickListener(v -> startRecording());
        viewBinding.imageViewStop.setOnClickListener(v -> stopRecording());
        viewBinding.imageViewStop.setVisibility(View.GONE);
        viewBinding.imageViewSwitch.setOnClickListener(v -> CameraSelector());
        cameraExecutor = Executors.newSingleThreadExecutor();



    }

    private void CameraSelector() {
        if (cameraSelector == null) {
            return;
        }
        if (CameraSelector.DEFAULT_BACK_CAMERA == cameraSelector) {
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
        } else {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        }
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderListenableFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderListenableFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderListenableFuture.get();
                Preview preview = new Preview.Builder()
                        .build();
                preview.setSurfaceProvider(viewBinding.previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                if (cameraSelector == null) {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                }
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProviderFuture 失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void iniLocation() {
        try {
            mapLocationClient = new AMapLocationClient(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "初始化失败" + getApplicationContext());
        }
        if (mapLocationClient != null) {
            mapLocationClient.setLocationListener(this);
            mapLocationClientOption = new AMapLocationClientOption();
            mapLocationClientOption.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            mapLocationClientOption.setOnceLocationLatest(false);
            mapLocationClientOption.setInterval(33);
            mapLocationClientOption.setNeedAddress(false);
            mapLocationClientOption.setHttpTimeOut(20000);
            mapLocationClientOption.setLocationCacheEnable(false);
            mapLocationClient.setLocationOption(mapLocationClientOption);
            mapLocationClientOption.setSensorEnable(true);
            mapLocationClientOption.setGpsFirst(true);
        }
    }

    private void startRecording() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "请开启定位", Toast.LENGTH_LONG).show();
            return;
        }
        videoStartTimeMillis = System.currentTimeMillis(); // 记录开始录制的时间
        String startTime = TimeUtils.convertMillisToTime(videoStartTimeMillis);
        isRecording = true;
        videoCapture();
    }

    private void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
            WriteToFile();
        }
        viewBinding.imageViewStart.setVisibility(View.VISIBLE);
        viewBinding.imageViewStop.setVisibility(View.GONE);
        viewBinding.imageViewSwitch.setVisibility(View.VISIBLE);
    }


    private void videoCapture() {
        VideoCapture<Recorder> videoCapture = this.videoCapture;
        if (videoCapture == null) {
            return;
        }

        viewBinding.imageViewStart.setEnabled(false);

        Recording curRecording = recording;
        if (curRecording != null) {
            curRecording.stop();
            recording = null;
            return;
        }

        currentVideoName = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, currentVideoName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-video");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();

        Recorder recorder = videoCapture.getOutput();
        PendingRecording pendingRecording = recorder.prepareRecording(this, mediaStoreOutputOptions);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled();
        }

        recording = pendingRecording.start(ContextCompat.getMainExecutor(this), RecordEvent -> {
            if (RecordEvent instanceof VideoRecordEvent.Start) {
                viewBinding.imageViewStart.setVisibility(View.GONE);
                viewBinding.imageViewStop.setVisibility(View.VISIBLE);
                viewBinding.imageViewStart.setEnabled(true);
                viewBinding.imageViewSwitch.setVisibility(View.GONE);
                videoStartTimeMillis = System.currentTimeMillis();
                Log.d(TAG, "录制开始");
            } else if (RecordEvent instanceof VideoRecordEvent.Finalize) {
                Log.d(TAG, "录制结束");
                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) RecordEvent;
                if (!finalizeEvent.hasError()) {
                    String msg = "完成" + finalizeEvent.getOutputResults().getOutputUri();
                    Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, msg);
                } else {
                    recording.close();
                    recording = null;
                    Log.e(TAG, "视频录制出错：" + finalizeEvent.getError());
                }
                viewBinding.imageViewStart.setVisibility(View.VISIBLE);
                viewBinding.imageViewStop.setVisibility(View.GONE);
                viewBinding.imageViewStart.setEnabled(true);
                viewBinding.imageViewSwitch.setVisibility(View.VISIBLE);
                if (!finalizeEvent.hasError()) {
                    Uri outputUri = finalizeEvent.getOutputResults().getOutputUri();
                    Log.d(TAG, "录制完成，Uri: " + outputUri);
                    Toast.makeText(getApplicationContext(), "录制完成", Toast.LENGTH_SHORT).show();

                    String realPath = FileUtils.getpath(V.this, outputUri);
                    if (realPath == null) {
                        Toast.makeText(V.this, "获取视频路径失败", Toast.LENGTH_LONG).show();
                        return;
                    }

                    File file = new File(realPath);
                    if (!file.exists()) {
                        Toast.makeText(V.this, "视频文件不存在: " + realPath, Toast.LENGTH_LONG).show();
                        return;
                    }
                    WriteToFile();
                    Frame.extract(V.this, realPath, currentVideoName, new Frame.Callback() {
                        @Override
                        public void onComplete(int totalFrames, File outputDir) {
                            Log.d(TAG, "提取完成，总帧数: " + totalFrames + ", 输出目录: " + outputDir.getAbsolutePath());
                            runOnUiThread(() -> Toast.makeText(V.this, "帧提取完成", Toast.LENGTH_SHORT).show());


                            File framesDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), currentVideoName);
                            File gpsFile = new File(framesDir, currentFileName);
                            Log.d(TAG, "framesDir 路径: " + framesDir.getAbsolutePath());
                            Log.d(TAG, "gpsFile 路径: " + gpsFile.getAbsolutePath());

                            SimpleDateFormat dateSdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                            String recordDate = dateSdf.format(new Date(videoStartTimeMillis));
                            List<Match.GpsPoint> gpsRecords = Match.readGpsFile(gpsFile , recordDate);
                            if (gpsRecords.isEmpty()) {
                                Log.e(TAG, "GPS数据为空，无法匹配");
                                return;
                            }
                            double frameRate = 30.0;
                            matchAllFrames(framesDir, gpsRecords, videoStartTimeMillis, frameRate);
                        }

                        @Override
                        public void onError(String message) {
                            Log.e(TAG, "提取帧出错: " + message);
                            runOnUiThread(() -> Toast.makeText(V.this, "提取帧失败: " + message, Toast.LENGTH_LONG).show());
                        }
                    });


                } else {
                    recording.close();
                    recording = null;
                    Log.e(TAG, "视频录制出错：" + finalizeEvent.getError());
                }
            }
        });
    }

    private boolean isLocationEnabled() {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        mMapView.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mMapView.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mMapView.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                iniLocation();
                mapLocationClient.startLocation();
                startCamera();
            } else {
                Toast.makeText(this, "用户未授予权限。", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    static {
        REQUIRED_PERMISSIONS = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            String[] additionalPermissions = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            String[] temp = new String[REQUIRED_PERMISSIONS.length + additionalPermissions.length];
            System.arraycopy(REQUIRED_PERMISSIONS, 0, temp, 0, REQUIRED_PERMISSIONS.length);
            System.arraycopy(additionalPermissions, 0, temp, REQUIRED_PERMISSIONS.length, additionalPermissions.length);
            REQUIRED_PERMISSIONS = temp;
        }
    }

    @Override
    public void onLocationChanged(AMapLocation aMapLocation) {
        if (aMapLocation != null && aMapLocation.getErrorCode() == 0) {
            // 获取并更新显示定位信息的 TextView
            TextView accuracyTextView = viewBinding.accuracyCamera;
            LocationAcc.updateAccuracyText(aMapLocation, accuracyTextView);
        } else {
            // 如果定位失败，记录错误信息
            assert aMapLocation != null;
            Log.e(TAG, "定位失败: 错误码 = " + aMapLocation.getErrorCode() + " 错误信息 = " + aMapLocation.getErrorInfo());
        }
        if (aMapLocation.getErrorCode() == 0 && isRecording) {
            if (lastLocation != null) {
                interpolateAndAddToBuffer(lastLocation, aMapLocation);
            }
            lastLocation = aMapLocation;
        }
    }

    private void interpolateAndAddToBuffer(AMapLocation last, AMapLocation current) {
        double lat1 = last.getLatitude();
        double lon1 = last.getLongitude();
        float acc1 = last.getAccuracy();

        double lat2 = current.getLatitude();
        double lon2 = current.getLongitude();
        float acc2 = current.getAccuracy();

        long t1 = last.getTime();
        long t2 = current.getTime();
        long interval = t2 - t1;
        if (interval <= 0) return;

        for (int i = 1; i <= 30; i++) {
            double fraction = i / 30.0;

            double lat = lat1 + (lat2 - lat1) * fraction;
            double lon = lon1 + (lon2 - lon1) * fraction;
            float acc = acc1 + (acc2 - acc1) * (float) fraction;
            long ts = t1 + (long) (interval * fraction);

            String simulatedData = String.format(Locale.getDefault(),
                    "纬度: %f,经度: %f,精度: %.2f 米,时间: %s",
                    lat, lon, acc, convertMillisToTime(ts));
            addToBuffer(simulatedData);
        }
    }

    private synchronized void addToBuffer(String data) {
        gpsBuffer.add(data);
        if (gpsBuffer.size() >= BUFFER_SIZE) {
            WriteToFile();
        }
    }

    public void WriteToFile() {
        if (gpsBuffer.isEmpty()) return;
        FileOutputStream fos = null;
        BufferedWriter writer = null;
        try {
            File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), currentVideoName);
            if (!file.exists()) {
                boolean mkdirs = file.mkdirs();
                Log.d(TAG, "创建目录" + file.getAbsolutePath() + "结果：" + mkdirs);
            }
            if (currentFileName == null) {
                currentFileName = "GPS_" + currentVideoName + "_.txt";
            } else {
                File gpsFile = new File(file, currentFileName);
                if (gpsFile.exists() && gpsFile.length() > MAX_FILE_SIZE) {
                    currentFileName = "GPS_" + currentVideoName + "_" + System.currentTimeMillis() + "_.txt";
                }
            }
            File GPSfile = new File(file, currentFileName);
            fos = new FileOutputStream(GPSfile, true);
            writer = new BufferedWriter(new OutputStreamWriter(fos));
            for (String record : gpsBuffer) {
                writer.write(record);
                writer.newLine();
            }
            gpsBuffer.clear();
        } catch (IOException e) {
            Log.e(TAG, "写入失败" + e.getMessage());
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "关闭失败" + e.getMessage());
            }
        }
    }

    private void matchAllFrames(File framesDir, List<Match.GpsPoint> gpsRecords, long videoStartTimeMillis, double frameRate) {
        if (!framesDir.exists() || !framesDir.isDirectory()) {
            Log.e(TAG, "帧目录不存在或不是文件夹：" + framesDir.getAbsolutePath());
            return;
        }
        File[] frameFiles = framesDir.listFiles((dir, name) -> name.toLowerCase().matches("frame\\d+\\.jpeg"));

        if (frameFiles == null || frameFiles.length == 0) {
            Log.e(TAG, "没有找到任何符合规则的帧文件");
            return;
        }for (File f : frameFiles) {
            Log.d(TAG, "文件名: " + f.getName());
        }

        Arrays.sort(frameFiles, (f1, f2) -> Integer.compare(
                extractFrameIndex(f1.getName()),
                extractFrameIndex(f2.getName())
        ));

            List<String> csvLines = new ArrayList<>();
            csvLines.add("帧名,时间,纬度,经度,精度(米)");

            for (File frameFile : frameFiles) {
                int frameIndex = extractFrameIndex(frameFile.getName());
                if (frameIndex < 0) continue;

                long frameTime = Match.calcFrameTime(videoStartTimeMillis, frameIndex, frameRate);
               Match.GpsPoint gpsRecord = Match.findClosestGpsRecord(gpsRecords, frameTime);

                if (gpsRecord != null) {
                    Log.d(TAG, "匹配成功，帧：" + frameFile.getName() + " GPS时间：" + gpsRecord.timeStr);
                    csvLines.add(String.format(Locale.getDefault(),
                            "%s,\t%s,%.6f,%.6f,%.2f",
                            frameFile.getName(),
                            gpsRecord.timeStr,
                            gpsRecord.latitude,
                            gpsRecord.longitude,
                            gpsRecord.accuracy));

                } else {
                    Log.d(TAG, "图片: " + frameFile.getName() + " 未匹配到GPS点");
                }
            }
            File csvFile = new File(framesDir, "匹配结果.csv");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
                writer.write('\ufeff');
                for (String line : csvLines) {
                    writer.write(line);
                    writer.newLine();
                }
                Log.d(TAG, "匹配结果已写入: " + csvFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "写入匹配结果失败: " + e.getMessage());
            }
        }

    private int extractFrameIndex(String fileName) {
        try {
            String numStr = fileName.replaceAll("^frame(\\d+)\\.JPEG$", "$1");
            return Integer.parseInt(numStr);
        } catch (Exception e) {
            return -1;
        }
    }


}



// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.Chips.yolo11ncnn;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.Chips.yolo11ncnn.databinding.YolocameraBinding;
import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.MyLocationStyle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class YOLO extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "YoloCamera";
    private static final int REQUEST_PERMISSIONS = 100;

    private final YOLO11Ncnn detector = new YOLO11Ncnn();
    private YolocameraBinding binding;
    private AMap map;
    private LocationSource.OnLocationChangedListener mapLocationListener;
    private AMapLocationClient locationClient;
    private int facing;
    private int currentModel;
    private int currentBackend;
    private boolean resumed;
    private volatile NativeRuntime.Session runtime;
    private final AtomicLong runtimeRevision = new AtomicLong();
    private Surface outputSurface;
    // These fields belong only to the process-wide native control executor.
    private int loadedModel = -1;
    private int loadedBackend = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        binding = YolocameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        createRuntimeSession();
        binding.toolbarBack.setOnClickListener(v -> finish());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding.mapYolo.onCreate(savedInstanceState);
        map = binding.mapYolo.getMap();
        map.setLocationSource(new LocationSource() {
            @Override public void activate(OnLocationChangedListener listener) {
                mapLocationListener = listener;
            }

            @Override public void deactivate() {
                mapLocationListener = null;
            }
        });
        map.setOnMapTouchListener(event -> {
            int action = event.getActionMasked();
            binding.mapYolo.getParent().requestDisallowInterceptTouchEvent(
                    action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL);
        });
        MyLocationStyle locationStyle = new MyLocationStyle();
        locationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE);
        locationStyle.interval(1000);
        map.setMyLocationStyle(locationStyle);
        map.getUiSettings().setMyLocationButtonEnabled(true);
        map.moveCamera(CameraUpdateFactory.zoomTo(15));

        binding.cameraview.getHolder().setFormat(PixelFormat.RGBA_8888);
        binding.cameraview.getHolder().addCallback(this);
        if (savedInstanceState != null) {
            facing = savedInstanceState.getInt("facing", 0) == 1 ? 1 : 0;
            int savedModel = savedInstanceState.getInt("model", 0);
            int modelCount = getResources().getStringArray(R.array.model_array).length;
            currentModel = savedModel >= 0 && savedModel < modelCount ? savedModel : 0;
            currentBackend = Math.max(0, Math.min(2, savedInstanceState.getInt("backend", 0)));
        }
        binding.spinnerModel.setSelection(currentModel);
        binding.spinnerCPUGPU.setSelection(currentBackend);
        binding.spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (currentModel != position) {
                    currentModel = position;
                    reloadModel();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        binding.spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (currentBackend != position) {
                    currentBackend = position;
                    reloadModel();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        binding.buttonSwitchCamera.setOnClickListener(v -> {
            if (!resumed || !hasPermission(Manifest.permission.CAMERA)) return;
            facing = 1 - facing;
            reloadModel();
        });

        reloadModel();
        requestMissingPermissions();
    }

    private void createRuntimeSession() {
        runtimeRevision.incrementAndGet();
        runtime = NativeRuntime.openSession(() -> {
            cleanupNativeStep(() -> detector.closeCamera());
            cleanupNativeStep(() -> detector.setOutputWindow(null));
            cleanupNativeStep(() -> setOutputPath(null));
            cleanupNativeStep(() -> setOutputMetadata(null, Double.NaN, Double.NaN));
        });
        File directory = getExternalFilesDir(null);
        if (directory == null) directory = getFilesDir();
        String path = new File(directory, System.currentTimeMillis() + "-results.csv").getAbsolutePath();
        runtime.submit(() -> {
            loadedModel = -1;
            loadedBackend = -1;
            setOutputPath(path);
            setOutputMetadata(null, Double.NaN, Double.NaN);
        });
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
        if (!hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void cleanupNativeStep(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Unable to complete native cleanup", error);
        }
    }

    private boolean isRuntimeRequestCurrent(long revision) {
        return runtime != null && runtime.isCurrent() && runtimeRevision.get() == revision;
    }

    private void showRuntimeStatus(long revision, String text, int errorMessage) {
        runOnUiThread(() -> {
            if (!isRuntimeRequestCurrent(revision)) return;
            binding.runtimeStatus.setText(text);
            if (errorMessage != 0) Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        });
    }

    private void reloadModel() { updateRuntime(); }
    private void startCameraIfReady() { updateRuntime(); }

    private void updateRuntime() {
        if (runtime == null || !runtime.isCurrent()) return;
        final long revision = runtimeRevision.incrementAndGet();
        final int model = currentModel;
        final int backend = currentBackend;
        final int requestedFacing = facing;
        final Surface surface = outputSurface;
        final boolean runCamera = resumed && hasPermission(Manifest.permission.CAMERA)
                && surface != null && surface.isValid();
        binding.runtimeStatus.setText(runCamera ? "正在切换模型与相机…" : "等待相机画面或权限");
        runtime.submit(() -> {
            try {
                if (!isRuntimeRequestCurrent(revision)) return;
                // close joins the current copied frame. It runs on this executor,
                // with no model/window lock held, and never blocks the UI thread.
                detector.closeCamera();
                if (!isRuntimeRequestCurrent(revision)) return;
                if (!runCamera || !surface.isValid()) {
                    detector.setOutputWindow(null);
                    showRuntimeStatus(revision, "相机已暂停", 0);
                    return;
                }
                if (loadedModel != model || loadedBackend != backend) {
                    loadedModel = -1;
                    loadedBackend = -1;
                    if (!detector.loadModel(getAssets(), 0, model, backend)) {
                        detector.setOutputWindow(null);
                        showRuntimeStatus(revision, "模型加载失败，请选择可用后端", R.string.model_load_failed);
                        return;
                    }
                    loadedModel = model;
                    loadedBackend = backend;
                }
                if (!isRuntimeRequestCurrent(revision) || !surface.isValid()) return;
                if (!detector.setOutputWindow(surface) || !detector.openCamera(requestedFacing)) {
                    detector.closeCamera();
                    detector.setOutputWindow(null);
                    showRuntimeStatus(revision, "相机启动失败", R.string.camera_open_failed);
                    return;
                }
                if (!isRuntimeRequestCurrent(revision)) {
                    // Main-thread pause/rotation/selection may invalidate a task
                    // while openCamera is in progress. Never leave that camera live.
                    detector.closeCamera();
                    detector.setOutputWindow(null);
                    return;
                }
                String input = model == 0 ? "960" : "640";
                String mode = backend == 0 ? "CPU" : backend == 1 ? "GPU" : "Turnip";
                showRuntimeStatus(revision, "检测中 · " + input + " · " + mode, 0);
            } catch (RuntimeException | LinkageError error) {
                Log.e(TAG, "Unable to configure native detector", error);
                loadedModel = -1;
                loadedBackend = -1;
                cleanupNativeStep(() -> detector.closeCamera());
                cleanupNativeStep(() -> detector.setOutputWindow(null));
                showRuntimeStatus(revision, "运行环境初始化失败，请重新选择模型或后端", R.string.model_load_failed);
            }
        });
    }

    private void startLocationIfAllowed() {
        boolean allowed = hasLocationPermission();
        map.setMyLocationEnabled(allowed && resumed);
        if (!allowed || !resumed) return;
        if (locationClient == null) {
            try {
                locationClient = new AMapLocationClient(getApplicationContext());
                AMapLocationClientOption option = new AMapLocationClientOption();
                option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
                option.setInterval(1000);
                option.setLocationCacheEnable(false);
                locationClient.setLocationOption(option);
                locationClient.setLocationListener(location ->
                        runOnUiThread(() -> updateLocation(location)));
            } catch (Exception error) {
                Log.e(TAG, "Unable to initialize location", error);
                binding.accuracyYolo.setText(R.string.location_failed);
                if (locationClient != null) locationClient.onDestroy();
                locationClient = null;
                return;
            }
        }
        locationClient.startLocation();
    }

    private void updateLocation(AMapLocation location) {
        if (!resumed) return;
        LocationAcc.updateAccuracyText(location, binding.accuracyYolo);
        if (!LocationAcc.isValidLocation(location)) {
            clearMetadata();
            return;
        }
        if (mapLocationListener != null) mapLocationListener.onLocationChanged(location);
        long time = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        String timestamp = TimeUtils.formatTimestamp(time);
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        runtime.submit(() -> setOutputMetadata(timestamp, latitude, longitude));
    }

    private void clearMetadata() {
        if (runtime != null) runtime.submit(() -> setOutputMetadata(null, Double.NaN, Double.NaN));
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        outputSurface = holder.getSurface();
        updateRuntime();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        outputSurface = holder.getSurface();
        updateRuntime();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        outputSurface = null;
        updateRuntime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Returning from video evaluation acquires the process-global native
        // state again; an old Activity/session must never control its successor.
        if (runtime == null || !runtime.isCurrent()) createRuntimeSession();
        binding.mapYolo.onResume();
        startLocationIfAllowed();
        startCameraIfReady();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updateRuntime();
        if (locationClient != null) locationClient.stopLocation();
        map.setMyLocationEnabled(false);
        clearMetadata();
        binding.mapYolo.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        runtimeRevision.incrementAndGet();
        if (runtime != null) runtime.close();
        if (locationClient != null) {
            locationClient.stopLocation();
            locationClient.onDestroy();
            locationClient = null;
        }
        binding.cameraview.getHolder().removeCallback(this);
        binding.mapYolo.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt("facing", facing);
        outState.putInt("model", currentModel);
        outState.putInt("backend", currentBackend);
        binding.mapYolo.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            startLocationIfAllowed();
            startCameraIfReady();
        }
    }

    public native void setOutputPath(String path);
    public native void setOutputMetadata(String time, double latitude, double longitude);
}

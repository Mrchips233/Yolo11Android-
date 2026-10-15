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
    private boolean cameraOpened;
    private boolean modelLoaded;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        binding = YolocameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
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
            currentModel = Math.max(0, Math.min(3, savedInstanceState.getInt("model", 0)));
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
            if (!resumed || !modelLoaded || !hasPermission(Manifest.permission.CAMERA)) return;
            int nextFacing = 1 - facing;
            stopCamera();
            if (detector.openCamera(nextFacing)) {
                facing = nextFacing;
                cameraOpened = true;
            } else {
                Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_LONG).show();
                startCameraIfReady();
            }
        });

        File outputDirectory = getExternalFilesDir(null);
        if (outputDirectory == null) outputDirectory = getFilesDir();
        setOutputPath(new File(outputDirectory,
                System.currentTimeMillis() + "-results.csv").getAbsolutePath());
        clearMetadata();
        reloadModel();
        requestMissingPermissions();
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

    private void reloadModel() {
        modelLoaded = detector.loadModel(getAssets(), 0, currentModel, currentBackend);
        if (modelLoaded) {
            startCameraIfReady();
        } else {
            stopCamera();
            Toast.makeText(this, R.string.model_load_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void startCameraIfReady() {
        if (!resumed || cameraOpened || !modelLoaded || !hasPermission(Manifest.permission.CAMERA)) return;
        cameraOpened = detector.openCamera(facing);
        if (!cameraOpened) Toast.makeText(this, R.string.camera_open_failed, Toast.LENGTH_LONG).show();
    }

    private void stopCamera() {
        if (cameraOpened) {
            detector.closeCamera();
            cameraOpened = false;
        }
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
        setOutputMetadata(TimeUtils.formatTimestamp(time), location.getLatitude(), location.getLongitude());
    }

    private void clearMetadata() {
        setOutputMetadata(null, Double.NaN, Double.NaN);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        detector.setOutputWindow(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        detector.setOutputWindow(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        detector.setOutputWindow(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        binding.mapYolo.onResume();
        startLocationIfAllowed();
        startCameraIfReady();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopCamera();
        if (locationClient != null) locationClient.stopLocation();
        map.setMyLocationEnabled(false);
        clearMetadata();
        binding.mapYolo.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        detector.setOutputWindow(null);
        setOutputPath(null);
        clearMetadata();
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

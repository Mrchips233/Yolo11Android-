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
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;
import com.amap.api.maps.AMap;
import com.Chips.yolo11ncnn.databinding.YolocameraBinding;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.MyLocationStyle;

import java.io.File;


public class YOLO extends AppCompatActivity implements SurfaceHolder.Callback
{
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;
    private long currentTimestamp = 0L;

    public static final int REQUEST_CAMERA = 100;
    public static final int REQUEST_LOCATION=200;

    private YOLO11Ncnn yolo11ncnn = new YOLO11Ncnn();
    private int facing = 0;
    private AMap aMap;
    private Spinner spinnerModel;
    private Spinner spinnerCPUGPU;
    private int current_task = 0;
    private int current_model = 0;
    private int current_cpugpu = 0;
    private AMapLocationClient locationClient;
    private AMapLocationListener locationListener;
    private YolocameraBinding viewBinding;
    private SurfaceView cameraView;


    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        viewBinding = YolocameraBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());
        MapView mapView = viewBinding.mapYolo;
        mapView.onCreate(savedInstanceState);
        if (aMap == null) {
            aMap = mapView.getMap();
        }
        MyLocationStyle myLocationStyle;
        myLocationStyle = new MyLocationStyle();
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE);
        myLocationStyle.interval(33);
        aMap.getUiSettings().setMyLocationButtonEnabled(true);
        aMap.setMyLocationStyle(myLocationStyle);
        aMap.setMyLocationEnabled(true);
        aMap.moveCamera(CameraUpdateFactory.zoomTo(15));
        //隐私
        MapsInitializer.updatePrivacyAgree(this, true);
        MapsInitializer.updatePrivacyShow(this, true, true);


        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraView = viewBinding.cameraview;


        cameraView.getHolder().setFormat(PixelFormat.RGBA_8888);
        cameraView.getHolder().addCallback(this);

        Button buttonSwitchCamera = findViewById(R.id.buttonSwitchCamera);
        buttonSwitchCamera.setOnClickListener(arg0 -> {

            int new_facing = 1 - facing;

            yolo11ncnn.closeCamera();

            yolo11ncnn.openCamera(new_facing);

            facing = new_facing;
        });


        spinnerModel = findViewById(R.id.spinnerModel);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_model)
                {
                    current_model = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });

        spinnerCPUGPU = findViewById(R.id.spinnerCPUGPU);
        spinnerCPUGPU.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id)
            {
                if (position != current_cpugpu)
                {
                    current_cpugpu = position;
                    reload();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0)
            {
            }
        });
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
        } else {
            initLocation();
        }
        reload();
        String fileName = TimeUtils.convertMillisToTime() + "results.csv";
        File outputFile = new File(getExternalFilesDir(null), fileName);

        // 传给 JNI/C++
        setOutputPath(outputFile.getAbsolutePath());
        setOutputMetadata(TimeUtils.convertMillisToTime(), currentLatitude, currentLongitude);


    }


    private void reload()
    {
        boolean ret_init = yolo11ncnn.loadModel(getAssets(), current_task, current_model, current_cpugpu);
        if (!ret_init)
        {
            Log.e("MainActivity", "yolo11ncnn loadModel failed");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
        yolo11ncnn.setOutputWindow(holder.getSurface());
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
    }

    @Override
    public void onResume()
    {
        super.onResume();

        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        {
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }

        yolo11ncnn.openCamera(facing);
    }

    @Override
    public void onPause()
    {
        super.onPause();

        yolo11ncnn.closeCamera();
    }
    private void initLocation(){
        try {
            locationClient = new AMapLocationClient(getApplicationContext());
            locationClient.setLocationListener(aMapLocation -> {
                if(aMapLocation != null){
                    currentLatitude = aMapLocation.getLatitude();
                    currentLongitude = aMapLocation.getLongitude();
                    LocationAcc.updateAccuracyText(aMapLocation, viewBinding.accuracyYolo);
                    updateMetadata();
                }
            });

            AMapLocationClientOption option = new AMapLocationClientOption();
            option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            option.setInterval(1000);
            locationClient.setLocationOption(option);
            locationClient.startLocation();

        } catch (Exception e) {
            Log.e("YoloCamera", "捕获异常: " + e.getMessage(), e);
        }
    }



    public native void setOutputPath(String path);
    public native void setOutputMetadata(String time, double latitude, double longitude);


    static {
        System.loadLibrary("yolo11ncnn"); // 加载你的 native 库
    }
    public void updateMetadata() {
        String time = TimeUtils.convertMillisToTime();
        setOutputMetadata(time, currentLatitude, currentLongitude);
    }

}

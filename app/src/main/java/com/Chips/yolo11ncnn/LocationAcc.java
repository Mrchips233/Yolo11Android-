package com.Chips.yolo11ncnn;

import android.util.Log;
import android.widget.TextView;

import com.amap.api.location.AMapLocation;

import java.util.Locale;

public class LocationAcc {
    private static final String TAG = "Yolo11AccTest";
    public  static void updateAccuracyText(AMapLocation location, TextView textView){
        if(location == null || textView == null ){
            if(textView != null){
                textView.setText("定位信息不可用");
            }
            return;
        }
        if(location.getErrorCode() == 0){
            location.getLocationType();
            double lat = location.getLatitude();
            double lon = location.getLongitude();
            float Accuracy = location.getAccuracy();
            String time = TimeUtils.convertMillisToTime();
            int locationType = location.getLocationType();
            String locationTypeStr = locationTypeString(locationType);
            String status = String.format(Locale.getDefault(),
                    "纬度: %f\n经度: %f\n精度: %.2f 米\n定位类型: %s\n时间: %s",
                    lat, lon, Accuracy, locationTypeStr, time);
            textView.setText(status);
        } else {
            Log.e(TAG, "错误码" + location.getErrorCode() + "错误信息" + location.getErrorInfo());
        }
    }
    private static String  locationTypeString(int locationType){
        switch (locationType) {
            case AMapLocation.LOCATION_TYPE_GPS:
                return "GPS 定位";
            case AMapLocation.LOCATION_TYPE_WIFI:
                return "WiFi 定位";
            case AMapLocation.LOCATION_TYPE_CELL:
                return "基站定位";
            default:
                return "未知类型";
        }
    }
}

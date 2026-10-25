package com.Chips.yolo11ncnn;

import android.widget.TextView;

import com.amap.api.location.AMapLocation;

import java.util.Locale;

public final class LocationAcc {
    private LocationAcc() { }

    public static boolean isValidLocation(AMapLocation location) {
        return location != null && location.getErrorCode() == 0
                && Double.isFinite(location.getLatitude())
                && Double.isFinite(location.getLongitude())
                && Math.abs(location.getLatitude()) <= 90
                && Math.abs(location.getLongitude()) <= 180
                && Float.isFinite(location.getAccuracy()) && location.getAccuracy() >= 0;
    }

    public static void updateAccuracyText(AMapLocation location, TextView textView) {
        if (textView == null) return;
        if (!isValidLocation(location)) {
            textView.setText(location == null ? R.string.location_unavailable : R.string.location_failed);
            return;
        }
        long time = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
        textView.setText(String.format(Locale.US,
                "纬度: %.6f\n经度: %.6f\n精度: %.2f 米\n定位类型: %s\n时间: %s",
                location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                locationTypeString(location.getLocationType()), TimeUtils.convertMillisToTime(time)));
    }

    private static String locationTypeString(int locationType) {
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

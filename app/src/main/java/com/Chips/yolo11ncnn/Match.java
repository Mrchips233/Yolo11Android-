package com.Chips.yolo11ncnn;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Match {

    private static final String TAG = "Match";
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public static class GpsPoint {
        public long timeMillis;
        public String timeStr;
        public double latitude;
        public double longitude;
        public float accuracy;

        public GpsPoint(long timeMillis, String timeStr, double latitude, double longitude, float accuracy) {
            this.timeMillis = timeMillis;
            this.timeStr = timeStr;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
        }
    }

    public static List<GpsPoint> readGpsFile(File gpsFile , String recordDate) {
        List<GpsPoint> records = new ArrayList<>();
        if (gpsFile == null || !gpsFile.exists()) {
            Log.e(TAG, "GPS文件不存在：" + (gpsFile == null ? "null" : gpsFile.getAbsolutePath()));
            return records;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(gpsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 格式示例:
                // 纬度: 29.563010, 经度: 106.551556, 精度: 5.40 米, 时间: 15:30:42.100
                try {
                    String[] parts = line.split(",");
                    if (parts.length < 4) continue;

                    double lat = Double.parseDouble(parts[0].split(":")[1].trim());
                    double lon = Double.parseDouble(parts[1].split(":")[1].trim());
                    float acc = Float.parseFloat(parts[2].split(":")[1].trim().split(" ")[0]);
                    String timeStr = parts[3].split(": ", 2)[1].trim();

                    Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                            .parse(recordDate + " " + timeStr);
                    if (date == null) continue;
                    long timeMillis = date.getTime();

                    records.add(new GpsPoint(timeMillis, timeStr, lat, lon, acc));
                } catch (Exception e) {
                    Log.e(TAG, "解析GPS行失败: " + line + " error: " + e.getMessage());
                }
            }
        }catch(Exception e){
            Log.e(TAG, "读取GPS文件失败: " + e.getMessage());
        }
        return records;
    }
    public static GpsPoint findClosestGpsRecord(List<GpsPoint> records, long targetTime) {
        if (records == null || records.isEmpty()) return null;

        GpsPoint closest = null;
        long minDiff = Long.MAX_VALUE;
        for (GpsPoint r : records) {
            long diff = Math.abs(r.timeMillis - targetTime);
            if (diff < minDiff) {
                minDiff = diff;
                closest = r;
            }
        }
        return closest;
    }
    public static long calcFrameTime(long startTimeMillis, int frameIndex, double frameRate) {
        // 根据帧率计算每帧的时间偏移，单位毫秒
        return startTimeMillis + (long) ((frameIndex / frameRate) * 1000);
    }
}

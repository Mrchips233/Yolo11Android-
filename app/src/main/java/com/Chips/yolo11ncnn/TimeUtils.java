package com.Chips.yolo11ncnn;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TimeUtils {
    public static String convertMillisToTime(long mills){
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        return sdf.format(new Date(mills));
    }
    public static String convertMillisToTime(){
        return convertMillisToTime(System.currentTimeMillis());
    }
}

package com.Chips.yolo11ncnn;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class TimeUtils {
    // SimpleDateFormat is mutable: each worker gets its own formatter.
    private static final ThreadLocal<SimpleDateFormat> TIME = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        }
    };
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        }
    };

    private TimeUtils() { }

    public static String convertMillisToTime(long millis) {
        return format(TIME.get(), millis);
    }

    public static String convertMillisToTime() {
        return convertMillisToTime(System.currentTimeMillis());
    }

    public static String formatTimestamp(long millis) {
        return format(TIMESTAMP.get(), millis);
    }

    private static String format(SimpleDateFormat formatter, long millis) {
        formatter.setTimeZone(TimeZone.getDefault());
        return formatter.format(new Date(millis));
    }
}

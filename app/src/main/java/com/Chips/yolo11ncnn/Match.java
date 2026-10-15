package com.Chips.yolo11ncnn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses dated GPS CSV files and the original Chinese, time-only logs. */
public final class Match {
    public static final long DEFAULT_MAX_GPS_AGE_MILLIS = 2000;
    private static final Logger LOGGER = Logger.getLogger(Match.class.getName());
    private static final Pattern LEGACY_LINE = Pattern.compile(
            "\\s*纬度\\s*:\\s*([^,]+),\\s*经度\\s*:\\s*([^,]+),"
                    + "\\s*精度\\s*:\\s*([^ ]+)\\s*米,\\s*时间\\s*:\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*");

    private Match() { }

    public static final class GpsPoint {
        public final long timeMillis;
        public final String timeStr;
        public final double latitude;
        public final double longitude;
        public final float accuracy;

        public GpsPoint(long timeMillis, String timeStr, double latitude, double longitude,
                        float accuracy) {
            this.timeMillis = timeMillis;
            this.timeStr = timeStr;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
        }
    }

    /** Returns a timestamp-sorted, immutable list; malformed rows are skipped. */
    public static List<GpsPoint> readGpsFile(File gpsFile, String recordDate) {
        if (gpsFile == null || !gpsFile.isFile()) return Collections.emptyList();
        List<GpsPoint> records = new ArrayList<>();
        SimpleDateFormat legacyDate = strictFormat("yyyy-MM-dd HH:mm:ss.SSS");
        SimpleDateFormat isoDate = strictFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        SimpleDateFormat dayFormat = strictFormat("yyyy-MM-dd");
        Calendar legacyDay = Calendar.getInstance();
        Date initialDay = parse(dayFormat, recordDate);
        if (initialDay != null) legacyDay.setTime(initialDay);
        long previousTimeOfDay = -1;
        int invalidRows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(gpsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) break;
                line = line.replace("\ufeff", "").trim();
                if (line.isEmpty() || line.startsWith("timestampMillis,")) continue;
                try {
                    Matcher legacy = LEGACY_LINE.matcher(line);
                    if (legacy.matches()) {
                        if (initialDay == null) throw new IllegalArgumentException("Invalid recording date");
                        double latitude = Double.parseDouble(legacy.group(1));
                        double longitude = Double.parseDouble(legacy.group(2));
                        float accuracy = Float.parseFloat(legacy.group(3));
                        checkCoordinates(latitude, longitude, accuracy);
                        String time = legacy.group(4);
                        Date timestamp = parse(legacyDate, dayFormat.format(legacyDay.getTime()) + " " + time);
                        if (timestamp == null) throw new IllegalArgumentException("Invalid GPS time");
                        Calendar clock = Calendar.getInstance();
                        clock.setTime(timestamp);
                        long timeOfDay = ((clock.get(Calendar.HOUR_OF_DAY) * 60L
                                + clock.get(Calendar.MINUTE)) * 60 + clock.get(Calendar.SECOND))
                                * 1000 + clock.get(Calendar.MILLISECOND);
                        // Old logs omit dates. A large backwards jump marks the next day.
                        if (previousTimeOfDay >= 0 && previousTimeOfDay - timeOfDay > 12 * 60 * 60 * 1000L) {
                            legacyDay.add(Calendar.DAY_OF_MONTH, 1);
                            timestamp = parse(legacyDate, dayFormat.format(legacyDay.getTime()) + " " + time);
                        }
                        if (timestamp == null) throw new IllegalArgumentException("Invalid GPS date");
                        previousTimeOfDay = timeOfDay;
                        records.add(new GpsPoint(timestamp.getTime(), time, latitude, longitude, accuracy));
                    } else {
                        String[] fields = line.split(",", -1);
                        if (fields.length != 5) throw new IllegalArgumentException("Invalid GPS columns");
                        long timestamp = Long.parseLong(fields[0].trim());
                        Date datedTimestamp = parse(isoDate, fields[1].trim());
                        if (datedTimestamp == null || datedTimestamp.getTime() != timestamp) {
                            throw new IllegalArgumentException("Inconsistent GPS timestamp");
                        }
                        double latitude = Double.parseDouble(fields[2].trim());
                        double longitude = Double.parseDouble(fields[3].trim());
                        float accuracy = Float.parseFloat(fields[4].trim());
                        checkCoordinates(latitude, longitude, accuracy);
                        records.add(new GpsPoint(timestamp, fields[1].trim(), latitude, longitude, accuracy));
                    }
                } catch (IllegalArgumentException malformed) {
                    invalidRows++;
                }
            }
        } catch (IOException exception) {
            LOGGER.warning("读取 GPS 文件失败: " + exception.getMessage());
            return Collections.emptyList();
        }
        if (invalidRows > 0) LOGGER.warning("跳过损坏的 GPS 记录: " + invalidRows);
        records.sort(Comparator.comparingLong(point -> point.timeMillis));
        return Collections.unmodifiableList(records);
    }

    /** Input must already be sorted by time, as returned by readGpsFile. */
    public static GpsPoint findClosestGpsRecord(List<GpsPoint> records, long targetTime) {
        return findClosestGpsRecord(records, targetTime, DEFAULT_MAX_GPS_AGE_MILLIS);
    }

    public static GpsPoint findClosestGpsRecord(List<GpsPoint> records, long targetTime,
                                               long maxDifferenceMillis) {
        if (maxDifferenceMillis < 0) throw new IllegalArgumentException("Negative GPS time limit");
        if (records == null || records.isEmpty()) return null;
        int low = 0;
        int high = records.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (records.get(middle).timeMillis < targetTime) low = middle + 1;
            else high = middle;
        }
        GpsPoint closest = low < records.size() ? records.get(low) : null;
        if (low > 0 && (closest == null || distance(records.get(low - 1).timeMillis, targetTime)
                <= distance(closest.timeMillis, targetTime))) {
            closest = records.get(low - 1);
        }
        return closest != null && distance(closest.timeMillis, targetTime) <= maxDifferenceMillis
                ? closest : null;
    }

    private static long distance(long first, long second) {
        long difference = first >= second ? first - second : second - first;
        return difference < 0 ? Long.MAX_VALUE : difference;
    }

    private static void checkCoordinates(double latitude, double longitude, float accuracy) {
        if (Double.isNaN(latitude) || Math.abs(latitude) > 90
                || Double.isNaN(longitude) || Math.abs(longitude) > 180
                || Float.isNaN(accuracy) || Float.isInfinite(accuracy) || accuracy < 0) {
            throw new IllegalArgumentException("Invalid GPS coordinates");
        }
    }

    private static SimpleDateFormat strictFormat(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setLenient(false);
        return format;
    }

    private static Date parse(SimpleDateFormat format, String value) {
        if (value == null) return null;
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        return position.getIndex() == value.length() ? parsed : null;
    }
}

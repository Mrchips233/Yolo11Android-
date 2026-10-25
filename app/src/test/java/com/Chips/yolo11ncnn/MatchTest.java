package com.Chips.yolo11ncnn;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MatchTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Locale previousLocale;
    private TimeZone previousZone;

    @Before public void setUp() {
        previousLocale = Locale.getDefault();
        previousZone = TimeZone.getDefault();
        Locale.setDefault(Locale.GERMANY);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After public void tearDown() {
        Locale.setDefault(previousLocale);
        TimeZone.setDefault(previousZone);
    }

    @Test public void readsLegacyAcrossMidnightAndSkipsDamagedRows() throws Exception {
        List<Match.GpsPoint> points = read(
                "纬度: 29.563010, 经度: 106.551556, 精度: 5.40 米, 时间: 23:59:59.900\n"
                + "损坏的行\n"
                + "纬度: 29.563011,经度: 106.551557,精度: 6.40 米,时间: 00:00:00.100\n",
                "2026-10-15");
        assertEquals(2, points.size());
        assertEquals(200, points.get(1).timeMillis - points.get(0).timeMillis);
        assertEquals(29.563011, points.get(1).latitude, 0.0000001);
    }

    @Test public void rejectsInvalidDatesTimesAndCoordinates() throws Exception {
        assertTrue(read("纬度: 29,经度: 106,精度: 5 米,时间: 00:00:00.000", "2026-02-30").isEmpty());
        assertTrue(read("纬度: 29,经度: 106,精度: 5 米,时间: 25:00:00.000", "2026-10-15").isEmpty());
        assertTrue(read("纬度: NaN,经度: 106,精度: 5 米,时间: 00:00:00.000", "2026-10-15").isEmpty());
    }

    @Test public void sortsDatedCsvOnceAndPreservesTimeZone() throws Exception {
        List<Match.GpsPoint> points = read("\ufefftimestampMillis,ISO8601,lat,lon,accuracy\n"
                + "1792022401000,2026-10-15T08:00:01.000+08:00,29.1,106.2,3.2\n"
                + "1792022400000,2026-10-15T00:00:00.000Z,29.2,106.3,4.2\n"
                + "1792022400000,2026-10-16T00:00:00.000Z,29.2,106.3,4.2\n", "ignored");
        assertEquals(2, points.size());
        assertEquals(1792022400000L, points.get(0).timeMillis);
        assertEquals(1000, points.get(1).timeMillis - points.get(0).timeMillis);
    }

    @Test public void boundsGpsAgeAndResolvesTiesToEarlierPoint() {
        List<Match.GpsPoint> records = java.util.Arrays.asList(point(1000), point(3000));
        assertEquals(1000, Match.findClosestGpsRecord(records, 2000, 1000).timeMillis);
        assertNull(Match.findClosestGpsRecord(records, 6000, 2000));
        assertEquals(3000, Match.findClosestGpsRecord(records, 5000, 2000).timeMillis);
        assertNull(Match.findClosestGpsRecord(Collections.emptyList(), 0, 2000));
    }

    @Test public void closestLookupScalesLogarithmically() {
        AtomicInteger probes = new AtomicInteger();
        List<Match.GpsPoint> records = new AbstractList<Match.GpsPoint>() {
            @Override public Match.GpsPoint get(int index) {
                probes.incrementAndGet();
                return point(index * 1000L);
            }
            @Override public int size() { return 1_000_000; }
        };
        assertEquals(765432000L, Match.findClosestGpsRecord(records, 765432100L, 2000).timeMillis);
        assertTrue("A million GPS points should need only a few probes", probes.get() < 40);
    }

    @Test public void timestampUsesStableDigitsAndTracksTimeZoneChanges() {
        assertEquals("2026-10-15T00:00:00.000Z", TimeUtils.formatTimestamp(1792022400000L));
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"));
        assertEquals("2026-10-15T08:00:00.000+08:00", TimeUtils.formatTimestamp(1792022400000L));
    }

    private List<Match.GpsPoint> read(String text, String date) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return Match.readGpsFile(file, date);
    }

    private static Match.GpsPoint point(long time) {
        return new Match.GpsPoint(time, "", 29, 106, 5);
    }
}

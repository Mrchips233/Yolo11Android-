package com.Chips.yolo11ncnn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real bundled NCNN model and Android bitmap access, rather than a mock JNI wrapper. */
@RunWith(AndroidJUnit4.class)
public final class NativeBitmapInstrumentedTest {
    private static YOLO11Ncnn detector;
    private static NativeRuntime.Session session;
    private static final CountDownLatch cleanupFinished = new CountDownLatch(1);

    @BeforeClass public static void loadRealCpuModel() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        detector = new YOLO11Ncnn();
        session = NativeRuntime.openSession(() -> {
            try {
                detector.closeCamera();
                detector.setOutputWindow(null);
            } finally {
                cleanupFinished.countDown();
            }
        });
        onNative(() -> {
            detector.closeCamera();
            detector.setOutputWindow(null);
            // Spinner model 1 is 640, backend 0 is CPU. Exercise packaged assets.
            assertTrue("Packaged TT100K45 model must load", detector.loadModel(target.getAssets(), 0, 1, 0));
            return null;
        });
    }

    @AfterClass public static void releaseCameraOwnership() throws InterruptedException {
        if (session != null) {
            session.close();
            assertTrue("Native cleanup must complete", cleanupFinished.await(90, TimeUnit.SECONDS));
        }
    }

    @Test public void opaqueArgbBlankReturnsEmptyArrayAndCanBeReused() throws Exception {
        onNative(() -> {
            // Odd dimensions also exercise Android bitmap row stride.
            Bitmap blank = Bitmap.createBitmap(319, 241, Bitmap.Config.ARGB_8888);
            try {
                blank.eraseColor(Color.rgb(114, 114, 114));
                for (int iteration = 0; iteration < 2; iteration++) {
                    float[] detections = detector.detectBitmap(blank);
                    assertValidDetections(detections, blank.getWidth(), blank.getHeight());
                    assertEquals("Uniform background must have no retained detections", 0, detections.length);
                    assertEquals("Inference must leave source pixels unchanged", Color.rgb(114, 114, 114),
                            blank.getPixel(101, 83));
                }
            } finally {
                blank.recycle();
            }
            return null;
        });
    }

    @Test public void rgb565IsRejectedWithAnExplicitException() throws Exception {
        onNative(() -> {
            Bitmap unsupported = Bitmap.createBitmap(32, 32, Bitmap.Config.RGB_565);
            try {
                expectInvalidBitmap(unsupported);
            } finally {
                unsupported.recycle();
            }
            return null;
        });
    }

    @Test public void recycledBitmapIsRejectedWithAnExplicitException() throws Exception {
        onNative(() -> {
            Bitmap recycled = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            recycled.recycle();
            expectInvalidBitmap(recycled);
            return null;
        });
    }

    @Test public void nullBitmapIsRejectedWithAnExplicitException() throws Exception {
        onNative(() -> {
            expectInvalidBitmap(null);
            return null;
        });
    }

    @Test public void validationFixtureMatchesIndependentPytorchCpuPrediction() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject oracle = new JSONObject(readAsset(testContext, "tt100k_val_10887_640.expected.json"));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        final Bitmap fixture;
        try (InputStream stream = testContext.getAssets().open(oracle.getString("fixture"))) {
            fixture = BitmapFactory.decodeStream(stream, null, options);
        }
        assertNotNull("Validation fixture must decode", fixture);
        assertEquals(Bitmap.Config.ARGB_8888, fixture.getConfig());
        assertEquals(oracle.getInt("input_width"), fixture.getWidth());
        assertEquals(oracle.getInt("input_height"), fixture.getHeight());
        try {
            float[] actual = onNative(() -> detector.detectBitmap(fixture));
            assertValidDetections(actual, fixture.getWidth(), fixture.getHeight());
            JSONArray expected = oracle.getJSONArray("expected");
            assertTrue("Fixture must exercise at least one positive prediction", expected.length() > 0);
            assertEquals("Retained detection count", expected.length() * 6, actual.length);
            JSONObject tolerance = oracle.getJSONObject("tolerance");
            double scoreTolerance = tolerance.getDouble("probability");
            double pixelTolerance = tolerance.getDouble("box_pixels");
            // This fixture has one sign; do not depend on the production
            // implementation's area-based ordering if more are added later.
            boolean[] matched = new boolean[actual.length / 6];
            for (int row = 0; row < expected.length(); row++) {
                JSONArray wanted = expected.getJSONArray(row);
                int closest = -1;
                double distance = Double.POSITIVE_INFINITY;
                for (int candidate = 0; candidate < matched.length; candidate++) {
                    int offset = candidate * 6;
                    if (matched[candidate] || (int) actual[offset] != wanted.getInt(0)) continue;
                    double candidateDistance = Math.abs(actual[offset + 2] - wanted.getDouble(2))
                            + Math.abs(actual[offset + 3] - wanted.getDouble(3));
                    if (candidateDistance < distance) {
                        distance = candidateDistance;
                        closest = candidate;
                    }
                }
                assertTrue("Expected class must be present: " + wanted.getInt(0), closest >= 0);
                matched[closest] = true;
                int offset = closest * 6;
                assertEquals("Probability", wanted.getDouble(1), actual[offset + 1], scoreTolerance);
                for (int component = 2; component < 6; component++) {
                    assertEquals("Box component " + component, wanted.getDouble(component),
                            actual[offset + component], pixelTolerance);
                }
            }
        } finally {
            fixture.recycle();
        }
    }

    private static void expectInvalidBitmap(Bitmap bitmap) {
        try {
            detector.detectBitmap(bitmap);
            fail("Invalid bitmap must fail explicitly rather than look like zero detections");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            assertNotNull("Failure should explain what went wrong", expected.getMessage());
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static void assertValidDetections(float[] values, int width, int height) {
        assertNotNull("Successful inference returns an array", values);
        assertEquals("Each output record has six floats", 0, values.length % 6);
        assertTrue("Deployment caps retained detections at 300", values.length <= 300 * 6);
        for (int offset = 0; offset < values.length; offset += 6) {
            for (int component = 0; component < 6; component++) {
                float value = values[offset + component];
                assertFalse("Output must be finite", Float.isNaN(value) || Float.isInfinite(value));
            }
            int label = (int) values[offset];
            assertEquals("Labels must be integer indices", label, values[offset], 0.0);
            assertTrue("Label must match the 45 packaged classes", label >= 0 && label < 45);
            assertTrue("Probability must be retained by deployment threshold", values[offset + 1] >= 0.465f);
            assertTrue("Probability must be at most one", values[offset + 1] <= 1.0f);
            assertTrue("Box origin is inside the bitmap", values[offset + 2] >= 0 && values[offset + 3] >= 0);
            assertTrue("Box dimensions are nonnegative", values[offset + 4] >= 0 && values[offset + 5] >= 0);
            assertTrue("Box right must be in bitmap pixels", values[offset + 2] + values[offset + 4] <= width + 0.01f);
            assertTrue("Box bottom must be in bitmap pixels", values[offset + 3] + values[offset + 5] <= height + 0.01f);
        }
    }

    private static String readAsset(Context context, String name) throws Exception {
        try (InputStream stream = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T onNative(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        session.submit(task);
        try {
            return task.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }
}

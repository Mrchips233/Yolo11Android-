package com.Chips.yolo11ncnn;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real visible SurfaceView + native model/camera lifecycle, without UI polling dependencies. */
@RunWith(AndroidJUnit4.class)
public class CameraLifecycleInstrumentedTest {
    private static final String TAG = "CameraLifecycleTest";
    private static final long UI_BUDGET_MS = 500;
    private static final long CONFIG_TIMEOUT_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long maximumUiMarkerMs;

    @Test(timeout = 240_000)
    public void rearCameraSwitchesCpu640Gpu640Cpu960WithoutBlockingMain() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        assertPermission(instrumentation, Manifest.permission.CAMERA);
        // YOLO requests these alongside CAMERA. Pregrant them to prevent a
        // location permission dialog pausing the Activity during camera checks.
        assertPermission(instrumentation, Manifest.permission.ACCESS_COARSE_LOCATION);
        assertPermission(instrumentation, Manifest.permission.ACCESS_FINE_LOCATION);

        Intent intent = new Intent(instrumentation.getTargetContext(), YOLO.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        YOLO activity = (YOLO) instrumentation.startActivitySync(intent);
        try {
            // Allow normal Activity/Surface startup before measuring backend
            // switches; startup includes the unrelated map SDK initialization.
            instrumentation.waitForIdleSync();
            selectConfiguration(activity, 1, 0, true);
            assertTrue(waitForConfiguration(activity, "640", "CPU", false));
            keepUiResponsiveFor(1_000);

            selectConfiguration(activity, 1, 1, false);
            boolean gpuLoaded = waitForConfiguration(activity, "640", "GPU", true);
            Log.i(TAG, "GPU640 available=" + gpuLoaded);
            keepUiResponsiveFor(1_000);

            selectConfiguration(activity, 0, 0, false);
            assertTrue(waitForConfiguration(activity, "960", "CPU", false));
            keepUiResponsiveFor(1_000);
            Log.i(TAG, "CPU640 -> GPU640 -> CPU960 completed; main marker max="
                    + maximumUiMarkerMs + "ms");
        } finally {
            finishAndAwaitCleanup(activity);
        }
    }

    private void assertPermission(Instrumentation instrumentation, String permission) {
        assertEquals("Pregrant " + permission + " to "
                        + instrumentation.getTargetContext().getPackageName()
                        + " before running this test",
                PackageManager.PERMISSION_GRANTED,
                instrumentation.getTargetContext().checkSelfPermission(permission));
    }

    private void selectConfiguration(YOLO activity, int model, int backend, boolean selectRear)
            throws Exception {
        onMainWithinBudget(() -> {
            ((Spinner) activity.findViewById(R.id.spinnerModel)).setSelection(model);
            ((Spinner) activity.findViewById(R.id.spinnerCPUGPU)).setSelection(backend);
            // A fresh YOLO Activity starts at facing=0 (front); exercise the
            // real switch button to select the rear camera rather than bypass JNI.
            if (selectRear) assertTrue(activity.findViewById(R.id.buttonSwitchCamera).performClick());
        });
    }

    private boolean waitForConfiguration(YOLO activity, String input, String backend,
                                         boolean allowUnavailableBackend) throws Exception {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + CONFIG_TIMEOUT_MS;
        String lastStatus = "";
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<String> status = new AtomicReference<>();
            AtomicBoolean validSurface = new AtomicBoolean();
            // A marker is checked throughout the load, not only before it.
            // This also catches OnItemSelected work dispatched on a later layout.
            onMainWithinBudget(() -> {
                status.set(((TextView) activity.findViewById(R.id.runtimeStatus)).getText().toString());
                SurfaceView preview = activity.findViewById(R.id.cameraview);
                validSurface.set(preview.isShown() && preview.getHolder().getSurface().isValid());
            });
            lastStatus = status.get();
            if (lastStatus.contains("检测中") && lastStatus.contains(input)
                    && lastStatus.contains(backend) && validSurface.get()) {
                Log.i(TAG, "Ready: " + lastStatus);
                return true;
            }
            boolean backendFailure = lastStatus.contains("模型加载失败")
                    || lastStatus.contains("运行环境初始化失败");
            // Spinner selection notifications can be delivered on the next
            // layout. Do not treat the previous configuration's error label as
            // the result of this selection before that layout has had a chance.
            boolean settled = SystemClock.elapsedRealtime() - started >= UI_BUDGET_MS;
            if (allowUnavailableBackend && backendFailure && settled) {
                Log.w(TAG, "Optional backend unavailable: " + lastStatus);
                return false;
            }
            if (lastStatus.contains("失败") && settled) fail("Configuration failed: " + lastStatus);
            SystemClock.sleep(50);
        }
        fail("Timed out after 30s waiting for " + input + "/" + backend + "; status=" + lastStatus);
        return false;
    }

    private void keepUiResponsiveFor(long durationMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + durationMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            onMainWithinBudget(() -> { });
            SystemClock.sleep(50);
        }
    }

    private void onMainWithinBudget(Runnable action) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        long started = SystemClock.elapsedRealtime();
        main.post(() -> {
            try { action.run(); }
            catch (Throwable failure) { error.set(failure); }
            finally { complete.countDown(); }
        });
        assertTrue("Main thread did not respond within " + UI_BUDGET_MS + "ms",
                complete.await(UI_BUDGET_MS, TimeUnit.MILLISECONDS));
        maximumUiMarkerMs = Math.max(maximumUiMarkerMs, SystemClock.elapsedRealtime() - started);
        if (error.get() != null) throw new AssertionError("Main-thread test action failed", error.get());
    }

    private void finishAndAwaitCleanup(YOLO activity) throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        main.post(() -> {
            activity.finish();
            requested.countDown();
        });
        assertTrue("Activity could not finish", requested.await(CONFIG_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        long deadline = SystemClock.elapsedRealtime() + CONFIG_TIMEOUT_MS;
        AtomicBoolean destroyed = new AtomicBoolean();
        while (!destroyed.get() && SystemClock.elapsedRealtime() < deadline) {
            onMainWithinBudget(() -> destroyed.set(activity.isDestroyed()));
            if (!destroyed.get()) SystemClock.sleep(50);
        }
        assertTrue("Activity onDestroy was not delivered", destroyed.get());

        // This barrier follows the Activity's queued native cleanup. It also
        // performs ownership handoff if a delayed old lifecycle task exists.
        CountDownLatch nativeIdle = new CountDownLatch(1);
        NativeRuntime.Session barrier = NativeRuntime.openSession(() -> { });
        try {
            barrier.submit(nativeIdle::countDown);
            assertTrue("Native camera cleanup did not finish within 30s",
                    nativeIdle.await(CONFIG_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            barrier.close();
        }
    }
}

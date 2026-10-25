package com.Chips.yolo11ncnn;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class NativeRuntimeTest {
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable command) { work.add(command); }
        void drain() { while (!work.isEmpty()) work.remove().run(); }
    }

    @Test public void newerOwnerSkipsStaleQueuedWorkAndCleansBeforeHandoff() {
        ManualExecutor executor = new ManualExecutor();
        NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
        List<String> events = new ArrayList<>();
        NativeRuntime.Session old = coordinator.openSession(() -> events.add("old cleanup"));
        old.submit(() -> events.add("old work"));
        executor.drain();
        old.submit(() -> events.add("stale work"));
        NativeRuntime.Session next = coordinator.openSession(() -> events.add("new cleanup"));
        next.submit(() -> events.add("new work"));
        assertTrue(old.isCancelled());
        assertFalse(old.isCurrent());
        executor.drain();
        assertEquals(Arrays.asList("old work", "old cleanup", "new work"), events);
    }

    @Test public void lateOldDestroyCannotCleanNewOwner() {
        ManualExecutor executor = new ManualExecutor();
        NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
        List<String> events = new ArrayList<>();
        NativeRuntime.Session old = coordinator.openSession(() -> events.add("old cleanup"));
        executor.drain();
        NativeRuntime.Session next = coordinator.openSession(() -> events.add("new cleanup"));
        executor.drain();
        old.close();
        next.submit(() -> events.add("new work"));
        executor.drain();
        assertEquals(Arrays.asList("old cleanup", "new work"), events);
        next.close(); next.close();
        executor.drain();
        assertEquals(Arrays.asList("old cleanup", "new work", "new cleanup"), events);
    }

    @Test public void closeCancelsImmediatelyAndQueuedTasksNeverReopen() {
        ManualExecutor executor = new ManualExecutor();
        NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
        List<String> events = new ArrayList<>();
        NativeRuntime.Session session = coordinator.openSession(() -> events.add("cleanup"));
        executor.drain();
        session.submit(() -> events.add("open"));
        session.close();
        assertTrue(session.isCancelled());
        session.submit(() -> events.add("late metadata"));
        executor.drain();
        assertEquals(Arrays.asList("cleanup"), events);
    }

    @Test public void supersededBeforeActivationDoesNotCleanUnownedNativeState() {
        ManualExecutor executor = new ManualExecutor();
        NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
        List<String> events = new ArrayList<>();
        NativeRuntime.Session first = coordinator.openSession(() -> events.add("unowned cleanup"));
        first.submit(() -> events.add("stale open"));
        first.close();
        NativeRuntime.Session next = coordinator.openSession(() -> events.add("new cleanup"));
        next.submit(() -> events.add("new work"));
        executor.drain();
        assertEquals(Arrays.asList("new work"), events);
    }

    @Test public void cancelledHandoffStillCleansThePreviouslyRunningCamera() {
        ManualExecutor executor = new ManualExecutor();
        NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
        List<String> events = new ArrayList<>();
        coordinator.openSession(() -> events.add("old cleanup"));
        executor.drain();
        NativeRuntime.Session next = coordinator.openSession(() -> events.add("new cleanup"));
        next.close();
        executor.drain();
        assertEquals(Arrays.asList("old cleanup"), events);
    }

    @Test public void cancellationIsImmediateWhileNativeWorkAndCleanupStaySerialized() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishWork = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicBoolean workFinished = new AtomicBoolean();
        AtomicBoolean cleanupWasSerialized = new AtomicBoolean();
        try {
            NativeRuntime.Coordinator coordinator = new NativeRuntime.Coordinator(executor);
            NativeRuntime.Session session = coordinator.openSession(() -> {
                cleanupWasSerialized.set(workFinished.get());
                cleaned.countDown();
            });
            session.submit(() -> {
                started.countDown();
                try { finishWork.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                workFinished.set(true);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            session.close();
            assertTrue(session.isCancelled());
            assertFalse(workFinished.get());
            assertEquals(1, cleaned.getCount());
            finishWork.countDown();
            assertTrue(cleaned.await(2, TimeUnit.SECONDS));
            assertTrue(cleanupWasSerialized.get());
        } finally {
            finishWork.countDown();
            executor.shutdownNow();
        }
    }
}

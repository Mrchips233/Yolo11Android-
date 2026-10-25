package com.Chips.yolo11ncnn;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes every user of the process-global native camera/model/CSV state. */
public final class NativeRuntime {
    private static final Coordinator PROCESS = new Coordinator(
            Executors.newSingleThreadExecutor(r -> new Thread(r, "yolo-native-control")));

    private NativeRuntime() { }

    public static Session openSession(Runnable cleanup) {
        return PROCESS.openSession(cleanup);
    }

    public static final class Session implements AutoCloseable {
        private final Coordinator coordinator;
        private final Runnable cleanup;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Session(Coordinator coordinator, Runnable cleanup) {
            this.coordinator = coordinator;
            this.cleanup = cleanup;
        }

        public boolean isCurrent() {
            return !cancelled.get() && coordinator.current == this;
        }

        /** Immediately visible even while a long video job owns the executor. */
        public boolean isCancelled() { return cancelled.get(); }

        public void submit(Runnable operation) {
            coordinator.submit(this, operation);
        }

        /** Never blocks the caller or joins native work on the main thread. */
        @Override public void close() { coordinator.close(this); }
    }

    // An injectable executor makes handoff/late-destroy races deterministic in
    // plain JVM tests, without loading Android classes or the JNI library.
    static final class Coordinator {
        private final Executor executor;
        private volatile Session current;
        // Access only from the serial executor; the UI owns current/cancelled.
        private Session nativeOwner;

        Coordinator(Executor executor) { this.executor = executor; }

        synchronized Session openSession(Runnable cleanup) {
            if (current != null) current.cancelled.set(true);
            Session next = new Session(this, cleanup);
            current = next;
            executor.execute(() -> {
                if (nativeOwner != null) {
                    Session previous = nativeOwner;
                    nativeOwner = null;
                    previous.cleanup.run();
                }
                if (next.isCurrent()) nativeOwner = next;
            });
            return next;
        }

        synchronized void submit(Session session, Runnable operation) {
            if (!session.isCurrent()) return;
            executor.execute(() -> {
                if (session.isCurrent() && nativeOwner == session) operation.run();
            });
        }

        synchronized void close(Session session) {
            if (!session.cancelled.compareAndSet(false, true)) return;
            if (current == session) current = null;
            executor.execute(() -> {
                // A late onDestroy from an old Activity cannot clear the new
                // Activity's window, metadata or camera.
                if (nativeOwner == session) {
                    nativeOwner = null;
                    session.cleanup.run();
                }
            });
        }
    }
}

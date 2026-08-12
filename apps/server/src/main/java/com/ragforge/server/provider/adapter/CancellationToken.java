package com.ragforge.server.provider.adapter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancellationRequested() {
        return cancelled.get();
    }

    public void onCancel(Runnable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Cancellation callback is required");
        }
        if (cancelled.get()) {
            runCallback(callback);
            return;
        }
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            runCallback(callback);
        }
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        callbacks.forEach(this::runCallback);
        callbacks.clear();
        return true;
    }

    private void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Cancellation must notify all listeners even if one listener fails.
        }
    }
}

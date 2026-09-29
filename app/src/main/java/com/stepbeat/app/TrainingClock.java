package com.stepbeat.app;

/** Active training time, independent of beat count, wall clock and UI refreshes. */
final class TrainingClock {
    private long accumulated, startedAt;
    private boolean running;

    void restore(long elapsed) { accumulated = Math.max(0, elapsed); running = false; }
    void resume(long now) { if (!running) { startedAt = now; running = true; } }
    void pause(long now) { accumulated = elapsedAt(now); running = false; }
    long elapsedAt(long now) { return accumulated + (running ? Math.max(0, now - startedAt) : 0); }
    long accumulated() { return accumulated; }
    long startedAt() { return startedAt; }
    boolean expired(int minutes, long now) { return minutes > 0 && elapsedAt(now) >= minutes * 60_000L; }
}

package com.stepbeat.app;

/** Monotonic deadlines: delayed callbacks skip missed beats rather than burst. */
public final class BeatClock {
    public static final int MIN_BPM = 40;
    public static final int MAX_BPM = 220;
    private long nextNanos;
    private long intervalNanos;

    public static int clamp(int bpm) { return Math.max(MIN_BPM, Math.min(MAX_BPM, bpm)); }
    public void reset(long now, int bpm) {
        intervalNanos = 60_000_000_000L / clamp(bpm);
        nextNanos = now;
    }
    public long advance(long now) {
        nextNanos += intervalNanos;
        if (nextNanos <= now) nextNanos += ((now - nextNanos) / intervalNanos + 1) * intervalNanos;
        return nextNanos;
    }
    public long deadline() { return nextNanos; }
}

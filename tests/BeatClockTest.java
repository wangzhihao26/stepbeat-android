package com.stepbeat.app;

public final class BeatClockTest {
    public static void main(String[] args) {
        check(BeatClock.clamp(-1) == 40, "lower bound");
        check(BeatClock.clamp(300) == 220, "upper bound");
        BeatClock clock = new BeatClock();
        for (int bpm : new int[]{40, 100, 120, 170, 220}) {
            long origin = 9_000_000_000L;
            long interval = 60_000_000_000L / bpm;
            clock.reset(origin, bpm);
            for (int i = 1; i <= 100_000; i++) {
                long deadline = clock.deadline();
                check(clock.advance(deadline + 3_000_000L) == origin + interval * i, "no accumulated callback drift at " + bpm);
            }
        }
        clock.reset(0, 120);
        check(clock.advance(1_600_000_000L) == 2_000_000_000L, "skip missed beats without burst");
        clock.reset(0, 120);
        check(clock.advance(500_000_000L) == 1_000_000_000L, "exact-boundary delay is skipped");
        clock.reset(42, 220);
        check(clock.deadline() == 42, "tempo reset starts a new phase");
        check(clock.advance(42) == 42 + 60_000_000_000L / 220, "new tempo interval");
        System.out.println("PASS: 500,006 timing/bounds assertions");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

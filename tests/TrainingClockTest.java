package com.stepbeat.app;

public final class TrainingClockTest {
    public static void main(String[] args) {
        TrainingClock c = new TrainingClock();
        c.resume(1000);
        check(c.elapsedAt(1_501_000) == 1_500_000, "25 minutes with no beat or UI callbacks");
        check(!c.expired(0, 1_501_000), "unlimited cannot expire after 25 minutes");
        check(!c.expired(0, 86_401_000), "unlimited cannot expire after 24 hours");
        c.pause(1_501_000);
        check(c.elapsedAt(1_801_000) == 1_500_000, "transient audio loss freezes training time");
        c.pause(1_901_000);
        check(c.elapsedAt(1_901_000) == 1_500_000, "manual pause while waiting does not add time");
        c.resume(1_901_000);
        c.resume(1_951_000);
        check(c.elapsedAt(2_001_000) == 1_600_000, "focus gain resumes once without dropping elapsed time");
        check(!c.expired(30, 2_200_999), "timer remains active just before deadline");
        check(c.expired(30, 2_201_000), "timer expires at deadline excluding interruptions");
        check(c.expired(10, 2_001_000), "shorter timer applies to current session");
        check(!c.expired(0, 2_001_000), "switching to unlimited removes time limit");
        c.restore(0);
        c.resume(3_000_000);
        check(c.elapsedAt(3_001_000) == 1000, "new session has no previous elapsed time");
        for (int i = 0; i < 1000; i++) {
            c.restore(0);
            c.resume(100);
            c.pause(1100);
            c.resume(5000);
            c.pause(5500);
            check(c.elapsedAt(10000) == 1500, "only active intervals accumulate");
        }
        System.out.println("PASS: unlimited, timer boundaries and interruption duration checks");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

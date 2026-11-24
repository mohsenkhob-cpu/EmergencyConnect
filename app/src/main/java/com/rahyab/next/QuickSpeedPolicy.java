package com.rahyab.next;

/** Decide when measured slowness warrants changing a working automatic connection. */
final class QuickSpeedPolicy {
    enum Decision { ACCEPT, RECHECK, SWITCH }

    static final long MIN_DOWNLOAD_KBPS = 1000L;
    private static final long COOLDOWN_MS = 15 * 60_000L;

    private String bestEngine;
    private long bestSpeed;
    private long firstSlowSpeed;
    private int missingSamples;
    private long pauseUntil;

    boolean comparing() { return bestEngine != null; }
    String bestEngine() { return bestEngine; }
    boolean canMeasure(long now) { return now >= pauseUntil; }

    Decision measure(String engine, long speed, long now) {
        if (!canMeasure(now)) return Decision.ACCEPT;
        if (speed <= 0) {
            firstSlowSpeed = 0;
            return comparing() && ++missingSamples == 1 ? Decision.RECHECK
                    : comparing() ? Decision.SWITCH : Decision.ACCEPT;
        }
        missingSamples = 0;
        if (speed >= MIN_DOWNLOAD_KBPS) {
            reset();
            return Decision.ACCEPT;
        }
        if (firstSlowSpeed == 0) {
            firstSlowSpeed = speed;
            return Decision.RECHECK;
        }
        long mean = (firstSlowSpeed + speed) / 2;
        firstSlowSpeed = 0;
        if (bestEngine == null || mean > bestSpeed) {
            bestEngine = engine;
            bestSpeed = mean;
        }
        return Decision.SWITCH;
    }

    void engineChanged() {
        firstSlowSpeed = 0L;
        missingSamples = 0;
    }

    String restoreBest(long now) {
        String engine = bestEngine;
        reset();
        pauseUntil = now + COOLDOWN_MS;
        return engine;
    }

    void reset() {
        engineChanged();
        bestEngine = null;
        bestSpeed = 0L;
        pauseUntil = 0L;
    }
}

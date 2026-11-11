package com.exenova.link.watchdog;

/**
 * Server health as judged by the watchdog. Pure value type with the threshold
 * logic, kept separate from scheduling and Discord so it can be unit-tested.
 */
public enum HealthLevel {
    OK, WARNING, CRITICAL;

    /**
     * Evaluates health from the worst of TPS, RAM% and CPU%. A metric at or past
     * its critical threshold is CRITICAL; at or past its warning threshold is
     * WARNING; otherwise OK. CPU is ignored when {@code cpuPercent < 0} (unknown).
     */
    public static HealthLevel evaluate(double tps, int ramPercent, int cpuPercent, Thresholds t) {
        HealthLevel level = OK;
        if (tps <= t.tpsCritical()) level = CRITICAL;
        else if (tps <= t.tpsWarn()) level = WARNING;

        level = worst(level, over(ramPercent, t.ramWarn(), t.ramCritical()));
        if (cpuPercent >= 0) level = worst(level, over(cpuPercent, t.cpuWarn(), t.cpuCritical()));
        return level;
    }

    private static HealthLevel over(int value, int warn, int critical) {
        if (value >= critical) return CRITICAL;
        if (value >= warn) return WARNING;
        return OK;
    }

    private static HealthLevel worst(HealthLevel a, HealthLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Watchdog thresholds. TPS thresholds are lower-is-worse; RAM/CPU are higher-is-worse. */
    public record Thresholds(double tpsWarn, double tpsCritical, int ramWarn, int ramCritical, int cpuWarn, int cpuCritical) {
        public static Thresholds defaults() { return new Thresholds(18.0, 15.0, 85, 95, 85, 95); }
    }
}

package eu.berdosi.app.heartbeat;

/** One logged reading. The raw sample dump is not held here; see {@link ReadingDb#details}. */
final class ReadingEntry {
    /** A reading this close to the previous one continues the same activity period. */
    static final long CARRY_OVER_MS = 15 * 60 * 1000L;

    /**
     * What the duration slider offers, in minutes: roughly logarithmic, so short periods can be
     * told apart and long ones don't need many taps.
     */
    static final int[] DURATION_STEPS_MIN = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, 40, 50, 60, 90, 120, 180, 240};

    /** Index into {@link #DURATION_STEPS_MIN} of the step closest to {@code minutes}; the lower one on a tie. */
    static int nearestDurationStep(int minutes) {
        int best = 0;
        for (int i = 1; i < DURATION_STEPS_MIN.length; i++) {
            if (Math.abs(DURATION_STEPS_MIN[i] - minutes) < Math.abs(DURATION_STEPS_MIN[best] - minutes)) {
                best = i;
            }
        }
        return best;
    }

    /** 0 until the entry is inserted. */
    long id;
    /** When the reading was started, epoch milliseconds. */
    final long timestamp;
    final float bpm;
    final int cycles;
    final float seconds;
    /** Unit gravity vector in phone coordinates at the start of the reading; null without a sensor. */
    final float[] gravity;

    /** Null when not recorded. */
    ActivityLevel activity;
    /** The activity was filled in by the orientation detector and the user never touched it. */
    boolean activityAuto;
    /** Minutes spent in {@link #activity}; null when not recorded. */
    Integer durationMin;
    /** The duration was computed, not typed in, so it follows changes of the activity. */
    boolean durationAuto = true;
    /** Marked invalid by the user: finger slipped, bad signal, ... See {@link #isInvalid()}. */
    boolean invalid;

    /** Excluded from interpretation: marked invalid, or a test reading. */
    boolean isInvalid() {
        return invalid || activity == ActivityLevel.TESTING;
    }

    ReadingEntry(long timestamp, float bpm, int cycles, float seconds, float[] gravity) {
        this.timestamp = timestamp;
        this.bpm = bpm;
        this.cycles = cycles;
        this.seconds = seconds;
        this.gravity = gravity;
    }

    /**
     * Default duration of a reading at {@code timestamp} done in {@code level}: if the previous
     * reading was in the same activity within {@link #CARRY_OVER_MS}, its duration plus the time
     * since it; otherwise a fresh period, 0 minutes. Null when there is no activity.
     */
    static Integer suggestDuration(ReadingEntry previous, ActivityLevel level, long timestamp) {
        if (level == null) return null;
        if (previous == null || previous.activity != level) return 0;
        long gap = timestamp - previous.timestamp;
        if (gap < 0 || gap > CARRY_OVER_MS) return 0;
        int base = previous.durationMin == null ? 0 : previous.durationMin;
        return base + (int) Math.round(gap / 60000.0);
    }
}

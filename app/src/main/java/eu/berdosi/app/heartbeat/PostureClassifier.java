package eu.berdosi.app.heartbeat;

import java.util.List;

/**
 * Guesses the activity from the accelerometer.
 *
 * Walking is the only thing physics can tell directly: the acceleration shakes. Lying, sitting and
 * standing cannot be told apart from a phone's tilt in general, because the tilt depends on how the
 * phone is held. But a person tends to hold it the same way in the same posture, so the guess is
 * learned from the user's own earlier readings: the closest past tilts, restricted to the ones the
 * user labelled themselves, vote. No confident vote, no guess.
 */
final class PostureClassifier {
    /** Std. deviation of |acceleration| above which the phone is being walked with; resting in a hand is below ~0.3. */
    static final float WALKING_MOTION = 1.2f;
    /** Past readings tilted further than this from the current one don't vote. */
    static final double RADIUS_DEGREES = 25;
    static final int MAX_VOTERS = 5;
    static final int MIN_VOTERS = 3;
    /** Share of the votes the winning activity needs. */
    static final double MIN_AGREEMENT = 0.8;
    /** Only the most recent labelled readings are considered, so habits can drift. */
    static final int MAX_HISTORY = 200;

    private static final ActivityLevel[] LEARNED = {
            ActivityLevel.LYING, ActivityLevel.SITTING, ActivityLevel.STANDING};

    private PostureClassifier() {
    }

    /**
     * @param history entries newest first
     * @return the suggested activity, or null when there is no confident one
     */
    static ActivityLevel suggest(Posture posture, List<ReadingEntry> history) {
        if (posture == null) return null;
        if (posture.motion >= WALKING_MOTION) return ActivityLevel.WALKING;

        double[] distances = new double[MAX_HISTORY];
        ActivityLevel[] labels = new ActivityLevel[MAX_HISTORY];
        int considered = 0;
        int candidates = 0;
        for (ReadingEntry entry : history) {
            if (entry.gravity == null || entry.activityAuto || !isLearned(entry.activity)) continue;
            if (considered++ == MAX_HISTORY) break;
            double distance = angleDegrees(posture, entry.gravity);
            if (distance > RADIUS_DEGREES) continue;
            labels[candidates] = entry.activity;
            distances[candidates++] = distance;
        }

        // the MAX_VOTERS nearest voters
        int[] votes = new int[ActivityLevel.values().length];
        int voters = 0;
        boolean[] used = new boolean[candidates];
        while (voters < MAX_VOTERS) {
            int nearest = -1;
            for (int i = 0; i < candidates; i++) {
                if (used[i] || labels[i] == null) continue;
                if (nearest < 0 || distances[i] < distances[nearest]) nearest = i;
            }
            if (nearest < 0) break;
            used[nearest] = true;
            votes[labels[nearest].ordinal()]++;
            voters++;
        }
        if (voters < MIN_VOTERS) return null;

        ActivityLevel winner = null;
        for (ActivityLevel level : LEARNED) {
            if (winner == null || votes[level.ordinal()] > votes[winner.ordinal()]) winner = level;
        }
        return votes[winner.ordinal()] >= MIN_AGREEMENT * voters ? winner : null;
    }

    private static boolean isLearned(ActivityLevel level) {
        for (ActivityLevel learned : LEARNED) {
            if (learned == level) return true;
        }
        return false;
    }

    private static double angleDegrees(Posture posture, float[] gravity) {
        double dot = posture.gx * gravity[0] + posture.gy * gravity[1] + posture.gz * gravity[2];
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }
}

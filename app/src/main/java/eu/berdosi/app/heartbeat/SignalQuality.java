package eu.berdosi.app.heartbeat;

import java.util.Arrays;
import java.util.List;

/**
 * Judges a finished reading from the beats detected in it.
 *
 * The reported pulse is the number of beats over the time they span, i.e. it follows the mean
 * beat interval. A steady heartbeat has a typical (median) interval close to that mean. Missed
 * and spurious beats pull the mean away from the median, and then the reported pulse no longer
 * matches the beats in the picture. A missed beat here and a doubled one there cancel out and
 * the pulse is still right, so those are not held against the reading.
 *
 * The detector also reports the dominant rhythm of the whole signal. Beats that disagree with it,
 * or a signal with no clear rhythm at all (finger movement, a loose grip), are not trusted either.
 */
final class SignalQuality {
    enum Verdict {
        OK,
        /** Not enough beats found to tell a pulse. */
        TOO_FEW_BEATS,
        /** The beats stray from each other or from the signal's rhythm, a long dropout occurred, or there is no clear rhythm. */
        IRREGULAR
    }

    static final int MIN_BEATS = 6;
    /** Largest relative difference between the pulse from the median interval and the reported pulse. */
    static final double MAX_RATE_MISMATCH = 0.10;
    /** A single gap this many times the median interval is a dropout, however clean the rest. */
    static final double DROPOUT_INTERVAL = 2.5;
    /**
     * Least share of the signal's power that must lie in its dominant rhythm. Recorded readings with
     * a visible pulse had 0.50 or more; the ones dominated by movement had 0.32 or less.
     */
    static final double MIN_PERIODICITY = 0.40;
    /** Largest relative difference between the beats' pulse and the signal's dominant rhythm. */
    static final double MAX_SPECTRUM_MISMATCH = 0.10;

    private SignalQuality() {
    }

    /** Judges a reading from everything the detector found, including the signal's own rhythm. */
    static Verdict assess(BeatDetector.Result result) {
        if (result.beatTimes.length < MIN_BEATS) return Verdict.TOO_FEW_BEATS;
        if (result.periodicity < MIN_PERIODICITY) return Verdict.IRREGULAR;

        double span = result.beatTimes[result.beatTimes.length - 1] - result.beatTimes[0];
        double beatBpm = 60000.0 * (result.beatTimes.length - 1) / span;
        if (Math.abs(1 - beatBpm / result.spectrumBpm) > MAX_SPECTRUM_MISMATCH) return Verdict.IRREGULAR;

        return assess(result.beatList());
    }

    /** @param beatTimes timestamps of the detected beats, milliseconds, in any order */
    static Verdict assess(List<Long> beatTimes) {
        if (beatTimes.size() < MIN_BEATS) return Verdict.TOO_FEW_BEATS;

        long[] times = new long[beatTimes.size()];
        for (int i = 0; i < times.length; i++) times[i] = beatTimes.get(i);
        Arrays.sort(times);

        double[] intervals = new double[times.length - 1];
        for (int i = 0; i < intervals.length; i++) intervals[i] = times[i + 1] - times[i];
        double mean = (double) (times[times.length - 1] - times[0]) / intervals.length;

        double[] sorted = intervals.clone();
        Arrays.sort(sorted);
        double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        if (median <= 0) return Verdict.IRREGULAR;

        if (sorted[sorted.length - 1] >= DROPOUT_INTERVAL * median) return Verdict.IRREGULAR;
        // pulse from the median interval = 1 / median, reported pulse = 1 / mean
        return Math.abs(1 - median / mean) > MAX_RATE_MISMATCH ? Verdict.IRREGULAR : Verdict.OK;
    }
}

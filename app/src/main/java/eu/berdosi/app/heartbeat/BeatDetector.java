package eu.berdosi.app.heartbeat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds the heartbeats in a series of camera brightness samples.
 *
 * The finger's brightness drifts far more than it pulses (pressure, exposure, a shifting grip), so
 * the pulse is never read off the raw level:
 * <ol>
 *   <li>the samples are laid on an even time grid, because frames do not arrive evenly;</li>
 *   <li>the slow drift is subtracted (a one second moving average) and the rest is lightly smoothed;</li>
 *   <li>the dominant rhythm is looked up in the spectrum. It is robust where single beats are not
 *       (a weak or doubled beat does not move it), and it sets how close two beats can be;</li>
 *   <li>valleys (the red level dips with each systole) are the beats. Valleys closer than about half
 *       the rhythm's period are one beat (a dicrotic notch), the stronger one is kept.</li>
 * </ol>
 * The spectrum is also reported, with how much of the signal's power sits in that rhythm, so
 * {@link SignalQuality} can tell a reading with a pulse in it from one that is mostly movement.
 *
 * Pure and stateless, so it can be tested with recorded readings.
 */
final class BeatDetector {
    static final double GRID_HZ = 50;
    /** Moving average that is subtracted as drift. Longer than a beat, shorter than a breath. */
    static final double TREND_S = 1.0;
    static final double SMOOTH_S = 0.08;
    /** Below this much signal the spectrum says nothing; no beats are returned. */
    static final double MIN_SPAN_S = 4.0;
    static final double MIN_BPM = 40;
    static final double MAX_BPM = 200;
    /** The spectrum is scanned this wide, so the share of power in the pulse has a fair denominator. */
    private static final double SCAN_MIN_BPM = 20;
    private static final double SCAN_MAX_BPM = 300;
    private static final double SCAN_STEP_BPM = 0.5;
    /** Power within this fraction either side of the peak counts as the pulse. */
    private static final double PEAK_WIDTH = 0.10;
    /** No two valleys closer than this are ever both beats (300 bpm would be 0.2 s). */
    private static final double MIN_GAP_S = 0.25;
    /** A valley shallower than this fraction of the median valley is noise. */
    private static final double PROMINENCE_FLOOR = 0.2;
    /** Valleys closer than this fraction of the rhythm's period are one beat. */
    private static final double REFRACTORY = 0.55;

    static final class Result {
        /** Milliseconds, ascending; empty if there was too little signal. */
        final long[] beatTimes;
        /** The strongest rhythm between {@link #MIN_BPM} and {@link #MAX_BPM}; NaN if none could be tried. */
        final double spectrumBpm;
        /** Share (0-1) of the signal's power that lies in {@link #spectrumBpm}; NaN if none could be tried. */
        final double periodicity;

        Result(long[] beatTimes, double spectrumBpm, double periodicity) {
            this.beatTimes = beatTimes;
            this.spectrumBpm = spectrumBpm;
            this.periodicity = periodicity;
        }

        List<Long> beatList() {
            List<Long> list = new ArrayList<>(beatTimes.length);
            for (long time : beatTimes) list.add(time);
            return list;
        }
    }

    private BeatDetector() {
    }

    /**
     * @param timesMs ascending sample times, milliseconds
     * @param values  sample brightness, any scale
     */
    static Result analyse(long[] timesMs, double[] values) {
        int count = timesMs.length;
        if (count < 2 || (timesMs[count - 1] - timesMs[0]) / 1000.0 < MIN_SPAN_S) {
            return new Result(new long[0], Double.NaN, Double.NaN);
        }

        double step = 1000.0 / GRID_HZ;
        int n = (int) Math.ceil((timesMs[count - 1] - timesMs[0]) / step);
        double[] grid = resample(timesMs, values, n, step);

        double[] detrended = new double[n];
        double[] trend = movingAverage(grid, GRID_HZ * TREND_S);
        for (int i = 0; i < n; i++) detrended[i] = grid[i] - trend[i];

        double[] spectrum = spectrum(detrended);
        double bestPower = -1;
        int best = -1;
        double total = 0;
        for (int k = 0; k < spectrum.length; k++) {
            total += spectrum[k];
            double bpm = SCAN_MIN_BPM + k * SCAN_STEP_BPM;
            if (bpm >= MIN_BPM && bpm <= MAX_BPM && spectrum[k] > bestPower) {
                bestPower = spectrum[k];
                best = k;
            }
        }
        double peakBpm = SCAN_MIN_BPM + best * SCAN_STEP_BPM;
        double inPeak = 0;
        for (int k = 0; k < spectrum.length; k++) {
            double bpm = SCAN_MIN_BPM + k * SCAN_STEP_BPM;
            if (Math.abs(bpm - peakBpm) <= PEAK_WIDTH * peakBpm) inPeak += spectrum[k];
        }
        double periodicity = total > 0 ? inPeak / total : 0;

        double[] smooth = movingAverage(detrended, GRID_HZ * SMOOTH_S);
        int[] valleys = pickValleys(smooth, 60000.0 / peakBpm / step);

        long[] beats = new long[valleys.length];
        for (int i = 0; i < beats.length; i++) beats[i] = timesMs[0] + Math.round(valleys[i] * step);
        return new Result(beats, peakBpm, periodicity);
    }

    /** Linear interpolation of the samples at timesMs[0] + i * step. */
    private static double[] resample(long[] times, double[] values, int n, double step) {
        double[] grid = new double[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double t = times[0] + i * step;
            while (j < times.length - 2 && times[j + 1] <= t) j++;
            long t0 = times[j];
            long t1 = times[j + 1];
            double fraction = t1 == t0 ? 0 : Math.min(1, Math.max(0, (t - t0) / (t1 - t0)));
            grid[i] = values[j] + (values[j + 1] - values[j]) * fraction;
        }
        return grid;
    }

    /** Centred moving average over about {@code width} samples; the ends repeat their edge value. */
    static double[] movingAverage(double[] x, double width) {
        int window = (int) Math.round(width);
        if (window % 2 == 0) window++;
        int half = window / 2;
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double sum = 0;
            for (int k = -half; k <= half; k++) {
                sum += x[Math.min(x.length - 1, Math.max(0, i + k))];
            }
            out[i] = sum / window;
        }
        return out;
    }

    /** Power at SCAN_MIN_BPM, SCAN_MIN_BPM + SCAN_STEP_BPM, ... of the mean-free, Hann-windowed signal. */
    private static double[] spectrum(double[] x) {
        int n = x.length;
        double mean = 0;
        for (double v : x) mean += v;
        mean /= n;
        double[] windowed = new double[n];
        for (int i = 0; i < n; i++) {
            windowed[i] = (x[i] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        }

        int bins = (int) Math.round((SCAN_MAX_BPM - SCAN_MIN_BPM) / SCAN_STEP_BPM) + 1;
        double[] power = new double[bins];
        for (int k = 0; k < bins; k++) {
            double omega = 2 * Math.PI * (SCAN_MIN_BPM + k * SCAN_STEP_BPM) / 60.0 / GRID_HZ;
            double stepCos = Math.cos(omega);
            double stepSin = Math.sin(omega);
            // rotate (cos, sin) by omega per sample instead of calling cos/sin for every sample
            double cos = 1, sin = 0, re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                re += windowed[i] * cos;
                im -= windowed[i] * sin;
                double nextCos = cos * stepCos - sin * stepSin;
                sin = sin * stepCos + cos * stepSin;
                cos = nextCos;
            }
            power[k] = re * re + im * im;
        }
        return power;
    }

    /** Grid indices of the beats: the valleys of y, ascending. */
    private static int[] pickValleys(double[] y, double periodSamples) {
        List<Integer> minima = new ArrayList<>();
        for (int i = 1; i < y.length - 1; i++) {
            if (y[i] < y[i - 1] && y[i] <= y[i + 1]) minima.add(i);
        }

        // keep the deepest of any valleys closer than MIN_GAP_S
        final double[] depth = y;
        List<Integer> byDepth = new ArrayList<>(minima);
        Collections.sort(byDepth, (a, b) -> Double.compare(depth[a], depth[b]));
        int minGap = (int) Math.max(1, Math.round(GRID_HZ * MIN_GAP_S));
        boolean[] dropped = new boolean[y.length];
        List<Integer> spaced = new ArrayList<>();
        for (int candidate : byDepth) {
            if (dropped[candidate]) continue;
            spaced.add(candidate);
            for (int i = Math.max(0, candidate - minGap + 1); i < Math.min(y.length, candidate + minGap); i++) {
                if (i != candidate) dropped[i] = true;
            }
        }
        Collections.sort(spaced);
        if (spaced.isEmpty()) return new int[0];

        // a valley's prominence: how far the signal climbs on both sides before anything deeper
        double[] prominence = new double[spaced.size()];
        double[] sorted = new double[spaced.size()];
        for (int c = 0; c < prominence.length; c++) {
            prominence[c] = prominence(y, spaced.get(c));
            sorted[c] = prominence[c];
        }
        Arrays.sort(sorted);
        double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;

        List<Integer> kept = new ArrayList<>();
        List<Double> keptProminence = new ArrayList<>();
        for (int c = 0; c < prominence.length; c++) {
            if (prominence[c] >= PROMINENCE_FLOOR * median) {
                kept.add(spaced.get(c));
                keptProminence.add(prominence[c]);
            }
        }

        // two valleys inside one rhythm period are one beat; the closest pair goes first
        while (kept.size() > 1) {
            int closest = 0;
            int smallest = Integer.MAX_VALUE;
            for (int i = 0; i + 1 < kept.size(); i++) {
                int gap = kept.get(i + 1) - kept.get(i);
                if (gap < smallest) {
                    smallest = gap;
                    closest = i;
                }
            }
            if (smallest >= REFRACTORY * periodSamples) break;
            int drop = keptProminence.get(closest) < keptProminence.get(closest + 1) ? closest : closest + 1;
            kept.remove(drop);
            keptProminence.remove(drop);
        }

        int[] out = new int[kept.size()];
        for (int i = 0; i < out.length; i++) out[i] = kept.get(i);
        return out;
    }

    private static double prominence(double[] y, int valley) {
        double left = y[valley];
        for (int i = valley - 1; i >= 0 && y[i] >= y[valley]; i--) left = Math.max(left, y[i]);
        double right = y[valley];
        for (int i = valley + 1; i < y.length && y[i] >= y[valley]; i++) right = Math.max(right, y[i]);
        return Math.min(left, right) - y[valley];
    }
}

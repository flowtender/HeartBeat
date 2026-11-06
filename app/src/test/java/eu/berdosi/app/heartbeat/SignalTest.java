package eu.berdosi.app.heartbeat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SignalTest {
    // ---- FingerSettle ----

    private static final int FRAME_MS = 45;

    /** Feeds frames of a finger-like picture whose red level is red(t), for the given duration. */
    private static FingerSettle.Status feed(FingerSettle settle, long from, long to,
                                            java.util.function.DoubleUnaryOperator red) {
        FingerSettle.Status status = null;
        for (long t = from; t <= to; t += FRAME_MS) {
            status = settle.update(t, red.applyAsDouble(t - from), 40, 30);
        }
        return status;
    }

    @Test
    public void aSteadyFingerIsStableOnlyAfterTheWindowHasBeenWatched() {
        FingerSettle settle = new FingerSettle();
        assertEquals(FingerSettle.Status.SETTLING, feed(settle, 0, 1500, t -> 200));
        assertEquals(FingerSettle.Status.STABLE, feed(settle, 1545, 2600, t -> 200));
    }

    @Test
    public void aHeartbeatSizedWobbleStillCountsAsSteady() {
        FingerSettle settle = new FingerSettle();
        // +-2% at 1.2 Hz
        assertEquals(FingerSettle.Status.STABLE,
                feed(settle, 0, 4000, t -> 200 * (1 + 0.02 * Math.sin(2 * Math.PI * 1.2 * t / 1000))));
    }

    @Test
    public void aDriftingLevelIsNotStableUntilItLevelsOff() {
        FingerSettle settle = new FingerSettle();
        // exposure still adapting: 150 -> 220 over 3 s, then flat
        assertEquals(FingerSettle.Status.SETTLING,
                feed(settle, 0, 3000, t -> 150 + 70 * Math.min(1, t / 3000)));
        assertEquals(FingerSettle.Status.STABLE, feed(settle, 3045, 5200, t -> 220));
    }

    @Test
    public void aPictureThatIsNotAFingerNeverSettles() {
        FingerSettle settle = new FingerSettle();
        FingerSettle.Status status = null;
        for (long t = 0; t < 5000; t += FRAME_MS) status = settle.update(t, 150, 150, 150); // neutral
        assertEquals(FingerSettle.Status.NO_FINGER, status);
        for (long t = 5000; t < 10000; t += FRAME_MS) status = settle.update(t, 40, 5, 5); // red but dark
        assertEquals(FingerSettle.Status.NO_FINGER, status);
    }

    @Test
    public void liftingTheFingerRestartsTheWatch() {
        FingerSettle settle = new FingerSettle();
        assertEquals(FingerSettle.Status.STABLE, feed(settle, 0, 3000, t -> 200));
        assertEquals(FingerSettle.Status.NO_FINGER, settle.update(3045, 120, 120, 120));
        assertEquals(FingerSettle.Status.SETTLING, settle.update(3090, 200, 40, 30));
        assertEquals(FingerSettle.Status.SETTLING, feed(settle, 3135, 4500, t -> 200));
        assertEquals(FingerSettle.Status.STABLE, feed(settle, 4545, 5300, t -> 200));
    }

    @Test
    public void fingerCheckNeedsBrightAndRed() {
        assertTrue(FingerSettle.looksLikeFinger(220, 50, 30));
        assertFalse(FingerSettle.looksLikeFinger(200, 150, 140));
        assertFalse(FingerSettle.looksLikeFinger(60, 5, 5));
        assertFalse(FingerSettle.looksLikeFinger(0, 0, 0));
    }

    // ---- SignalQuality ----

    private static List<Long> beats(int count, long interval, long... skipped) {
        List<Long> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean skip = false;
            for (long s : skipped) skip |= s == i;
            if (!skip) list.add(1_000_000L + i * interval);
        }
        return list;
    }

    private static BeatDetector.Result found(List<Long> beats, double spectrumBpm, double periodicity) {
        long[] times = new long[beats.size()];
        for (int i = 0; i < times.length; i++) times[i] = beats.get(i);
        return new BeatDetector.Result(times, spectrumBpm, periodicity);
    }

    @Test
    public void regularBeatsInARhythmicSignalAreOk() {
        // 850 ms apart is 70.6 bpm
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(found(beats(12, 850), 71, 0.8)));
    }

    @Test
    public void aSignalWithoutARhythmIsInvalidHoweverRegularTheBeatsLook() {
        assertEquals(SignalQuality.Verdict.IRREGULAR, SignalQuality.assess(found(beats(12, 850), 71, 0.2)));
    }

    @Test
    public void beatsThatDisagreeWithTheSignalsRhythmAreInvalid() {
        // the beats say 70.6 bpm, the signal says 90
        assertEquals(SignalQuality.Verdict.IRREGULAR, SignalQuality.assess(found(beats(12, 850), 90, 0.8)));
    }

    @Test
    public void tooFewBeatsIsReportedBeforeTheRhythmIsConsulted() {
        assertEquals(SignalQuality.Verdict.TOO_FEW_BEATS,
                SignalQuality.assess(found(beats(4, 850), Double.NaN, Double.NaN)));
    }

    @Test
    public void regularBeatsAreOk() {
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(beats(12, 850)));
    }

    @Test
    public void naturalVariabilityIsOk() {
        List<Long> list = new ArrayList<>();
        long t = 0;
        // intervals wander +-12% around 850 ms
        double[] wobble = {1.0, 1.08, 1.12, 1.03, 0.92, 0.88, 0.95, 1.05, 1.1, 0.97, 0.9, 1.0};
        for (double w : wobble) {
            list.add(t);
            t += (long) (850 * w);
        }
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(list));
    }

    @Test
    public void tooFewBeatsIsReportedAsSuch() {
        assertEquals(SignalQuality.Verdict.TOO_FEW_BEATS, SignalQuality.assess(beats(5, 850)));
    }

    @Test
    public void oneMissedBeatInAGoodReadingIsTolerated() {
        // 15 beats, one dropped: a single double-length interval among 13
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(beats(15, 800, 7)));
    }

    @Test
    public void aLongDropoutMakesItInvalid() {
        // three beats in a row missing: a gap of 4x the median
        assertEquals(SignalQuality.Verdict.IRREGULAR, SignalQuality.assess(beats(16, 800, 6, 7, 8)));
    }

    @Test
    public void manyMissedBeatsMakeItInvalid() {
        assertEquals(SignalQuality.Verdict.IRREGULAR,
                SignalQuality.assess(beats(16, 800, 3, 5, 8, 11)));
    }

    @Test
    public void noiseSpacedUnevenlyIsInvalid() {
        long[] gaps = {200, 900, 150, 400, 1200, 300, 250, 800, 100, 600, 350, 950};
        List<Long> list = new ArrayList<>();
        long t = 0;
        for (long g : gaps) {
            list.add(t);
            t += g;
        }
        assertEquals(SignalQuality.Verdict.IRREGULAR, SignalQuality.assess(list));
    }

    @Test
    public void beatOrderDoesNotMatter() {
        List<Long> list = beats(12, 850);
        java.util.Collections.reverse(list);
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(list));
    }

    private static List<Long> fromIntervals(long... intervals) {
        List<Long> list = new ArrayList<>();
        long t = 5_000_000L;
        list.add(t);
        for (long interval : intervals) list.add(t += interval);
        return list;
    }

    @Test
    public void recordedReadingWithADoubledAndSplitBeatButRightPulseIsOk() {
        // from a real finger: one beat found twice (46 ms), two missed beats; ~75 bpm either way
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(fromIntervals(
                46, 527, 782, 852, 799, 911, 798, 752, 1247, 1318, 799, 777, 833)));
    }

    @Test
    public void recordedReadingThatMissedManyBeatsHasTheWrongPulseAndIsInvalid() {
        // from a real finger: about 88 bpm in the picture, 71 reported
        assertEquals(SignalQuality.Verdict.IRREGULAR, SignalQuality.assess(fromIntervals(
                1338, 1307, 719, 677, 1338, 623, 654, 634, 684, 1246, 633, 611)));
    }
}

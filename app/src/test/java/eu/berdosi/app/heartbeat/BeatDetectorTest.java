package eu.berdosi.app.heartbeat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BeatDetectorTest {
    /** Beats per minute from the first detected beat to the last, as the app reports it. */
    private static double pulse(BeatDetector.Result result) {
        long[] beats = result.beatTimes;
        return 60000.0 * (beats.length - 1) / (beats[beats.length - 1] - beats[0]);
    }

    /**
     * A finger's red level: a drifting baseline with a dip at every beat. Beats arrive every
     * intervalMs; sample times are jittered the way unevenly delivered frames are.
     */
    private static BeatDetector.Result synthetic(double intervalMs, double seconds, boolean notch, long seed) {
        Random random = new Random(seed);
        List<Long> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        double t = 0;
        while (t < seconds * 1000) {
            double phase = (t % intervalMs) / intervalMs;
            // fast fall into the valley, slow recovery: the shape of the recorded readings
            double beat = phase < 0.12 ? -phase / 0.12 : -1 + (phase - 0.12) / 0.88;
            double dicrotic = notch ? -0.25 * Math.exp(-Math.pow((phase - 0.45) / 0.04, 2)) : 0;
            double drift = 3 * Math.sin(2 * Math.PI * t / 1000 / 17) + 0.002 * t;
            values.add(100 + drift + beat + dicrotic + 0.05 * random.nextGaussian());
            times.add(1_000_000L + Math.round(t));
            t += 33 + random.nextInt(25);
        }
        long[] timeArray = new long[times.size()];
        double[] valueArray = new double[times.size()];
        for (int i = 0; i < timeArray.length; i++) {
            timeArray[i] = times.get(i);
            valueArray[i] = values.get(i);
        }
        return BeatDetector.analyse(timeArray, valueArray);
    }

    @Test
    public void aPulseRidingOnAFarLargerDriftIsFound() {
        // the drift moves by several units, each beat by one
        for (double bpm : new double[]{50, 72, 110, 150}) {
            BeatDetector.Result result = synthetic(60000 / bpm, 12, false, (long) bpm);
            assertEquals("pulse " + bpm, bpm, pulse(result), bpm * 0.03);
            assertEquals(bpm, result.spectrumBpm, bpm * 0.05);
        }
    }

    @Test
    public void aSecondBumpInEachBeatDoesNotDoubleThePulse() {
        BeatDetector.Result result = synthetic(800, 12, true, 7);
        assertEquals(75, pulse(result), 2.5);
    }

    @Test
    public void aCleanSignalIsMostlyRhythm() {
        BeatDetector.Result result = synthetic(800, 12, false, 3);
        assertTrue(result.periodicity > SignalQuality.MIN_PERIODICITY);
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(result));
    }

    @Test
    public void aFlatPictureHasNoBeats() {
        long[] times = new long[300];
        double[] values = new double[300];
        for (int i = 0; i < times.length; i++) {
            times[i] = i * 40L;
            values[i] = 120;
        }
        assertEquals(0, BeatDetector.analyse(times, values).beatTimes.length);
    }

    @Test
    public void tooLittleSignalGivesNothing() {
        long[] times = {0, 40, 80, 120};
        double[] values = {1, 2, 1, 2};
        BeatDetector.Result result = BeatDetector.analyse(times, values);
        assertEquals(0, result.beatTimes.length);
        assertTrue(Double.isNaN(result.spectrumBpm));
    }

    @Test
    public void beatsComeOutAscendingAndWithinTheRecording() {
        BeatDetector.Result result = synthetic(700, 12, true, 11);
        for (int i = 1; i < result.beatTimes.length; i++) {
            assertTrue(result.beatTimes[i] > result.beatTimes[i - 1]);
        }
        assertTrue(result.beatTimes[0] >= 1_000_000L);
        assertTrue(result.beatTimes[result.beatTimes.length - 1] <= 1_000_000L + 12_000);
    }

    // ---- recorded readings (see src/test/resources/readings; samples as the detector saw them) ----

    private static BeatDetector.Result recorded(int id) throws Exception {
        List<Long> times = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                BeatDetectorTest.class.getResourceAsStream("/readings/reading" + id + ".csv")))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] parts = line.split(",");
                times.add(Long.parseLong(parts[0]));
                values.add(Double.parseDouble(parts[1]));
            }
        }
        long[] timeArray = new long[times.size()];
        double[] valueArray = new double[times.size()];
        for (int i = 0; i < timeArray.length; i++) {
            timeArray[i] = times.get(i);
            valueArray[i] = values.get(i);
        }
        return BeatDetector.analyse(timeArray, valueArray);
    }

    @Test
    public void recordedCleanReadingsAgreeWithTheirSpectrum() throws Exception {
        // sitting 72 bpm, and standing 106 bpm: the highest rate in the log
        for (int id : new int[]{4, 26, 10}) {
            BeatDetector.Result result = recorded(id);
            assertEquals("reading " + id, result.spectrumBpm, pulse(result), result.spectrumBpm * 0.04);
            assertEquals("reading " + id, SignalQuality.Verdict.OK, SignalQuality.assess(result));
        }
        assertEquals(72, pulse(recorded(4)), 2);
        assertEquals(106, pulse(recorded(10)), 4);
    }

    @Test
    public void recordedReadingTheOldDetectorGotWrongIsNowRight() throws Exception {
        // a clear pulse of about 88 bpm; the old detector reported 65-69 and marked it invalid
        BeatDetector.Result standing = recorded(18);
        assertEquals(88, pulse(standing), 6);
        // the same fast pulse, 92 bpm, which the old detector reported as 69 without any complaint
        assertEquals(92, pulse(recorded(23)), 3);
    }

    @Test
    public void recordedReadingsFromAfterTheTuningAgreeWithTheirSpectrum() throws Exception {
        // standing, ~95 and ~93 bpm; the old detector reported 77 and 72 and marked both invalid
        for (int id : new int[]{40, 42}) {
            BeatDetector.Result result = recorded(id);
            assertEquals("reading " + id, result.spectrumBpm, pulse(result), result.spectrumBpm * 0.03);
            assertEquals("reading " + id, SignalQuality.Verdict.OK, SignalQuality.assess(result));
        }
        assertEquals(95, pulse(recorded(40)), 3);
        assertEquals(93, pulse(recorded(42)), 3);
    }

    @Test
    public void readingFromFrameDrivenSamplingAt30FramesPerSecond() throws Exception {
        // the first reading taken after sampling followed the camera's frames (33 ms apart, no rolling average)
        BeatDetector.Result result = recorded(44);
        assertEquals(69.5, pulse(result), 2);
        assertEquals(SignalQuality.Verdict.OK, SignalQuality.assess(result));
    }

    @Test
    public void recordedReadingsDominatedByMovementAreNotTrusted() throws Exception {
        for (int id : new int[]{12, 17}) {
            assertNotEquals("reading " + id, SignalQuality.Verdict.OK, SignalQuality.assess(recorded(id)));
        }
    }
}

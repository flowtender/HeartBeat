package eu.berdosi.app.heartbeat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PostureTest {
    private static final float G = 9.81f;

    // ---- MotionWindow ----

    @Test
    public void restingPhoneHasGravityDirectionAndNoMotion() {
        MotionWindow window = new MotionWindow();
        for (int i = 0; i < 100; i++) window.add(i * 20, 0f, 0f, G);
        Posture p = window.snapshot(99 * 20);
        assertNotNull(p);
        assertEquals(1f, p.gz, 1e-4);
        assertEquals(0f, p.gx, 1e-4);
        assertEquals(0f, p.motion, 1e-3);
    }

    @Test
    public void walkingShakesTheMagnitude() {
        MotionWindow window = new MotionWindow();
        // ~2 Hz bounce of +-3 m/s² along gravity, 50 samples per second
        for (int i = 0; i < 150; i++) {
            window.add(i * 20, 0f, 0f, G + 3f * (float) Math.sin(2 * Math.PI * 2 * i * 0.02));
        }
        Posture p = window.snapshot(149 * 20);
        assertNotNull(p);
        assertTrue("motion " + p.motion, p.motion >= PostureClassifier.WALKING_MOTION);
        assertEquals(1f, p.gz, 0.05);
    }

    @Test
    public void samplesOlderThanTheWindowAreIgnored() {
        MotionWindow window = new MotionWindow();
        // phone face down, then 3+ seconds later face up
        for (int i = 0; i < 100; i++) window.add(i * 20, 0f, 0f, -G);
        for (int i = 0; i < 100; i++) window.add(10_000 + i * 20, 0f, 0f, G);
        Posture p = window.snapshot(10_000 + 99 * 20);
        assertNotNull(p);
        assertEquals(1f, p.gz, 1e-4);
    }

    @Test
    public void tooFewOrStaleSamplesGiveNoPosture() {
        MotionWindow window = new MotionWindow();
        assertNull(window.snapshot(0));
        for (int i = 0; i < 5; i++) window.add(i * 20, 0f, 0f, G);
        assertNull(window.snapshot(100));
        for (int i = 5; i < 50; i++) window.add(i * 20, 0f, 0f, G);
        assertNull(window.snapshot(60_000));
    }

    @Test
    public void bufferWrapsAroundKeepingTheNewestSamples() {
        MotionWindow window = new MotionWindow();
        // 2000 samples at 1 ms: far more than the buffer holds, orientation flips halfway
        for (int i = 0; i < 1000; i++) window.add(i, 0f, 0f, -G);
        for (int i = 1000; i < 2000; i++) window.add(i, 0f, 0f, G);
        Posture p = window.snapshot(1999);
        assertNotNull(p);
        assertEquals(1f, p.gz, 1e-4);
    }

    // ---- PostureClassifier ----

    private static Posture at(float gx, float gy, float gz, float motion) {
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        return new Posture((float) (gx / n), (float) (gy / n), (float) (gz / n), motion);
    }

    private static ReadingEntry labelled(ActivityLevel level, float gx, float gy, float gz) {
        double n = Math.sqrt(gx * gx + gy * gy + gz * gz);
        ReadingEntry e = new ReadingEntry(0, 70f, 15, 13f,
                new float[]{(float) (gx / n), (float) (gy / n), (float) (gz / n)});
        e.activity = level;
        return e;
    }

    private static List<ReadingEntry> repeat(ActivityLevel level, int count, float gx, float gy, float gz) {
        List<ReadingEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(labelled(level, gx, gy, gz));
        return list;
    }

    @Test
    public void walkingIsRecognisedWithoutHistory() {
        assertEquals(ActivityLevel.WALKING,
                PostureClassifier.suggest(at(0, 5, 8, 2.5f), new ArrayList<>()));
    }

    @Test
    public void noHistoryNoGuess() {
        assertNull(PostureClassifier.suggest(at(0, 5, 8, 0.1f), new ArrayList<>()));
        assertNull(PostureClassifier.suggest(null, new ArrayList<>()));
    }

    @Test
    public void closestLabelledTiltsVote() {
        List<ReadingEntry> history = repeat(ActivityLevel.LYING, 4, 0, 0, -1);
        history.addAll(repeat(ActivityLevel.STANDING, 4, 0, 6, 8));
        assertEquals(ActivityLevel.STANDING,
                PostureClassifier.suggest(at(0, 6.5f, 7.5f, 0.1f), history));
        assertEquals(ActivityLevel.LYING,
                PostureClassifier.suggest(at(0.3f, 0, -1, 0.1f), history));
    }

    @Test
    public void tiltFarFromEverythingLabelledGivesNoGuess() {
        List<ReadingEntry> history = repeat(ActivityLevel.STANDING, 6, 0, 6, 8);
        assertNull(PostureClassifier.suggest(at(0, 0, -1, 0.1f), history));
    }

    @Test
    public void fewerThanThreeVotersGiveNoGuess() {
        assertNull(PostureClassifier.suggest(at(0, 6, 8, 0.1f),
                repeat(ActivityLevel.STANDING, 2, 0, 6, 8)));
        assertEquals(ActivityLevel.STANDING, PostureClassifier.suggest(at(0, 6, 8, 0.1f),
                repeat(ActivityLevel.STANDING, 3, 0, 6, 8)));
    }

    @Test
    public void splitVoteGivesNoGuess() {
        List<ReadingEntry> history = repeat(ActivityLevel.SITTING, 3, 0, 6, 8);
        history.addAll(repeat(ActivityLevel.STANDING, 2, 0, 6, 8));
        // 3 of 5 is below the required agreement
        assertNull(PostureClassifier.suggest(at(0, 6, 8, 0.1f), history));
    }

    @Test
    public void readingsTheDetectorLabelledItselfDoNotTrainIt() {
        List<ReadingEntry> history = repeat(ActivityLevel.STANDING, 6, 0, 6, 8);
        for (ReadingEntry e : history) e.activityAuto = true;
        assertNull(PostureClassifier.suggest(at(0, 6, 8, 0.1f), history));
    }

    @Test
    public void walkingAndAerobicLabelsDoNotVote() {
        List<ReadingEntry> history = repeat(ActivityLevel.WALKING, 6, 0, 6, 8);
        history.addAll(repeat(ActivityLevel.AEROBIC, 6, 0, 6, 8));
        assertNull(PostureClassifier.suggest(at(0, 6, 8, 0.1f), history));
    }
}

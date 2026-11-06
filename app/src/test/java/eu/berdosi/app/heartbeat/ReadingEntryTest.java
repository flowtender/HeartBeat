package eu.berdosi.app.heartbeat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ReadingEntryTest {
    private static final long MINUTE = 60 * 1000L;
    private static final long T0 = 1_000_000_000_000L;

    private static ReadingEntry entry(long timestamp, ActivityLevel level, Integer durationMin) {
        ReadingEntry e = new ReadingEntry(timestamp, 70f, 15, 13f, null);
        e.activity = level;
        e.durationMin = durationMin;
        return e;
    }

    @Test
    public void continuesDurationOfSameActivityWithinFifteenMinutes() {
        ReadingEntry previous = entry(T0, ActivityLevel.LYING, 10);
        assertEquals(Integer.valueOf(14),
                ReadingEntry.suggestDuration(previous, ActivityLevel.LYING, T0 + 4 * MINUTE));
    }

    @Test
    public void fifteenMinutesExactlyStillContinues_oneMillisecondMoreDoesNot() {
        ReadingEntry previous = entry(T0, ActivityLevel.STANDING, 2);
        assertEquals(Integer.valueOf(17),
                ReadingEntry.suggestDuration(previous, ActivityLevel.STANDING, T0 + 15 * MINUTE));
        assertEquals(Integer.valueOf(0),
                ReadingEntry.suggestDuration(previous, ActivityLevel.STANDING, T0 + 15 * MINUTE + 1));
    }

    @Test
    public void changingActivityStartsOver() {
        ReadingEntry previous = entry(T0, ActivityLevel.LYING, 30);
        assertEquals(Integer.valueOf(0),
                ReadingEntry.suggestDuration(previous, ActivityLevel.STANDING, T0 + MINUTE));
    }

    @Test
    public void previousWithoutDurationCountsFromZero() {
        ReadingEntry previous = entry(T0, ActivityLevel.SITTING, null);
        assertEquals(Integer.valueOf(3),
                ReadingEntry.suggestDuration(previous, ActivityLevel.SITTING, T0 + 3 * MINUTE));
    }

    @Test
    public void noPreviousReadingStartsAtZero_noActivityHasNoDuration() {
        assertEquals(Integer.valueOf(0), ReadingEntry.suggestDuration(null, ActivityLevel.LYING, T0));
        assertNull(ReadingEntry.suggestDuration(entry(T0, ActivityLevel.LYING, 5), null, T0 + MINUTE));
    }

    @Test
    public void sliderThumbSnapsToTheClosestStep_lowerOnTie() {
        int[] steps = ReadingEntry.DURATION_STEPS_MIN;
        assertEquals(1, steps[ReadingEntry.nearestDurationStep(0)]);
        assertEquals(7, steps[ReadingEntry.nearestDurationStep(7)]);
        assertEquals(10, steps[ReadingEntry.nearestDurationStep(12)]);
        assertEquals(15, steps[ReadingEntry.nearestDurationStep(13)]);
        assertEquals(30, steps[ReadingEntry.nearestDurationStep(35)]);
        assertEquals(90, steps[ReadingEntry.nearestDurationStep(100)]);
        assertEquals(240, steps[ReadingEntry.nearestDurationStep(5000)]);
    }
}

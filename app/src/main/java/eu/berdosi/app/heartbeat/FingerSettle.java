package eu.berdosi.app.heartbeat;

/**
 * Decides from the camera picture whether a finger covers the lens and the colour has stopped
 * changing, so a reading can begin. Fed one frame average at a time; thread-safe.
 *
 * With the torch lit through a fingertip the picture is bright and red-dominated. After the
 * finger lands, or the torch comes on, the exposure keeps adapting for a moment, so the average
 * red level drifts; it is steady once, over the last {@link #WINDOW_MS}, it has neither trended
 * nor swung more than a heartbeat's worth.
 */
final class FingerSettle {
    enum Status {
        /** The picture doesn't look like a finger over the lens. */
        NO_FINGER,
        /** A finger, but the colour is still changing or has not been watched for long enough. */
        SETTLING,
        STABLE
    }

    static final long WINDOW_MS = 2000;
    /** Mean red level, 0-255, below which the picture is too dark to be a lit fingertip. */
    static final double MIN_RED = 90;
    /** Red's share of red + green + blue; about 1/3 for a neutral picture, much higher through a finger. */
    static final double MIN_RED_SHARE = 0.55;
    /** The second half of the window may differ from the first by this fraction of the mean. */
    static final double MAX_DRIFT = 0.02;
    /** Highest minus lowest red level in the window, as a fraction of the mean. */
    static final double MAX_RANGE = 0.10;
    private static final int MIN_SAMPLES = 20;
    private static final int CAPACITY = 256;

    private final long[] times = new long[CAPACITY];
    private final double[] reds = new double[CAPACITY];
    private int next = 0;
    private int size = 0;
    /** Time of the first frame of the current unbroken run of finger-like frames, or -1. */
    private long fingerSince = -1;

    /** Whether one frame, as mean channel levels 0-255, looks like a lit fingertip. */
    static boolean looksLikeFinger(double red, double green, double blue) {
        double total = red + green + blue;
        return red >= MIN_RED && total > 0 && red / total >= MIN_RED_SHARE;
    }

    synchronized Status update(long timeMs, double red, double green, double blue) {
        if (!looksLikeFinger(red, green, blue)) {
            fingerSince = -1;
            size = 0;
            next = 0;
            return Status.NO_FINGER;
        }
        if (fingerSince < 0) fingerSince = timeMs;

        times[next] = timeMs;
        reds[next] = red;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;

        if (timeMs - fingerSince < WINDOW_MS) return Status.SETTLING;

        // the samples of the window, oldest first
        int count = 0;
        while (count < size && times[(next - 1 - count + CAPACITY) % CAPACITY] >= timeMs - WINDOW_MS) count++;
        if (count < MIN_SAMPLES) return Status.SETTLING;

        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        double firstHalf = 0, secondHalf = 0;
        int half = count / 2;
        for (int i = 0; i < count; i++) {
            double value = reds[(next - count + i + CAPACITY) % CAPACITY];
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (i < half) firstHalf += value;
            else if (i >= count - half) secondHalf += value;
        }
        double mean = sum / count;
        double drift = Math.abs(secondHalf - firstHalf) / half / mean;
        double range = (max - min) / mean;
        return drift <= MAX_DRIFT && range <= MAX_RANGE ? Status.STABLE : Status.SETTLING;
    }
}

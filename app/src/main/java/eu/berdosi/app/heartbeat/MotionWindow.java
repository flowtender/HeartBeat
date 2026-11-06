package eu.berdosi.app.heartbeat;

/** The most recent accelerometer samples, in a fixed ring buffer. Not thread-safe. */
final class MotionWindow {
    static final long WINDOW_MS = 3000;
    /** Fewer samples than this in the window say nothing about orientation. */
    static final int MIN_SAMPLES = 10;
    private static final int CAPACITY = 512;

    private final long[] times = new long[CAPACITY];
    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];
    private final float[] zs = new float[CAPACITY];
    private int next = 0;
    private int size = 0;

    void add(long timeMs, float x, float y, float z) {
        times[next] = timeMs;
        xs[next] = x;
        ys[next] = y;
        zs[next] = z;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    /** Summary of the samples in the {@link #WINDOW_MS} up to {@code nowMs}; null when too few. */
    Posture snapshot(long nowMs) {
        double sumX = 0, sumY = 0, sumZ = 0, sumMag = 0, sumMagSquares = 0;
        int count = 0;
        for (int age = 1; age <= size; age++) {
            int i = (next - age + CAPACITY) % CAPACITY;
            if (times[i] < nowMs - WINDOW_MS) break;
            if (times[i] > nowMs) continue;
            double x = xs[i], y = ys[i], z = zs[i];
            double magnitude = Math.sqrt(x * x + y * y + z * z);
            sumX += x;
            sumY += y;
            sumZ += z;
            sumMag += magnitude;
            sumMagSquares += magnitude * magnitude;
            count++;
        }
        if (count < MIN_SAMPLES) return null;

        double norm = Math.sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ);
        // an average near zero is free fall or a sensor glitch: no direction to speak of
        if (norm / count < 1.0) return null;

        double meanMag = sumMag / count;
        double variance = Math.max(0, sumMagSquares / count - meanMag * meanMag);
        return new Posture(
                (float) (sumX / norm), (float) (sumY / norm), (float) (sumZ / norm),
                (float) Math.sqrt(variance));
    }
}

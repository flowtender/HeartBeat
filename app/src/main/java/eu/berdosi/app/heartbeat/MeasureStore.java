package eu.berdosi.app.heartbeat;

import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

/** The brightness samples of one reading, in the order they were taken. Thread-safe. */
class MeasureStore {
    private long[] times = new long[512];
    private double[] values = new double[512];
    private int size = 0;
    private double minimum = Double.MAX_VALUE;
    private double maximum = -Double.MAX_VALUE;

    synchronized void add(long timeMs, double value) {
        if (size == times.length) {
            times = Arrays.copyOf(times, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        times[size] = timeMs;
        values[size] = value;
        size++;
        if (value < minimum) minimum = value;
        if (value > maximum) maximum = value;
    }

    synchronized int size() {
        return size;
    }

    synchronized long[] times() {
        return Arrays.copyOf(times, size);
    }

    synchronized double[] values() {
        return Arrays.copyOf(values, size);
    }

    /**
     * The samples scaled to 0-1 between the lowest and highest seen so far.
     *
     * @param rolling each value is the average of this many latest samples; 1 for none
     */
    synchronized CopyOnWriteArrayList<Measurement<Float>> getStdValues(int rolling) {
        CopyOnWriteArrayList<Measurement<Float>> stdValues = new CopyOnWriteArrayList<>();

        for (int i = 0; i < size; i++) {
            double sum = 0;
            for (int back = 0; back < rolling; back++) {
                sum += values[Math.max(0, i - back)];
            }
            stdValues.add(new Measurement<>(
                    new Date(times[i]),
                    (float) ((sum / rolling - minimum) / (maximum - minimum))));
        }

        return stdValues;
    }
}
